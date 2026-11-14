#!/usr/bin/env python3
"""Download openWakeWord's pre-trained models into the Android app's assets.

    python scripts/fetch_wakeword_models.py

These six files are not stored in this repository: they are licensed CC BY-NC-SA 4.0 (non-commercial), which is not the
license of this project's code (see THIRD_PARTY.md). Run this once before building the Android app or its unit tests.
The custom `ron.onnx` and `rulon.onnx` are part of this repository and are not downloaded.
The NUC/PC listener does not need this script: the `openwakeword` Python package brings the same models with it.

Each file is checked against a pinned SHA-256; a mismatch is an error and the file is not kept.
"""
import hashlib
import pathlib
import sys
import urllib.request

RELEASE = "https://github.com/dscripka/openWakeWord/releases/download/v0.5.1"
DEST = pathlib.Path(__file__).resolve().parents[1] / "android/app/src/main/assets/wakeword"

# local file name -> (name in the release, sha256)
MODELS = {
    "melspectrogram.onnx": ("melspectrogram.onnx", "ba2b0e0f8b7b875369a2c89cb13360ff53bac436f2895cced9f479fa65eb176f"),
    "embedding_model.onnx": ("embedding_model.onnx", "70d164290c1d095d1d4ee149bc5e00543250a7316b59f31d056cff7bd3075c1f"),
    "alexa.onnx": ("alexa_v0.1.onnx", "6ff566a01d12670e8d9e3c59da32651db1575d17272a601b7f8a39283dfbae3e"),
    "hey_jarvis.onnx": ("hey_jarvis_v0.1.onnx", "94a13cfe60075b132f6a472e7e462e8123ee70861bc3fb58434a73712ee0d2cb"),
    "hey_mycroft.onnx": ("hey_mycroft_v0.1.onnx", "c2a311e8fa1338de89c31b3b46dc4dffd4af2f9a8d6ddead48893c2d301b1f18"),
    "hey_rhasspy.onnx": ("hey_rhasspy_v0.1.onnx", "5a9b3ed3be2910e35780e097905aa9f35a9c10038df47914cf2b3ec4d670f6ea"),
}


def main() -> int:
    DEST.mkdir(parents=True, exist_ok=True)
    failed = False
    for local, (remote, sha) in MODELS.items():
        path = DEST / local
        if path.exists() and hashlib.sha256(path.read_bytes()).hexdigest() == sha:
            print(f"ok       {local}")
            continue
        try:
            with urllib.request.urlopen(f"{RELEASE}/{remote}", timeout=60) as r:
                data = r.read()
        except OSError as e:
            print(f"FAILED   {local}: {e}")
            failed = True
            continue
        if hashlib.sha256(data).hexdigest() != sha:
            print(f"FAILED   {local}: checksum does not match, not saved")
            failed = True
            continue
        path.write_bytes(data)
        print(f"fetched  {local}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
