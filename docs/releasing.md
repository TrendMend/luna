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
3. Run the tests (`docs/development.md`) and commit: `git commit -am "Release X.Y.Z"`.
4. Tag and push: `git tag -a vX.Y.Z -m "Luna X.Y.Z" && git push --follow-tags`.
5. The **Release** workflow (`.github/workflows/release.yml`) takes over. It checks that the tag matches `VERSION`, runs the Android unit tests, builds the
   release APK, and publishes a GitHub release with `luna-X.Y.Z.apk`, `SHA256SUMS.txt` and the notes from that `CHANGELOG.md` section.
6. Deploy as usual (rebuild the image or the NixOS flake); install the APK on the phones, or publish it to your MDM.

### Signing the APK

The release build is signed with a key from GitHub secrets, so no key is ever in the repository. Make one once and keep it safe (if you lose it you cannot
update installed apps):

```sh
keytool -genkeypair -v -keystore luna-release.jks -alias luna -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 luna-release.jks      # macOS: base64 -i luna-release.jks
```

Add these repository secrets (Settings, Secrets and variables, Actions): `ANDROID_KEYSTORE_BASE64` (that output), `ANDROID_KEYSTORE_PASSWORD`,
`ANDROID_KEY_ALIAS` (`luna`) and `ANDROID_KEY_PASSWORD`. To build a signed release on your own machine, export `LUNA_KEYSTORE_FILE`, `LUNA_KEYSTORE_PASSWORD`,
`LUNA_KEY_ALIAS` and `LUNA_KEY_PASSWORD` and run `./gradlew :app:assembleRelease` in `android/`.

Without those secrets the workflow still publishes, but the APK is signed with the debug key and is named `luna-X.Y.Z-debug-key.apk`; the release notes say so.
Such a build installs, but it is not one to hand to other people.

The APK bundles openWakeWord's non-commercial wake-word models (CC BY-NC-SA 4.0), so a published release redistributes them; see `THIRD_PARTY.md`.

Which part to bump: **patch** for a fix, **minor** for something new, **major** for a change people must act on (a new server setup, a
protocol change an older app cannot talk to). While the version starts with `0`, a minor may still change things.

## Checking what is installed

- The app: Settings, at the bottom (`Luna 0.2.0`); or `adb shell dumpsys package dev.luna.assistant | grep versionName`.
- The worker: `curl <token service>/healthz` returns `{"ok": true, "version": "0.2.0"}`.
