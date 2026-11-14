# TODO

Ordered roughly by what blocks what. "Unverified" means written and built or unit-tested, but never run for real.

## First real runs
Everything below was written and unit-tested but has not been run for real, or the project's notes do not record that it was.
- [ ] Run the full system on a home network: LiveKit server, token service, worker, and a phone or tablet that connects with its own
  access key (see `docs/deployment.md`). Plain http with key-only auth is meant for a home LAN only.
- [ ] First recorded run of the Android app on a device: starts, asks for the microphone, connects, plays the agent's audio, shows the transcript.
- [ ] A real Home Assistant action with `LUNA_DRY_RUN=0` (everything so far was dry-run).
- [ ] Choose a web-search provider and set its key (`web_search` in `worker/config.yaml`).

## UI (recommended after the UI review)
- [ ] **Android design pass.** A theme exists (`ui/theme/`: colour, type, shape) and the screens have previews. Still to check or do:
  dynamic colour, edge-to-edge, large-text defaults and system font scale, touch targets of at least 56dp, content descriptions, and
  Settings as a proper screen rather than a dialog. None of it has been seen on a device.
- [x] Compose previews and screenshot tests exist (`android/app/src/screenshotTest/`); keep reviewing the renders when the UI changes.
- [ ] Web page: restyle `web/index.html` and `web/a2ui.css` with the same tokens as Android so the two match; check it on a phone-sized viewport.
- [ ] Decide whether the widgets look right: the Android M3 catalog and the web renderer draw the same messages differently.

## Android: first run
- [ ] Starts, asks for the microphone, connects to a room, plays the agent's audio, shows the transcript.
- [ ] Worker's widget JSON actually parses with the Android A2UI parser and draws (only the web renderer has seen it).
- [ ] Control-room service stays up (battery restrictions may kill it); full-screen call notification opens over the lock screen.
- [x] Boot receiver (`BootReceiver.kt`) is written; restarting after a real reboot has not been checked.
- [ ] Keep the access key in the Android Keystore instead of plain private storage.
- [ ] `https://` (Tailscale or a certificate) before leaving the home LAN; `usesCleartextTraffic` is on today.
- [x] On-device wake words are written and tested against the Python original; running on a real device is unverified.

## Worker
- [ ] Run timers/reminders against a real LiveKit server: `luna.call` delivery into the control room is only unit-tested with a fake.
- [ ] `web_search` `native` provider needs `llm.plugin: openai.responses`; untested. Pick a search provider and set its key.
- [ ] Run the NixOS module (`LUNA_WEB_URL`) on a real machine.
- [ ] Compose and Kubernetes changes (shared `luna-data` volume, `LUNA_WEB_URL`) were validated as YAML only.

## Gemini-like capabilities still missing
- [ ] Calendar and agenda, notes/lists, general "remind me every day" (repeating reminders).
- [ ] Referrals beyond what search finds: a curated list of local numbers and services in `config.yaml`.
- [ ] An emergency flow beyond "tell a caregiver" (who is called, in what order).

## Protocol
- [ ] A2UI `v1.0` is the current spec; Luna is pinned to v0.9.1 because the Android renderer is. Revisit when `androidx.a2ui` moves.
- [ ] Web renderer: List templates, function calls inside values, Video and AudioPlayer.
