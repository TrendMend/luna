# Releasing

One version for the whole repository, in the `VERSION` file at the root (`MAJOR.MINOR.PATCH`).

| Where | How it follows `VERSION` |
|---|---|
| Android app | `android/app/build.gradle.kts` reads the file: `versionName` is the version, `versionCode` is `major*10000 + minor*100 + patch` (it only ever goes up, which an MDM or a store requires for an update). Settings shows `Luna <version>`. |
| Worker | `worker/pyproject.toml` has the same version (`worker/tests/test_version.py` fails if it differs). `luna.version.__version__` reads it back; `/healthz` and each `conversation_start` audit record report it. |
| History | `CHANGELOG.md`, and a git tag `vX.Y.Z` on the release commit. |

## Making a release

1. `python scripts/bump_version.py patch` (or `minor`, `major`, or an exact `1.4.2`). It updates `VERSION` and `worker/pyproject.toml` and
   starts a section in `CHANGELOG.md`; it refuses a version that is not higher than the current one.
2. Fill in that section of `CHANGELOG.md` (Added, Changed, Fixed; delete the empty headings).
3. Run the tests: `cd worker && uv run --extra openai --extra deepgram --extra cartesia --extra google pytest -q` and
   `cd android && ./gradlew :app:testDebugUnitTest :app:assembleDebug`.
4. `git commit -am "Release X.Y.Z" && git tag -a vX.Y.Z -m "Luna X.Y.Z" && git push --follow-tags`.
5. Deploy to the NUC as usual (`nix flake update luna`, rebuild); install the APK on the phones, or publish it to your MDM.

Which part to bump: **patch** for a fix, **minor** for something new, **major** for a change people must act on (a new server setup, a
protocol change an older app cannot talk to). While the version starts with `0`, a minor may still change things.

## Checking what is installed

- The app: Settings, at the bottom (`Luna 0.2.0`); or `adb shell dumpsys package dev.luna.assistant | grep versionName`.
- The worker: `curl <token service>/healthz` returns `{"ok": true, "version": "0.2.0"}`.
