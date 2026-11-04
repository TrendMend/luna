"""Triggers: the things that can start a conversation.

A trigger source is any object with
    async def run(self, emit)         call `await emit(Trigger(...))` whenever something should start a conversation
and, if it needs the microphone (a wake-word detector does), also
    def on_audio(self, samples)       int16 mono frames, delivered ONLY while the device is idle
    def reset(self)                   forget buffered audio (called when a conversation ends)
Built-in sources are registered in load_sources(); installed packages can add more through the entry-point
group `luna_listener.triggers`, whose entries are factories `factory(settings: dict, context) -> source`."""
from dataclasses import dataclass
from importlib.metadata import entry_points


@dataclass(frozen=True)
class Trigger:
    kind: str                       # "wake_word", "call", "button", ...
    wake_word: str | None = None    # id from config (sent to the worker as luna.wake_word)
    say: str | None = None          # opening phrase (sent as luna.say)


def load_sources(cfg: dict, context) -> list:
    """Built-in sources plus any installed extensions that are enabled in config."""
    from .control import ControlRoomSource
    from .wakeword import WakeWordSource
    sources = []
    if cfg["wake_words"]:
        sources.append(WakeWordSource.from_config(cfg))
    if (cfg["triggers"].get("livekit_call") or {}).get("enabled", True):
        sources.append(ControlRoomSource(cfg, context))
    for ep in entry_points(group="luna_listener.triggers"):
        settings = cfg["triggers"].get(ep.name) or {}
        if settings.get("enabled", False):
            sources.append(ep.load()(settings, context))
    return sources
