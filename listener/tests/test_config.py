import pytest

from luna_listener.config import ConfigError, load_config

BASE = """
device: kitchen
server: {token_url: http://x/api/token}
wake_words:
  ron:   {model: ron.onnx,   threshold: 0.5, enabled: true}
  rulon: {model: rulon.onnx, threshold: 0.5, enabled: true}
detection: {patience: 2, cooldown_seconds: 3}
"""


def write(tmp_path, name, text):
    p = tmp_path / name
    p.write_text(text)
    return p


def test_overrides_change_only_what_they_name(tmp_path):
    base = write(tmp_path, "config.yaml", BASE)
    over = write(tmp_path, "over.yaml", "wake_words: {ron: {threshold: 0.7}}\ndetection: {cooldown_seconds: 8}\n")
    cfg = load_config(base, over)
    assert cfg["wake_words"]["ron"]["threshold"] == 0.7
    assert cfg["wake_words"]["ron"]["model"].endswith("ron.onnx")       # untouched keys survive the merge
    assert cfg["wake_words"]["rulon"]["threshold"] == 0.5
    assert cfg["detection"] == {"patience": 2, "cooldown_seconds": 8}


def test_overrides_come_from_the_environment_and_json_works(tmp_path, monkeypatch):
    base = write(tmp_path, "config.yaml", BASE)
    over = write(tmp_path, "over.json", '{"wake_words": {"rulon": {"enabled": false}}}')
    monkeypatch.setenv("LUNA_LISTENER_OVERRIDES", str(over))
    assert list(load_config(base)["wake_words"]) == ["ron"]               # disabled words are dropped as usual


def test_without_overrides_nothing_changes(tmp_path, monkeypatch):
    monkeypatch.delenv("LUNA_LISTENER_OVERRIDES", raising=False)
    assert load_config(write(tmp_path, "config.yaml", BASE))["wake_words"]["ron"]["threshold"] == 0.5


def test_a_typo_is_an_error_not_a_silent_no_op(tmp_path):
    base = write(tmp_path, "config.yaml", BASE)
    with pytest.raises(ConfigError, match="not a setting"):
        load_config(base, write(tmp_path, "a.yaml", "wakewords: {ron: {threshold: 0.7}}\n"))
    with pytest.raises(ConfigError, match="needs a model"):               # a wake word that does not exist
        load_config(base, write(tmp_path, "b.yaml", "wake_words: {rulan: {threshold: 0.7}}\n"))


def test_overrides_are_still_validated(tmp_path):
    base = write(tmp_path, "config.yaml", BASE)
    with pytest.raises(ConfigError, match="threshold"):
        load_config(base, write(tmp_path, "c.yaml", "wake_words: {ron: {threshold: 1.5}}\n"))
    with pytest.raises(ConfigError, match="mapping"):
        load_config(base, write(tmp_path, "d.yaml", "- not\n- a mapping\n"))
