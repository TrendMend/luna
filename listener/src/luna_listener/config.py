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


def merge(base: dict, over: dict, _top: bool = True) -> dict:
    """`over` laid on `base`: mappings merge key by key, anything else (lists included) is replaced. A top-level key that
    `base` does not have is an error, so a typo in a deployment's overrides cannot silently do nothing."""
    out = dict(base)
    for key, value in over.items():
        if _top and key not in base:
            raise ConfigError(f"overrides: '{key}' is not a setting in config.yaml")
        out[key] = merge(base[key], value, False) if isinstance(value, dict) and isinstance(base.get(key), dict) else value
    return out


def load_config(path: str | pathlib.Path, overrides: str | pathlib.Path | None = None) -> dict:
    """config.yaml, with a deployment's own settings on top: `overrides` or $LUNA_LISTENER_OVERRIDES (YAML or JSON, only the
    values that differ from the defaults; the NixOS module writes it from `services.luna-listener.settings`)."""
    path = pathlib.Path(path)
    raw = yaml.safe_load(path.read_text()) or {}
    overrides = overrides or os.environ.get("LUNA_LISTENER_OVERRIDES")
    if overrides:
        extra = yaml.safe_load(pathlib.Path(overrides).read_text()) or {}
        if not isinstance(extra, dict):
            raise ConfigError("overrides must be a mapping of settings")
        raw = merge(raw, extra)
    cfg = validate(raw)
    for w in cfg["wake_words"].values():          # your own model files are found relative to the config file
        m = pathlib.Path(str(w["model"]))
        if m.suffix and not m.is_absolute():
            w["model"] = str((path.resolve().parent / m))
    audio = cfg.setdefault("audio", {})           # $LUNA_INPUT_DEVICE / $LUNA_OUTPUT_DEVICE win over the file (e.g. set by a NixOS module)
    for key, var in (("input_device", "LUNA_INPUT_DEVICE"), ("output_device", "LUNA_OUTPUT_DEVICE")):
        if os.environ.get(var, "").strip():
            audio[key] = os.environ[var].strip()
    if os.environ.get("LUNA_OUTPUT_GAIN", "").strip():
        audio["output_gain"] = float(os.environ["LUNA_OUTPUT_GAIN"])
    if not 0 < float(audio.get("output_gain", 1.0)) <= 8:
        raise ConfigError("audio.output_gain must be above 0 and at most 8 (1.0 = as sent, 2.0 = twice the amplitude)")
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
