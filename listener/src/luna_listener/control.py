"""The standing control room: Home Assistant makes this device call by sending a LiveKit data message.

Only the LiveKit server API (which needs the LiveKit secret) can send into this room: the device's control token
has no data-publish grant, so no other participant can spoof a call."""
import asyncio
import json
import logging

from livekit import rtc

from .tokens import TokenError, fetch_token
from .triggers import Trigger

log = logging.getLogger("luna.control")
CALL_TOPIC = "luna.call"
MAX_SAY = 300


def parse_call(topic: str | None, payload: bytes) -> Trigger | None:
    """A `luna.call` data message -> Trigger("call", say=...), or None if it is not a valid call."""
    if topic != CALL_TOPIC:
        return None
    try:
        data = json.loads(payload.decode("utf-8")) if payload else {}
    except (UnicodeDecodeError, ValueError):
        return None
    if not isinstance(data, dict):
        return None
    say = data.get("say")
    if isinstance(say, str):
        say = "".join(c for c in say if c.isprintable())[:MAX_SAY].strip() or None
    else:
        say = None
    return Trigger("call", say=say)


class ControlRoomSource:
    def __init__(self, cfg: dict, context=None):
        self.cfg = cfg

    async def run(self, emit) -> None:
        delay = 2.0
        while True:
            try:
                url, token = await fetch_token(self.cfg, purpose="control")
                room = rtc.Room()
                gone = asyncio.Event()
                loop = asyncio.get_running_loop()

                @room.on("data_received")
                def _data(packet: rtc.DataPacket):
                    trig = parse_call(packet.topic, packet.data)
                    if trig:
                        log.info("call received (say=%r)", trig.say)
                        loop.create_task(emit(trig))

                @room.on("disconnected")
                def _gone(*_):
                    loop.call_soon_threadsafe(gone.set)

                await room.connect(url, token)
                log.info("waiting for calls in the control room")
                delay = 2.0
                await gone.wait()
                log.warning("control room disconnected; reconnecting")
            except TokenError as e:
                log.error("control room: %s", e)
            except Exception as e:                                    # network down, server restarting, ...
                log.error("control room: %s", e)
            await asyncio.sleep(delay)
            delay = min(delay * 2, 30.0)
