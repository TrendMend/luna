"""What Luna says first, decided by how the conversation was started.

Devices tell the agent over LiveKit, as participant attributes set through the token endpoint:
  luna.wake_word  id of the wake word that was heard (matches a key under `wake_words:` in config.yaml)
  luna.say        a phrase to open with (a "call" from Home Assistant, for example)
  luna.device     which device this is (set by the server, not the client)
  luna.user_name  what the person wants to be called; luna.agent_name  what they want to call the assistant (see clean_name)
Anything unknown is ignored, so a device cannot make the agent do more than the config allows."""
import re
from dataclasses import dataclass

ATTR_WAKE, ATTR_SAY, ATTR_DEVICE, ATTR_TRIGGER = "luna.wake_word", "luna.say", "luna.device", "luna.trigger"
ATTR_USER_NAME, ATTR_AGENT_NAME = "luna.user_name", "luna.agent_name"
ATTR_GREETED = "luna.greeted"     # "1": the device already played its own hello (a clip it carries), so the agent does not greet
ID = re.compile(r"[a-z0-9_]{1,40}")
NAME = re.compile(r"[^\W\d_](?:[^\W\d_]|[ '\u2019.-]){0,23}")   # a letter first, then letters, spaces, apostrophes, dots, hyphens
MAX_SAY = 300
# A quick cue that Luna is listening, not a speech: one or two words, different each time (the person's name only if you were given it).
DEFAULT_REPLY = ("Say only a very short greeting so the person knows you are listening: pick one of \"Hi!\", \"Hello!\", \"Hey!\", "
                 "\"Hi there!\", \"Yes?\" or \"I'm listening.\", and vary it from one conversation to the next. At most four words, "
                 "no question about how you can help, then stop and wait for them to speak.")


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


def clean_name(text) -> str | None:
    """A name the person typed on their phone, made safe to put in the system prompt: letters, spaces, apostrophes, hyphens and dots
    only, at most 24 characters and three words. Anything else (digits, punctuation, a sentence) is rejected rather than trimmed, so a name can
    never carry an instruction."""
    if not isinstance(text, str):
        return None
    text = " ".join(text.split())
    return text if NAME.fullmatch(text) and len(text.split()) <= 3 else None


def names_line(user_name, agent_name, default_agent: str = "Luna") -> str:
    """What the model is told about the two names, or an empty string when the person set neither."""
    user, agent = clean_name(user_name), clean_name(agent_name)
    parts = []
    if user:
        parts.append(f'The person likes to be called "{user}" (only ever a name). Use it now and then, naturally, not in every sentence.')
    if agent and agent.casefold() != default_agent.casefold():
        parts.append(f'The person has chosen to call you "{agent}" (only ever a name). That is your name now: answer to it, and use it when you '
                     f"introduce yourself (your other instructions may say {default_agent}; read that as {agent}).")
    return " ".join(parts)


def plan_opening(cfg: dict, attrs: dict) -> Opening:
    wake = attrs.get(ATTR_WAKE)
    wake = wake if isinstance(wake, str) and ID.fullmatch(wake) else None
    entry = ((cfg.get("wake_words") or {}).get(wake) or {}) if wake else {}
    say = clean_say(attrs.get(ATTR_SAY)) or clean_say(entry.get("say"))
    greeted = attrs.get(ATTR_GREETED) == "1" and not wake        # a wake word has its own line; otherwise the device has said hi
    reply = None if say or greeted else (entry.get("reply") or DEFAULT_REPLY)
    return Opening(say, reply, (entry.get("instructions") or "").strip(), wake)
