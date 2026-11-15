"""Local wake-word detection with openWakeWord (ONNX). Nothing here touches the network."""
import asyncio
import logging
import os
import pathlib
import time

import numpy as np

from .triggers import Trigger

log = logging.getLogger("luna.wakeword")
CHUNK = 1280          # 80 ms at 16 kHz: what openWakeWord expects per call
NEAR_MISS = 0.15      # log scores from here up to the threshold, so a word that is heard but not accepted leaves a trace
HEARTBEAT = 750       # chunks (60 s): log the microphone level, to show the microphone is alive


def models_dir(cfg: dict) -> str | None:
    """Where built-in / helper models are kept: $LUNA_MODELS_DIR, else `models_dir:` in the config, else the package."""
    return os.environ.get("LUNA_MODELS_DIR") or cfg.get("models_dir") or None


class OpenWakeWordDetector:
    """predict(chunk) -> {wake word id: score 0..1}."""

    def __init__(self, models: dict[str, str], models_dir: str | None = None):
        import openwakeword
        from openwakeword.model import Model
        # Built-in and helper models live in models_dir if given (needed on NixOS, where the package is read-only),
        # otherwise inside the openWakeWord package.
        resources = pathlib.Path(models_dir) if models_dir else pathlib.Path(openwakeword.__file__).parent / "resources" / "models"
        paths, self._keys = [], {}
        for wid, m in models.items():
            p = pathlib.Path(m)
            if not p.suffix:                                     # a built-in name like "hey_jarvis"
                found = sorted(resources.glob(f"{m}_v*.onnx"))
                if not found:
                    raise FileNotFoundError(f"no built-in wake word model '{m}' (run: luna-listener download-models)")
                p = found[-1]
            elif not p.exists():
                raise FileNotFoundError(f"wake word model not found: {p}")
            paths.append(str(p))
            self._keys[p.stem] = wid
        extra = {}
        if models_dir:
            for key, name in (("melspec_model_path", "melspectrogram.onnx"), ("embedding_model_path", "embedding_model.onnx")):
                if not (resources / name).exists():
                    raise FileNotFoundError(f"{resources / name} missing (run: luna-listener download-models)")
                extra[key] = str(resources / name)
        self._model = Model(wakeword_models=paths, inference_framework="onnx", **extra)

    def predict(self, chunk: np.ndarray) -> dict[str, float]:
        scores = self._model.predict(chunk)
        return {self._keys[k]: float(v) for k, v in scores.items() if k in self._keys}

    def reset(self) -> None:
        self._model.reset()


class WakeWordSource:
    def __init__(self, detector, thresholds: dict[str, float], patience: int = 2):
        self.detector, self.thresholds, self.patience = detector, thresholds, max(1, patience)
        self._buf = np.zeros(0, dtype=np.int16)
        self._streak: dict[str, int] = {}
        self._pending: list[Trigger] = []
        self._peak = 0
        self._chunks = 0
        self._last_near: dict[str, float] = {}

    @classmethod
    def from_config(cls, cfg: dict) -> "WakeWordSource":
        ww = cfg["wake_words"]
        det = OpenWakeWordDetector({wid: w["model"] for wid, w in ww.items()}, models_dir(cfg))
        return cls(det, {wid: float(w.get("threshold", 0.5)) for wid, w in ww.items()},
                   int(cfg["detection"].get("patience", 2)))

    def on_audio(self, samples: np.ndarray) -> None:
        """Feed int16 mono 16 kHz audio of any length. Triggers are queued for run() to emit."""
        self._buf = np.concatenate([self._buf, samples.astype(np.int16, copy=False)])
        while len(self._buf) >= CHUNK:
            chunk, self._buf = self._buf[:CHUNK], self._buf[CHUNK:]
            self._peak = max(self._peak, int(np.abs(chunk.astype(np.int32)).max()))
            self._chunks += 1
            if self._chunks % HEARTBEAT == 0:
                log.info("listening; microphone peak in the last minute: %d of 32767", self._peak)
                self._peak = 0
            scores = self.detector.predict(chunk)
            for wid, thr in self.thresholds.items():
                sc = scores.get(wid, 0.0)
                if NEAR_MISS <= sc and time.monotonic() - self._last_near.get(wid, 0.0) > 1.0:
                    self._last_near[wid] = time.monotonic()
                    log.info("heard something like %s: score %.2f (needs %.2f for %d chunks in a row)", wid, sc, thr, self.patience)
                if scores.get(wid, 0.0) >= thr:
                    self._streak[wid] = self._streak.get(wid, 0) + 1
                    if self._streak[wid] >= self.patience:
                        log.info("wake word %s (score %.2f)", wid, scores[wid])
                        self._pending.append(Trigger("wake_word", wake_word=wid))
                        self.reset()
                        return
                else:
                    self._streak[wid] = 0

    def reset(self) -> None:
        """Forget buffered audio and detector state (after a trigger, and when a conversation ends)."""
        self._buf = np.zeros(0, dtype=np.int16)
        self._streak.clear()
        self.detector.reset()

    def take(self) -> list[Trigger]:
        out, self._pending = self._pending, []
        return out

    async def run(self, emit) -> None:
        while True:
            for t in self.take():
                await emit(t)
            await asyncio.sleep(0.02)
