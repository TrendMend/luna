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
    # `class` names the class to build when the plugin does not call it STT/LLM/TTS, e.g.
    # `plugin: google.beta` + `class: GeminiTTS` (Gemini speech models; google.TTS is Cloud Text-to-Speech).
    class_name = spec.pop("class", None) or kind
    key_env = spec.pop("api_key_env", None)
    if key_env:
        spec["api_key"] = os.environ[key_env]
    try:
        mod = importlib.import_module(f"livekit.plugins.{plugin}")
    except ImportError as e:
        raise SystemExit(f"plugin '{plugin}' not installed: run `uv sync --extra {plugin}` ({e})")
    try:
        cls = getattr(mod, class_name)
    except AttributeError:
        raise SystemExit(f"plugin '{plugin}' has no class '{class_name}' (set `class:` in the {kind.lower()} block)")
    # drop empty values so provider defaults apply
    return cls(**{k: v for k, v in spec.items() if v not in ("", None)})
