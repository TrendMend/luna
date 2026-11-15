# Releasing

One version for the whole repository, in the `VERSION` file at the root (`MAJOR.MINOR.PATCH`).

| Where | How it follows `VERSION` |
|---|---|
| Android app | `android/app/build.gradle.kts` reads the file: `versionName` is the version, `versionCode` is `major*10000 + minor*100 + patch` (it only ever goes up, which an MDM or a store requires for an update). Settings shows `Luna <version>`. |
| Worker | `worker/pyproject.toml` has the same version (`worker/tests/test_version.py` fails if it differs). `luna.version.__version__` reads it back; `/healthz` and each `conversation_start` audit record report it. |
| History | `CHANGELOG.md`, and a git tag `vX.Y.Z` on the release commit. |

Nobody edits the version by hand. [release-please](https://github.com/googleapis/release-please) works it out from the commit messages and
changes `VERSION`, `worker/pyproject.toml`, `.release-please-manifest.json` and `CHANGELOG.md` together in one pull request
(`release-please-config.json`).

## Making a release

1. **Write commit messages in the [Conventional Commits](https://www.conventionalcommits.org/) form.** The type decides the bump:
   `feat:` a minor, `fix:` a patch, and `feat!:` (or a `BREAKING CHANGE:` line in the body) a major (while the version starts with 0,
   a breaking change is a minor). Use a scope for the part, as in `fix(android): wake words after a restart`. `docs:`, `ci:`, `test:`,
   `chore:` and `refactor:` never start a release on their own. A message that does not follow the form is ignored, so a user-visible
   change with one (`android: ...`) is left out of the changelog and does not count. The message body is the changelog text: say what
   a person would notice.
2. Merge to `main` as usual. The **Release Please** workflow (`.github/workflows/release-please.yml`) opens, and keeps up to date, a
   pull request titled `chore(main): release X.Y.Z` with the new version and the changelog section.
3. When you want to ship, check that pull request (edit its `CHANGELOG.md` section if the wording needs it) and merge it. That creates the
   `vX.Y.Z` tag and a GitHub release with the notes.
4. The same workflow then calls `release.yml`, which checks the tag against `VERSION`, runs the Android unit tests, builds the release APK, and
   attaches `luna-X.Y.Z.apk` and `SHA256SUMS.txt` to that release. To build the APK again for an existing tag, run **Release** from the
   Actions tab and give the tag.
5. Deploy as usual (rebuild the image or the NixOS flake); install the APK on the phones, or publish it to your MDM.

Repository settings this needs (Settings, Actions, General): "Read and write permissions" for workflows, and "Allow GitHub Actions to create
and approve pull requests". Pull requests and tags made with the default token do not start other workflows, so the checks in `ci.yml` do
not run on the release pull request itself (they run on every change merged before it). If you require checks to pass before merging, give
release-please a personal access token or a GitHub App token instead (the `token:` input of the action).

**The first release-please release.** `CHANGELOG.md` still has a hand-written `## [Unreleased]` block from before release-please. The first
release pull request adds its section above it. In that pull request, fold the block into the new section and delete the `## [Unreleased]`
heading; after that, the changelog is written only by release-please.

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

Which bump a change deserves: **patch** for a fix, **minor** for something new, **major** for a change people must act on (a new server
setup, a protocol change an older app cannot talk to). While the version starts with `0`, a minor may still change things. To force a
version, put `Release-As: 1.0.0` on its own line in a commit message body.

## Checking what is installed

- The app: Settings, at the bottom (`Luna 0.2.0`); or `adb shell dumpsys package dev.luna.assistant | grep versionName`.
- The worker: `curl <token service>/healthz` returns `{"ok": true, "version": "0.2.0"}`.
