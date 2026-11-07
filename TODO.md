# TODO

Ordered roughly by what blocks what. "Unverified" means written and built or unit-tested, but never run for real.

## Now: get the phone talking to the NUC
- [ ] Re-lock the `luna` input in `nixos-nuc` (`docs/luna.md` step 2) so the NUC sees the new commits (pushed: `4773ac8`).
- [ ] `nixos-nuc/luna.nix`: `web.host = "0.0.0.0"` and `web.openFirewall = true` (port 8080) so a phone on the LAN can reach the token service. Plain http, key-only auth: home LAN only.
- [ ] `/var/lib/luna/env` on the NUC: `LIVEKIT_PUBLIC_URL=ws://<NUC LAN IP>:7880` (without it phones are told to connect to 127.0.0.1) and `BRAVE_API_KEY` (or change `web_search` in `worker/config.yaml`).
- [ ] Add the phone as a device: new key appended to `LUNA_ACCESS_KEYS` as `phone:<key>`, restart `luna-web`, paste `http://<NUC>:8080/#key=<key>` into the app's Settings.
- [ ] Deploy, then check `journalctl -u luna-web -f` and `-u luna-agent -f` while tapping Talk.
- [ ] First real run of the Android app on a phone (nothing in `android/` has ever run): see "Android: first run" below.

## UI (recommended after the UI review)
- [ ] **Android: a real Material 3 design pass.** Today it is stock M3 components with four hard-coded colours, no type scale, no shapes or spacing system, no dynamic colour, no edge-to-edge, and a layout (fixed 220dp button, fixed 160dp transcript box) never seen on a screen.
  - [ ] Proper `MaterialTheme`: colour roles (light and dark), typography scale, shapes; large-text defaults for an older user; respect system font scale.
  - [ ] Rebuild the screen on it: clear states (idle, connecting, listening, speaking, error), widgets as first-class surfaces, accessible touch targets (>= 56dp), content descriptions.
  - [ ] Settings as a proper screen, not a dialog.
- [ ] **See the UI before calling it done:** Compose `@Preview`s for every state plus screenshot tests (add a screenshot-test tool to the Gradle build), reviewed as images.
- [ ] Web page: restyle `web/index.html` and `web/a2ui.css` with the same tokens as Android so the two match; check it on a phone-sized viewport.
- [ ] Decide whether the widgets look right: the Android M3 catalog and the web renderer draw the same messages differently.

## Android: first run
- [ ] Starts, asks for the microphone, connects to a room, plays the agent's audio, shows the transcript.
- [ ] Worker's widget JSON actually parses with the Android A2UI parser and draws (only the web renderer has seen it).
- [ ] Control-room service stays up (battery restrictions may kill it); full-screen call notification opens over the lock screen.
- [ ] Boot receiver so the service restarts after a reboot.
- [ ] Keep the access key in the Android Keystore instead of plain private storage.
- [ ] `https://` (Tailscale or a certificate) before leaving the home LAN; `usesCleartextTraffic` is on today.
- [ ] On-device wake words (what the old HAWakeWord app did); `livekit-wakeword` is the likely route.

## Worker
- [ ] Run timers/reminders against a real LiveKit server: `luna.call` delivery into the control room is only unit-tested with a fake.
- [ ] `web_search` `native` provider needs `llm.plugin: openai.responses`; untested. Pick a search provider and set its key.
- [ ] Build the NixOS module change (`LUNA_WEB_URL`) on the NUC; the first rebuild is its first test.
- [ ] Compose and Kubernetes changes (shared `luna-data` volume, `LUNA_WEB_URL`) were validated as YAML only.

## Gemini-like capabilities still missing
- [ ] Calendar and agenda, notes/lists, general "remind me every day" (repeating reminders).
- [ ] Referrals beyond what search finds: a curated list of local numbers and services in `config.yaml`.
- [ ] An emergency flow beyond "tell a caregiver" (who is called, in what order).

## Protocol
- [ ] A2UI `v1.0` is the current spec; Luna is pinned to v0.9.1 because the Android renderer is. Revisit when `androidx.a2ui` moves.
- [ ] Web renderer: List templates, function calls inside values, Video and AudioPlayer.
