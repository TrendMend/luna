# Development

How to run the tests and checks. CI runs all of them and they must pass: `.github/workflows/ci.yml` (tests, lint, a Docker build and smoke test, a secret scan, an SBOM and a vulnerability scan;
the scans also run weekly), `release-please.yml` and `release.yml` (the release pull request and the Android release build, see `releasing.md`), and `codeql.yml` (CodeQL for Python, JavaScript and Kotlin; GitHub's default setup must stay off, it conflicts with this workflow). [`AGENTS.md`](../AGENTS.md) has the repository rules for contributors and coding agents.

None of the tests needs a network, a device or an API key: they use fakes for Home Assistant, weather, TV, LiveKit delivery and the model providers.

```sh
# worker: the extras are required, config.yaml names those providers and the tests import them
cd worker   && uv run --extra openai --extra deepgram --extra cartesia --extra google --group dev pytest -q
cd listener && uv run --group dev pytest -q          # add --extra wakeword to run the 3 tests that need openwakeword
node --test "web/test/*.test.js"
uvx ruff check .                                     # from the repository root: syntax errors and pyflakes problems
python scripts/fetch_wakeword_models.py              # once, before any Android build or test (see below)
cd android  && ./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug     # JDK 17 and the Android SDK
```

Last run on 2026-11-13 from a fresh clone: worker 142 tests, listener 21 (24 with the `wakeword` extra), web 6, Android 76 unit tests;
ruff and Android lint clean.

## Android

- The app needs openWakeWord's pre-trained models for its wake words. They are CC BY-NC-SA 4.0 (non-commercial), so they are not stored in
  git: `scripts/fetch_wakeword_models.py` downloads them, checked against pinned SHA-256 values, into `android/app/src/main/assets/wakeword/`
  (git-ignored). `ron.onnx` and `rulon.onnx` are the project's own and are in the repository. See [`THIRD_PARTY.md`](../THIRD_PARTY.md).
- `minSdk` is 26: guard newer APIs with `Build.VERSION.SDK_INT`; Android lint fails the build on a missing guard.
- UI previews and screenshot tests are in `android/app/src/screenshotTest/`. After a UI change run `./gradlew :app:updateDebugScreenshotTest`,
  look at the images, and commit the updated references.
- More: [`android/README.md`](../android/README.md).

## Web

`web/` has no build step. `node --test "web/test/*.test.js"` (Node 21 or newer) tests the protocol logic. `web/demo.html` renders sample widgets without a server
(`demo.html#confirm`, `#controls`, `#tabs` or `#referral` opens one directly).

## Releasing

[`releasing.md`](releasing.md).
