import pathlib

import yaml


def _fix(o):
    """YAML 1.1 reads bare on/off/yes/no as booleans, including as mapping keys. Map them back."""
    if isinstance(o, dict):
        return {({True: "on", False: "off"}.get(k, k) if isinstance(k, bool) else k): _fix(v) for k, v in o.items()}
    if isinstance(o, list):
        return [_fix(v) for v in o]
    return o


def load_config(path: str | pathlib.Path = "config.yaml") -> dict:
    return _fix(yaml.safe_load(pathlib.Path(path).read_text(encoding="utf-8")))
