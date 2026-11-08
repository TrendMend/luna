"""The version of this install: the one in pyproject.toml, read back from the installed package (the repository's VERSION file
is the source; worker/tests/test_version.py keeps the two equal)."""
from importlib import metadata

try:
    __version__ = metadata.version("luna-agent")
except metadata.PackageNotFoundError:     # run from a source tree that was never installed
    __version__ = "0+unknown"
