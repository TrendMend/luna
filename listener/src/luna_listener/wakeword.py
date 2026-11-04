"""Local wake-word detection with openWakeWord (ONNX). Nothing here touches the network."""
import asyncio
import logging
import pathlib

import numpy as np

from .triggers import Trigger

log = logging.getLogger("luna.wakeword")
CHUNK = 1280          # 80 ms at 16 kHz: what openWakeWord expects per call


class OpenWakeWordDetector:
    """predict(chunk) -> {wake word id: score 0..1}."""

    def __init__(self, models: dict[str, str]):
        import openwakeword
        from openwakeword.model import Model
        resources = pathlib.Path(openwakeword.__file__).parent / "resources" / "models"
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
        self._model = Model(wakeword_models=paths, inference_framework="onnx")

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

    @classmethod
    def from_config(cls, cfg: dict) -> "WakeWordSource":
        ww = cfg["wake_words"]
        det = OpenWakeWordDetector({wid: w["model"] for wid, w in ww.items()})
        return cls(det, {wid: float(w.get("threshold", 0.5)) for wid, w in ww.items()},
                   int(cfg["detection"].get("patience", 2)))

    def on_audio(self, samples: np.ndarray) -> None:
        """Feed int16 mono 16 kHz audio of any length. Triggers are queued for run() to emit."""
        self._buf = np.concatenate([self._buf, samples.astype(np.int16, copy=False)])
        while len(self._buf) >= CHUNK:
            chunk, self._buf = self._buf[:CHUNK], self._buf[CHUNK:]
            scores = self.detector.predict(chunk)
            for wid, thr in self.thresholds.items():
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
