"""Build STT/LLM/TTS objects from config.yaml by loading livekit.plugins.<plugin> dynamically."""
import importlib
import os


def preload(cfg: dict) -> None:
    """Import the configured plugins. LiveKit requires plugins to be imported on the MAIN thread, i.e.
    when the module loads, not later inside a session (that fails with "Plugins must be registered
    on the main thread" in console mode and in job threads)."""
    for kind in ("stt", "llm", "tts"):
        plugin = cfg[kind]["plugin"]
        try:
            importlib.import_module(f"livekit.plugins.{plugin}")
        except ImportError as e:
            raise SystemExit(f"plugin '{plugin}' not installed: run `uv sync --extra {plugin}` ({e})")


def build(kind: str, spec: dict):
    """kind: 'STT' | 'LLM' | 'TTS'. spec: {plugin: name, **constructor kwargs}."""
    spec = dict(spec)
    plugin = spec.pop("plugin")
    key_env = spec.pop("api_key_env", None)
    if key_env:
        spec["api_key"] = os.environ[key_env]
    try:
        mod = importlib.import_module(f"livekit.plugins.{plugin}")
    except ImportError as e:
        raise SystemExit(f"plugin '{plugin}' not installed: run `uv sync --extra {plugin}` ({e})")
    cls = getattr(mod, kind)
    # drop empty values so provider defaults apply
    return cls(**{k: v for k, v in spec.items() if v not in ("", None)})
