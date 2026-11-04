"""One conversation: join a fresh LiveKit room, publish the microphone, play the agent, end when it leaves."""
import asyncio
import logging

import numpy as np
from livekit import rtc

from .audio import FRAME, RATE
from .config import aec_enabled
from .tokens import attributes_for, fetch_token

log = logging.getLogger("luna.session")


class LiveKitSession:
    def __init__(self, cfg: dict, audio, trigger):
        self.cfg, self.audio, self.trigger = cfg, audio, trigger
        self._mic: asyncio.Queue = asyncio.Queue(maxsize=100)     # ~1 s of audio while connecting

    def push_mic(self, samples: np.ndarray) -> None:
        if self._mic.full():
            self._mic.get_nowait()
        self._mic.put_nowait(samples)

    async def run(self) -> str:
        """Run the conversation; returns why it ended."""
        cfg, loop = self.cfg, asyncio.get_running_loop()
        sess = cfg["session"]
        ended: asyncio.Future = loop.create_future()

        def end(reason: str):
            if not ended.done():
                ended.set_result(reason)

        url, token = await fetch_token(cfg, attributes=attributes_for(self.trigger))
        aec = aec_enabled(cfg)
        apm = rtc.AudioProcessingModule(echo_cancellation=aec, high_pass_filter=True)
        if aec:
            apm.set_stream_delay_ms(int(cfg["audio"].get("echo_delay_ms", 80)))

        room = rtc.Room()
        agent_seen = asyncio.Event()
        tasks: list[asyncio.Task] = []

        def is_agent(p) -> bool:
            return p.kind == rtc.ParticipantKind.PARTICIPANT_KIND_AGENT

        async def play(track: rtc.Track):
            stream = rtc.AudioStream(track, sample_rate=RATE, num_channels=1, frame_size_ms=10)
            async for ev in stream:
                f = ev.frame
                self.audio.play(np.frombuffer(bytes(f.data), dtype=np.int16).copy())
                if aec and f.samples_per_channel == FRAME:
                    apm.process_reverse_stream(f)        # tell the echo canceller what the speaker is playing

        @room.on("track_subscribed")
        def _track(track, publication, participant):
            if track.kind == rtc.TrackKind.KIND_AUDIO and is_agent(participant):
                tasks.append(loop.create_task(play(track)))

        @room.on("participant_connected")
        def _joined(p):
            if is_agent(p):
                agent_seen.set()

        @room.on("participant_disconnected")
        def _left(p):
            if is_agent(p):
                end("agent_left")

        @room.on("disconnected")
        def _disc(*_):
            end("disconnected")

        source = rtc.AudioSource(RATE, 1)

        async def pump_mic():
            while True:
                s = await self._mic.get()
                if len(s) != FRAME:
                    continue
                frame = rtc.AudioFrame(s.tobytes(), RATE, 1, FRAME)
                apm.process_stream(frame)                # echo cancellation, in place
                await source.capture_frame(frame)

        async def watch_agent_join():
            try:
                await asyncio.wait_for(agent_seen.wait(), sess.get("agent_join_timeout", 20))
            except asyncio.TimeoutError:
                end("agent_never_joined")

        try:
            await room.connect(url, token)
            log.info("joined %s (%s)", room.name, self.trigger.kind)
            if any(is_agent(p) for p in room.remote_participants.values()):
                agent_seen.set()
            track = rtc.LocalAudioTrack.create_audio_track("mic", source)
            await room.local_participant.publish_track(
                track, rtc.TrackPublishOptions(source=rtc.TrackSource.SOURCE_MICROPHONE))
            tasks += [loop.create_task(pump_mic()), loop.create_task(watch_agent_join())]
            try:
                return await asyncio.wait_for(ended, sess.get("max_seconds", 900))
            except asyncio.TimeoutError:
                return "max_seconds"
        finally:
            for t in tasks:
                t.cancel()
            log.info("playback stats: %s", self.audio.take_stats())
            self.audio.stop_playback()
            await room.disconnect()
