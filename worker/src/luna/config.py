import os
import pathlib

import yaml


def _fix(o):
    """YAML 1.1 reads bare on/off/yes/no as booleans, including as mapping keys. Map them back."""
    if isinstance(o, dict):
        return {({True: "on", False: "off"}.get(k, k) if isinstance(k, bool) else k): _fix(v) for k, v in o.items()}
    if isinstance(o, list):
        return [_fix(v) for v in o]
    return o


def merge(base: dict, over: dict, _top: bool = True) -> dict:
    """`over` laid on `base`: mappings merge key by key, anything else (lists included) is replaced. A top-level key that
    `base` does not have is an error, so a typo in a deployment's overrides cannot silently do nothing."""
    out = dict(base)
    for key, value in over.items():
        if _top and key not in base:
            raise ValueError(f"overrides: '{key}' is not a setting in config.yaml")
        out[key] = merge(base[key], value, False) if isinstance(value, dict) and isinstance(base.get(key), dict) else value
    return out


def load_config(path: str | pathlib.Path = "config.yaml", overrides: str | pathlib.Path | None = None) -> dict:
    """config.yaml, with a deployment's own settings on top: `overrides` or $LUNA_CONFIG_OVERRIDES (YAML or JSON, only the
    values that differ from the defaults; the NixOS module writes it from `services.luna.settings`)."""
    cfg = _fix(yaml.safe_load(pathlib.Path(path).read_text(encoding="utf-8")))
    overrides = overrides or os.environ.get("LUNA_CONFIG_OVERRIDES")
    if overrides:
        extra = yaml.safe_load(pathlib.Path(overrides).read_text(encoding="utf-8")) or {}
        if not isinstance(extra, dict):
            raise ValueError("overrides must be a mapping of settings")
        cfg = merge(cfg, _fix(extra))
    return cfg
