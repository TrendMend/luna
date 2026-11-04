"""luna-listener: run | devices | test-wakeword | download-models"""
import argparse
import asyncio
import logging
import os
import sys

from dotenv import load_dotenv

from .config import ConfigError, load_config


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(prog="luna-listener", description="Luna device endpoint.")
    p.add_argument("-c", "--config", default=os.environ.get("LUNA_LISTENER_CONFIG", "config.yaml"))
    sub = p.add_subparsers(dest="cmd")
    sub.add_parser("run", help="listen for wake words and calls (default)")
    sub.add_parser("devices", help="list audio devices")
    sub.add_parser("test-wakeword", help="live microphone test: print wake-word scores, no network")
    sub.add_parser("download-models", help="download openWakeWord's built-in models")
    return p


async def run(cfg: dict) -> None:
    from .app import App
    from .audio import AudioIO
    from .session import LiveKitSession
    from .triggers import load_sources
    audio = AudioIO(cfg["audio"].get("input_device"), cfg["audio"].get("output_device"))
    audio.start()
    try:
        await App(cfg, audio, load_sources(cfg, None), lambda trig: LiveKitSession(cfg, audio, trig)).run()
    finally:
        audio.stop()


async def test_wakeword(cfg: dict) -> None:
    from .audio import AudioIO
    from .wakeword import WakeWordSource
    audio = AudioIO(cfg["audio"].get("input_device"))
    audio.start(speaker=False)
    src = WakeWordSource.from_config(cfg)
    best: dict[str, float] = {}
    orig = src.detector.predict

    def spy(chunk):
        scores = orig(chunk)
        for k, v in scores.items():
            best[k] = max(best.get(k, 0.0), v)
        line = "  ".join(f"{k}: {'#' * int(v * 20):<20} {v:.2f}" for k, v in scores.items())
        print("\r" + line, end="", flush=True)
        return scores

    src.detector.predict = spy
    print("Say a wake word. Ctrl+C to stop.")
    try:
        async for frame in audio.frames():
            src.on_audio(frame)
            for t in src.take():
                print(f"\n>>> WAKE WORD: {t.wake_word}")
    finally:
        audio.stop()
        print("\nhighest scores:", {k: round(v, 2) for k, v in best.items()})


def main(argv: list[str] | None = None) -> int:
    load_dotenv()
    args = build_parser().parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s: %(message)s")
    cmd = args.cmd or "run"
    try:
        if cmd == "devices":
            from .audio import list_devices
            print(list_devices())
            return 0
        if cmd == "download-models":
            from openwakeword.utils import download_models
            download_models()
            return 0
        cfg = load_config(args.config)
        asyncio.run(run(cfg) if cmd == "run" else test_wakeword(cfg))
    except ConfigError as e:
        print(f"config error: {e}", file=sys.stderr)
        return 2
    except KeyboardInterrupt:
        pass
    return 0


if __name__ == "__main__":
    sys.exit(main())
