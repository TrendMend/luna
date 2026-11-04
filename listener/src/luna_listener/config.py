"""Load and validate the listener's config.yaml."""
import os
import pathlib
import re

import yaml

ID = re.compile(r"[a-z0-9_]{1,40}")


class ConfigError(Exception):
    pass


def aec_enabled(cfg: dict) -> bool:
    """Our own echo cancellation: $LUNA_ECHO_CANCELLATION (1/0) wins over `audio.echo_cancellation`. Turn it off when the
    operating system already cancels echo (e.g. a PipeWire echo-cancel device), so the audio is not processed twice."""
    env = os.environ.get("LUNA_ECHO_CANCELLATION")
    if env is not None and env.strip():
        return env.strip().lower() in ("1", "true", "yes", "on")
    return bool(cfg.get("audio", {}).get("echo_cancellation", True))


def load_config(path: str | pathlib.Path) -> dict:
    path = pathlib.Path(path)
    cfg = validate(yaml.safe_load(path.read_text()) or {})
    for w in cfg["wake_words"].values():          # your own model files are found relative to the config file
        m = pathlib.Path(str(w["model"]))
        if m.suffix and not m.is_absolute():
            w["model"] = str((path.resolve().parent / m))
    return cfg


def validate(cfg: dict) -> dict:
    dev = cfg.get("device", "")
    if not isinstance(dev, str) or not re.fullmatch(r"[A-Za-z0-9_-]{1,32}", dev):
        raise ConfigError("device: letters, digits, '-' and '_' only (max 32)")
    if not (cfg.get("server") or {}).get("token_url"):
        raise ConfigError("server.token_url is required")
    for wid, w in (cfg.get("wake_words") or {}).items():
        if not isinstance(wid, str) or not ID.fullmatch(wid):
            raise ConfigError(f"wake word id '{wid}': lowercase letters, digits and '_' only (max 40)")
        if not (w or {}).get("model"):
            raise ConfigError(f"wake word '{wid}' needs a model")
        if not 0 < float(w.get("threshold", 0.5)) <= 1:
            raise ConfigError(f"wake word '{wid}': threshold must be between 0 and 1")
    # Every entry is validated, but only the enabled ones are listened for.
    cfg["wake_words"] = {wid: w for wid, w in (cfg.get("wake_words") or {}).items() if w.get("enabled", True)}
    for section in ("audio", "detection", "session", "triggers", "server"):
        cfg[section] = cfg.get(section) or {}
    return cfg
