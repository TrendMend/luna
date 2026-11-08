"""One version for the whole repository: the VERSION file. The worker's pyproject.toml must equal it, the Android build reads it,
and scripts/bump_version.py is how it changes."""
import importlib.util
import pathlib
import re

import pytest

ROOT = pathlib.Path(__file__).resolve().parents[2]
VERSION = (ROOT / "VERSION").read_text().strip()


def load_script():
    spec = importlib.util.spec_from_file_location("bump_version", ROOT / "scripts" / "bump_version.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_version_file_is_a_plain_semantic_version():
    assert re.fullmatch(r"\d{1,3}\.\d{1,2}\.\d{1,2}", VERSION), VERSION        # what the Android build accepts too


def test_worker_matches_the_version_file():
    pyproject = (ROOT / "worker" / "pyproject.toml").read_text(encoding="utf-8")
    assert re.search(r'(?m)^version = "([^"]*)"', pyproject).group(1) == VERSION, "run scripts/bump_version.py instead of editing by hand"


def test_the_running_worker_reports_it():
    from luna.version import __version__
    assert __version__ == VERSION


def test_health_reports_the_version(tmp_path):
    from luna.web import __version__
    assert __version__ == VERSION


def test_android_build_reads_the_version_file_and_derives_the_code():
    gradle = (ROOT / "android" / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    assert 'rootProject.file("../VERSION")' in gradle and "versionName = appVersion" in gradle
    assert "appVersionParts[0] * 10_000 + appVersionParts[1] * 100 + appVersionParts[2]" in gradle
    assert 'versionCode = 1\n' not in gradle


def test_bump():
    b = load_script()
    assert b.bump("0.2.0", "patch") == "0.2.1"
    assert b.bump("0.2.9", "minor") == "0.3.0"
    assert b.bump("0.9.9", "major") == "1.0.0"
    assert b.bump("0.2.0", "1.4.2") == "1.4.2"
    for bad in ("0.2.0", "0.1.9", "banana", "1.2", "1.2.3.4", "1.2.3-beta"):
        with pytest.raises(ValueError):
            b.bump("0.2.0", bad)


def test_the_android_version_code_only_goes_up():
    b = load_script()
    steps = ["0.2.0", "0.2.1", "0.3.0", "0.10.0", "1.0.0", "1.0.1", "1.99.99", "2.0.0"]
    codes = [b.version_code(v) for v in steps]
    assert codes == sorted(codes) and len(set(codes)) == len(codes)
    assert b.version_code("0.2.0") == 200 and b.version_code("1.4.2") == 10402
    assert b.version_code("0.2.0") > 1            # more than the 1 that was installed before versions were real


def test_changelog_gets_a_section_above_the_newest():
    b = load_script()
    text = "# Changelog\n\nintro\n\n## [0.2.0] - 2026-11-07\n\n### Added\n- x\n"
    out = b.changelog_with(text, "0.2.1", "2026-11-09")
    assert out.index("## [0.2.1] - 2026-11-09") < out.index("## [0.2.0]")
    assert out.startswith("# Changelog\n\nintro")
    assert b.changelog_with("# Changelog\n", "0.1.0", "2026-01-01").endswith("### Fixed\n\n")
