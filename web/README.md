# Web

The browser UI for Luna: one big **Talk** button, the conversation, and any widgets Luna shows. It has no build step and no framework.

- **Look:** Material 3 with the same colour roles as the Android app. `a2ui.css` holds the tokens (light and dark by system setting, M3 shape and
  type scales, 56px touch targets).
- **Served by** the worker's token service (`python -m luna.web`; `LUNA_WEB_DIR` points at this folder). It installs on Android as a home-screen app.
- **Access key:** stored in the browser. A link or QR code from `luna-web add-device` sets it; otherwise the page asks for it in a dialog.
- **Microphone:** browsers allow it only on `https://` or `localhost`.
- **LiveKit client:** loaded from the jsDelivr CDN by default. Run `web/vendor.sh` once to serve it from this server instead (needed offline).

## Files

| File | What |
|---|---|
| `index.html` | The page: connects to LiveKit, shows status and the conversation. |
| `a2ui.js` | The widget renderer: draws A2UI v0.9.1 surfaces and sends taps back. Dependency-free. |
| `a2ui.css` | The Material 3 tokens, and the styles for the page and the widgets. |
| `demo.html` | Renders sample widgets without LiveKit and shows what would be sent back. |
| `vendor.sh` | Optional: downloads `livekit-client` into `web/vendor/`. |
| `test/` | Tests of the protocol logic. |

## Widgets

Luna sends widgets as A2UI messages on the `luna.ui` text stream; taps and errors go back on `luna.ui.action`
(see [`../docs/protocol.md`](../docs/protocol.md#widgets-proposed)). The page announces itself with the `luna.ui` attribute, so the worker only
offers widget tools to devices that can draw them.

Supported (the basic catalog): Text, Image, Icon, Row, Column, List, Card, Tabs, Divider, Modal, Button, CheckBox, TextField, DateTimeInput,
ChoicePicker and Slider. **Not yet:** List templates, function calls inside values, Video and AudioPlayer.

To see them without a server, open `demo.html`. `demo.html#confirm` (or `#controls`, `#tabs`, `#referral`) opens one directly.

## Tests

```sh
node --test web/test
```

These test the protocol logic (message rules, data model, pointer handling). The drawing is not covered by them.

## Status

- **Checked directly:** the renderer in headless Edge (13 checks: rendering, actions, input binding, URL safety, tabs, delete; that harness is not
  kept in the repository), and, after the Material 3 restyle on 2026-11-13, the home page and the widget demo in headless Edge in light and dark.
- **Not checked by me:** a real phone-sized screen, and the access-key dialog.
- **Reported:** the maintainer reports (2026-11-13) that the whole system works in production.
