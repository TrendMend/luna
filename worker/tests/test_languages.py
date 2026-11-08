"""The app offers languages (android Languages.kt, and the MDM form's arrays); the worker accepts the ones in config.yaml `languages:`.
These must be the same list, or the app would offer a language the worker silently ignores."""
import pathlib
import re

from luna.agent import CFG

ANDROID = pathlib.Path(__file__).resolve().parents[2] / "android" / "app" / "src" / "main"


def kotlin_languages() -> dict[str, str]:
    text = (ANDROID / "java" / "dev" / "luna" / "assistant" / "Languages.kt").read_text(encoding="utf-8")
    return dict(re.findall(r'Language\("([a-z]+)", "([^"]+)"\)', text))


def test_the_app_and_the_worker_offer_the_same_languages():
    assert kotlin_languages() == CFG["languages"]


def test_the_mdm_form_lists_the_same_languages():
    arrays = (ANDROID / "res" / "values" / "mdm_arrays.xml").read_text(encoding="utf-8")
    values = re.search(r'name="mdm_language_values">(.*?)</string-array>', arrays, re.S).group(1)
    labels = re.search(r'name="mdm_language_labels">(.*?)</string-array>', arrays, re.S).group(1)
    ids = re.findall(r"<item>([^<]+)</item>", values)
    names = re.findall(r"<item>([^<]+)</item>", labels)
    assert ids[0] == "user" and names[0] == "The person decides"               # the first answer leaves it to the person
    assert dict(zip(ids[1:], names[1:])) == CFG["languages"]


def test_the_default_language_is_none_and_the_default_is_valid_when_set():
    sess = CFG["session"]
    assert sess["language"] in ("", *CFG["languages"])
    assert isinstance(sess["language_only"], bool)
