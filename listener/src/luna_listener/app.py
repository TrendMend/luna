"""The listener's main loop: idle (listening for triggers) <-> in a conversation.

Privacy: while idle, microphone audio goes only to the local trigger sources (the wake-word detector). It
is sent to LiveKit only while a conversation is running."""
import asyncio
import logging
import time

from .triggers import Trigger

log = logging.getLogger("luna.app")


class App:
    def __init__(self, cfg: dict, audio, sources: list, session_factory, clock=time.monotonic):
        self.cfg, self.audio, self.sources, self.session_factory, self.clock = cfg, audio, sources, session_factory, clock
        self.session = None
        self._cooldown_until = 0.0
        self._triggers: asyncio.Queue = asyncio.Queue()

    async def emit(self, trigger: Trigger) -> None:
        await self._triggers.put(trigger)

    def route_mic(self, frame) -> None:
        """One microphone frame: to the conversation if there is one, otherwise to the local trigger sources."""
        if self.session is not None:
            self.session.push_mic(frame)
        elif self.clock() >= self._cooldown_until:
            for s in self.sources:
                if hasattr(s, "on_audio"):
                    s.on_audio(frame)

    async def handle(self, trigger: Trigger) -> str | None:
        """Start a conversation for this trigger if the device is free. Returns why it ended, or None if ignored."""
        if self.session is not None:
            log.info("ignoring %s trigger: already in a conversation", trigger.kind)
            return None
        if self.clock() < self._cooldown_until:
            log.info("ignoring %s trigger: cooling down", trigger.kind)
            return None
        self.session = self.session_factory(trigger)
        reason = "error"
        try:
            reason = await self.session.run()
        except Exception as e:                                    # token service down, LiveKit unreachable, ...
            log.error("conversation failed: %s", e)
            reason = f"error: {e}"
        finally:
            self.session = None
            self._cooldown_until = self.clock() + float(self.cfg["detection"].get("cooldown_seconds", 3))
            for s in self.sources:                                # forget audio from before/during the conversation
                if hasattr(s, "reset"):
                    s.reset()
        log.info("conversation ended: %s", reason)
        return reason

    async def run(self) -> None:
        tasks = [asyncio.create_task(s.run(self.emit)) for s in self.sources]

        async def mic():
            async for frame in self.audio.frames():
                self.route_mic(frame)

        tasks.append(asyncio.create_task(mic()))
        try:
            while True:
                await self.handle(await self._triggers.get())
        finally:
            for t in tasks:
                t.cancel()
