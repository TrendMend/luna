"""What Luna says first, decided by how the conversation was started.

Devices tell the agent over LiveKit, as participant attributes set through the token endpoint:
  luna.wake_word  id of the wake word that was heard (matches a key under `wake_words:` in config.yaml)
  luna.say        a phrase to open with (a "call" from Home Assistant, for example)
  luna.device     which device this is (set by the server, not the client)
Anything unknown is ignored, so a device cannot make the agent do more than the config allows."""
import re
from dataclasses import dataclass

ATTR_WAKE, ATTR_SAY, ATTR_DEVICE, ATTR_TRIGGER = "luna.wake_word", "luna.say", "luna.device", "luna.trigger"
ID = re.compile(r"[a-z0-9_]{1,40}")
MAX_SAY = 300
DEFAULT_REPLY = "Greet the user briefly and ask how you can help."


@dataclass
class Opening:
    say: str | None        # spoken word for word
    reply: str | None      # otherwise: instruction for the model's first reply
    extra: str             # added to the system prompt for this conversation
    wake_word: str | None


def clean_say(text) -> str | None:
    """Printable characters only, trimmed and length-limited."""
    if not isinstance(text, str):
        return None
    return "".join(c for c in text if c.isprintable())[:MAX_SAY].strip() or None


def plan_opening(cfg: dict, attrs: dict) -> Opening:
    wake = attrs.get(ATTR_WAKE)
    wake = wake if isinstance(wake, str) and ID.fullmatch(wake) else None
    entry = ((cfg.get("wake_words") or {}).get(wake) or {}) if wake else {}
    say = clean_say(attrs.get(ATTR_SAY)) or clean_say(entry.get("say"))
    reply = None if say else (entry.get("reply") or DEFAULT_REPLY)
    return Opening(say, reply, (entry.get("instructions") or "").strip(), wake)
