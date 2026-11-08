#!/usr/bin/env python3
"""Bump the version of everything in this repository at once.

    python scripts/bump_version.py patch      # 0.2.0 -> 0.2.1   (a fix)
    python scripts/bump_version.py minor      # 0.2.0 -> 0.3.0   (a feature)
    python scripts/bump_version.py major      # 0.2.0 -> 1.0.0   (a change that needs people to do something)
    python scripts/bump_version.py 1.4.2      # an exact version

It rewrites VERSION (the one source) and worker/pyproject.toml, and starts a section for the new version in CHANGELOG.md. It does not
touch git: the commit and the tag are yours (see docs/releasing.md). The Android app reads VERSION when it is built, so it follows.
"""
import datetime
import pathlib
import re
import sys

ROOT = pathlib.Path(__file__).resolve().parents[1]
SEMVER = re.compile(r"(\d{1,3})\.(\d{1,2})\.(\d{1,2})")


def parse(text: str) -> tuple[int, int, int]:
    m = SEMVER.fullmatch(text.strip())
    if not m:
        raise ValueError(f"not MAJOR.MINOR.PATCH: {text!r}")
    return int(m[1]), int(m[2]), int(m[3])


def bump(current: str, what: str) -> str:
    """The next version. `what` is major, minor, patch, or an exact version that must be higher than the current one."""
    major, minor, patch = parse(current)
    if what == "major":
        return f"{major + 1}.0.0"
    if what == "minor":
        return f"{major}.{minor + 1}.0"
    if what == "patch":
        return f"{major}.{minor}.{patch + 1}"
    if parse(what) <= (major, minor, patch):
        raise ValueError(f"{what} is not higher than {current}: an Android update with a lower version code is refused")
    return what


def version_code(version: str) -> int:
    """The Android versionCode (android/app/build.gradle.kts derives the same number): it only ever goes up with the version."""
    major, minor, patch = parse(version)
    return major * 10_000 + minor * 100 + patch


def changelog_with(text: str, version: str, today: str) -> str:
    """The changelog with an empty section for `version` just under the intro (above the newest release)."""
    header = f"## [{version}] - {today}\n\n### Added\n\n### Changed\n\n### Fixed\n\n"
    first = re.search(r"^## \[", text, re.MULTILINE)
    if first is None:
        return text.rstrip() + "\n\n" + header
    return text[:first.start()] + header + text[first.start():]


def main(argv: list[str]) -> int:
    if len(argv) != 2:
        print(__doc__)
        return 2
    version_file = ROOT / "VERSION"
    current = version_file.read_text().strip()
    try:
        new = bump(current, argv[1])
    except ValueError as e:
        print(f"error: {e}")
        return 1
    version_file.write_text(new + "\n")
    pyproject = ROOT / "worker" / "pyproject.toml"
    text = pyproject.read_text(encoding="utf-8")
    text, n = re.subn(r'(?m)^version = "[^"]*"', f'version = "{new}"', text, count=1)
    if n != 1:
        print("error: no version line in worker/pyproject.toml")
        return 1
    pyproject.write_text(text, encoding="utf-8", newline="\n")
    changelog = ROOT / "CHANGELOG.md"
    if changelog.exists():
        changelog.write_text(changelog_with(changelog.read_text(encoding="utf-8"), new, datetime.date.today().isoformat()),
                             encoding="utf-8", newline="\n")
    print(f"{current} -> {new}   (Android versionCode {version_code(new)})")
    print(f"next: fill in CHANGELOG.md, then  git commit -am 'Release {new}' && git tag -a v{new} -m 'Luna {new}' && git push --follow-tags")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
