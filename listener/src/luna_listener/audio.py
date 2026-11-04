"""Microphone and speaker through sounddevice. Everything is 16 kHz mono int16 in 10 ms frames (160 samples):
that is what the wake-word model and LiveKit's echo canceller both want."""
import asyncio
import collections
import threading
import time

import numpy as np

RATE = 16000
FRAME = 160   # 10 ms
PREBUFFER = 2400        # 150 ms: playback waits for this much audio, so network/CPU hiccups do not chop the words up
FLUSH_AFTER = 0.12      # ...unless no more audio arrives for this long (the end of a sentence): then play what is there


class AudioIO:
    def __init__(self, input_device=None, output_device=None):
        self.input_device, self.output_device = input_device, output_device
        self._loop: asyncio.AbstractEventLoop | None = None
        self._mic: asyncio.Queue | None = None
        self._play = collections.deque()
        self._lock = threading.Lock()
        self._streams = []
        self._buffered = 0
        self._primed = False
        self._last_play = 0.0

    # ---- microphone ------------------------------------------------------------------------
    def _on_mic(self, indata, frames, time_info, status):
        frame = indata[:, 0].copy()
        self._loop.call_soon_threadsafe(self._put, frame)

    def _put(self, frame):
        if self._mic.full():
            self._mic.get_nowait()          # drop the oldest rather than let latency build up
        self._mic.put_nowait(frame)

    # ---- speaker ---------------------------------------------------------------------------
    def _on_speaker(self, outdata, frames, time_info, status):
        need, out = frames, []
        with self._lock:
            if not self._primed and self._buffered and (
                    self._buffered >= PREBUFFER or time.monotonic() - self._last_play > FLUSH_AFTER):
                self._primed = True                    # enough in hand (or the sentence is over): start playing
            while self._primed and need and self._play:
                chunk = self._play[0]
                if len(chunk) <= need:
                    out.append(chunk)
                    need -= len(chunk)
                    self._buffered -= len(chunk)
                    self._play.popleft()
                else:
                    out.append(chunk[:need])
                    self._play[0] = chunk[need:]
                    self._buffered -= need
                    need = 0
            if self._primed and need:
                self._primed = False                   # ran dry: wait for a cushion again instead of stuttering
        data = np.concatenate(out) if out else np.zeros(0, dtype=np.int16)
        outdata[:, 0] = np.concatenate([data, np.zeros(frames - len(data), dtype=np.int16)])

    def play(self, samples: np.ndarray) -> None:
        with self._lock:
            self._play.append(samples.astype(np.int16, copy=False))
            self._buffered += len(samples)
            self._last_play = time.monotonic()

    def stop_playback(self) -> None:
        with self._lock:
            self._play.clear()
            self._buffered = 0
            self._primed = False

    # ---- lifecycle -------------------------------------------------------------------------
    def start(self, speaker: bool = True) -> None:
        import sounddevice as sd
        self._loop = asyncio.get_running_loop()
        self._mic = asyncio.Queue(maxsize=200)
        self._streams = [sd.InputStream(samplerate=RATE, channels=1, dtype="int16", blocksize=FRAME,
                                        device=self.input_device, callback=self._on_mic)]
        if speaker:
            self._streams.append(sd.OutputStream(samplerate=RATE, channels=1, dtype="int16", blocksize=FRAME * 2,
                                                 latency="high", device=self.output_device,
                                                 callback=self._on_speaker))
        for s in self._streams:
            s.start()

    def stop(self) -> None:
        for s in self._streams:
            s.stop()
            s.close()
        self._streams = []

    async def frames(self):
        while True:
            yield await self._mic.get()


def list_devices() -> str:
    import sounddevice as sd
    return str(sd.query_devices())
