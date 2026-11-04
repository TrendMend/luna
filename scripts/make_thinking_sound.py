"""Generate a soft, slow 'thinking' pad (stdlib only): two low tones with a gentle swell. Loops cleanly."""
import math, struct, wave

sr, dur = 24000, 4.0
n = int(sr * dur)
samples = []
for i in range(n):
    t = i / sr
    tone = 0.5 * math.sin(2 * math.pi * 220 * t) + 0.35 * math.sin(2 * math.pi * 330 * t)
    swell = 0.55 + 0.45 * math.sin(2 * math.pi * 2 * t / dur - math.pi / 2) ** 2  # 2 swells per loop
    samples.append(tone * swell)
peak = max(abs(s) for s in samples)
pcm = b"".join(struct.pack("<h", int(s / peak * 0.35 * 32767)) for s in samples)
with wave.open("sounds/thinking.wav", "wb") as w:
    w.setnchannels(1); w.setsampwidth(2); w.setframerate(sr); w.writeframes(pcm)
print("wrote sounds/thinking.wav", dur, "s")
