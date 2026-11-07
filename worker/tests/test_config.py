import pytest

from luna.config import load_config, merge

BASE = """
turns: {min_interruption_words: 1, min_endpointing_delay: 0.8}
session: {idle_seconds: 30, vision: []}
caregivers: [alice, bob]
"""


def write(tmp_path, name, text):
    p = tmp_path / name
    p.write_text(text, encoding="utf-8")
    return p


def test_overrides_merge_mappings_and_replace_lists(tmp_path):
    base = write(tmp_path, "config.yaml", BASE)
    over = write(tmp_path, "over.yaml", "turns: {min_interruption_words: 3}\nsession: {vision: [camera]}\ncaregivers: [carol]\n")
    cfg = load_config(base, over)
    assert cfg["turns"] == {"min_interruption_words": 3, "min_endpointing_delay": 0.8}
    assert cfg["session"] == {"idle_seconds": 30, "vision": ["camera"]}
    assert cfg["caregivers"] == ["carol"]


def test_environment_variable_and_no_overrides(tmp_path, monkeypatch):
    base = write(tmp_path, "config.yaml", BASE)
    monkeypatch.delenv("LUNA_CONFIG_OVERRIDES", raising=False)
    assert load_config(base)["turns"]["min_interruption_words"] == 1
    monkeypatch.setenv("LUNA_CONFIG_OVERRIDES", str(write(tmp_path, "o.json", '{"turns": {"min_interruption_words": 2}}')))
    assert load_config(base)["turns"]["min_interruption_words"] == 2


def test_unknown_top_level_setting_is_an_error(tmp_path):
    base = write(tmp_path, "config.yaml", BASE)
    with pytest.raises(ValueError, match="not a setting"):
        load_config(base, write(tmp_path, "o.yaml", "turn: {min_interruption_words: 3}\n"))


def test_merge_does_not_change_its_inputs():
    base, over = {"a": {"b": 1}}, {"a": {"b": 2}}
    assert merge(base, over) == {"a": {"b": 2}} and base == {"a": {"b": 1}}


def test_provider_class_can_be_named(monkeypatch):
    """`class:` picks a non-default class from the plugin module (Gemini TTS is google.beta.GeminiTTS)."""
    import sys
    import types
    from luna.providers import build

    made = {}
    mod = types.ModuleType("livekit.plugins.fakeplug")
    mod.TTS = lambda **kw: made.update(default=kw)
    mod.GeminiTTS = lambda **kw: made.update(named=kw) or "named"
    monkeypatch.setitem(sys.modules, "livekit.plugins.fakeplug", mod)

    assert build("TTS", {"plugin": "fakeplug", "class": "GeminiTTS", "model": "m", "voice_name": "Kore", "empty": ""}) == "named"
    assert made == {"named": {"model": "m", "voice_name": "Kore"}}      # `class` is not passed on; empty values are dropped
    build("TTS", {"plugin": "fakeplug", "model": "m"})
    assert made["default"] == {"model": "m"}                              # no `class:` = the old behaviour
    with pytest.raises(SystemExit, match="no class 'Nope'"):
        build("TTS", {"plugin": "fakeplug", "class": "Nope"})
