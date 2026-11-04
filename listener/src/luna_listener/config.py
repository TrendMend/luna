"""Load and validate the listener's config.yaml."""
import pathlib
import re

import yaml

ID = re.compile(r"[a-z0-9_]{1,40}")


class ConfigError(Exception):
    pass


def load_config(path: str | pathlib.Path) -> dict:
    cfg = yaml.safe_load(pathlib.Path(path).read_text()) or {}
    return validate(cfg)


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
