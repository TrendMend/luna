"""One version for the whole repository: the VERSION file. The worker's pyproject.toml must equal it and the Android build reads it.
release-please changes both together (release-please-config.json), so a hand edit that makes them differ fails here."""
import json
import pathlib
import re

ROOT = pathlib.Path(__file__).resolve().parents[2]
VERSION = (ROOT / "VERSION").read_text().strip()


def test_version_file_is_a_plain_semantic_version():
    assert re.fullmatch(r"\d{1,3}\.\d{1,2}\.\d{1,2}", VERSION), VERSION        # what the Android build accepts too


def test_worker_matches_the_version_file():
    pyproject = (ROOT / "worker" / "pyproject.toml").read_text(encoding="utf-8")
    assert re.search(r'(?m)^version = "([^"]*)"', pyproject).group(1) == VERSION, "release-please changes the version in both places; do not edit one by hand"


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


def test_release_please_manages_the_same_files_the_build_reads():
    config = json.loads((ROOT / "release-please-config.json").read_text(encoding="utf-8"))["packages"]["."]
    assert config["release-type"] == "simple"                              # writes VERSION, which the Android build reads
    assert {"path": "worker/pyproject.toml", "type": "toml", "jsonpath": "$.project.version"} in config["extra-files"]
    manifest = json.loads((ROOT / ".release-please-manifest.json").read_text(encoding="utf-8"))
    assert manifest["."] == VERSION, "the manifest is the last released version; release-please updates it"


def version_code(version):
    major, minor, patch = (int(x) for x in version.split("."))
    return major * 10_000 + minor * 100 + patch


def test_the_android_version_code_only_goes_up():
    steps = ["0.2.0", "0.2.1", "0.3.0", "0.10.0", "1.0.0", "1.0.1", "1.99.99", "2.0.0"]
    codes = [version_code(v) for v in steps]
    assert codes == sorted(codes) and len(set(codes)) == len(codes)
    assert version_code("0.2.0") == 200 and version_code("1.4.2") == 10402
