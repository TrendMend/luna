# Scripts

Small helpers for working on the repository. Run them from the repository root.

| Script | What it does |
|---|---|
| [`bump_version.py`](bump_version.py) | Changes the version of everything at once: `python scripts/bump_version.py patch`, `minor`, `major`, or an exact `1.4.2`. It updates `VERSION` and `worker/pyproject.toml`, starts a section in `CHANGELOG.md`, and refuses a version that is not higher than the current one. It does not touch git. See [`../docs/releasing.md`](../docs/releasing.md). |
| [`fetch_wakeword_models.py`](fetch_wakeword_models.py) | Downloads openWakeWord's six pre-trained models into the Android app's assets, checking each against a pinned SHA-256. They are CC BY-NC-SA 4.0 and not stored in git. Run it once before building the Android app or its tests. See [`../THIRD_PARTY.md`](../THIRD_PARTY.md). |
