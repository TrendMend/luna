# Makes android/app/src/test/resources/wakeword/golden.json: what the real openWakeWord scores for a clip, to check the Kotlin port against.
#   cd listener && uv run python tools/make_wakeword_golden.py ../android/app/src/test/resources/wakeword/speech.wav \
#       ../android/app/src/test/resources/wakeword/golden.json models
# (speech.wav: 16 kHz mono 16-bit; the checked-in one is Windows speech saying the wake words.)
"""Reference scores from the real openWakeWord, 1280-sample chunks exactly as the NUC listener feeds it."""
import json, pathlib, sys, wave
import numpy as np
import openwakeword
from openwakeword.model import Model

wav_path, out_path, models_dir = sys.argv[1], sys.argv[2], pathlib.Path(sys.argv[3])
res = pathlib.Path(openwakeword.__file__).parent / "resources" / "models"
files = {
    "alexa": res / "alexa_v0.1.onnx", "hey_jarvis": res / "hey_jarvis_v0.1.onnx",
    "hey_mycroft": res / "hey_mycroft_v0.1.onnx", "hey_rhasspy": res / "hey_rhasspy_v0.1.onnx",
    "ron": models_dir / "ron.onnx", "rulon": models_dir / "rulon.onnx",
}
stem_to_id = {p.stem: k for k, p in files.items()}
m = Model(wakeword_models=[str(p) for p in files.values()], inference_framework="onnx")
with wave.open(wav_path) as f:
    assert f.getframerate() == 16000 and f.getnchannels() == 1 and f.getsampwidth() == 2
    pcm = np.frombuffer(f.readframes(f.getnframes()), dtype=np.int16)
scores = {k: [] for k in files}
feats = []
for i in range(0, len(pcm) - 1279, 1280):
    out = m.predict(pcm[i:i + 1280])
    for stem, v in out.items():
        scores[stem_to_id[stem]].append(round(float(v), 6))
    feats.append([round(float(x), 5) for x in m.preprocessor.feature_buffer[-1]])
json.dump({"chunk": 1280, "warmup_ignore_frames": 24, "scores": scores, "last_embedding": feats}, open(out_path, "w"))
print("frames", len(feats), {k: round(max(v), 3) for k, v in scores.items()})
