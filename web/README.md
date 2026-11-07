# web

The browser UI (one button, a transcript). It is served by the worker's token service (`python -m luna.web`,
`LUNA_WEB_DIR` points here) and installs on Android as a home-screen app. It stores the device access key in
the browser; a link or QR code from `luna-web add-device` sets it. To serve livekit-client locally instead of
from a CDN, run `web/vendor.sh` once.

## Widgets

`a2ui.js` / `a2ui.css` draw A2UI v0.9.1 surfaces that Luna sends on the `luna.ui` text stream (protocol:
[`../docs/protocol.md`](../docs/protocol.md#widgets-proposed)); taps and errors go back on `luna.ui.action`. The page announces
itself with the `luna.ui` attribute, so the worker only offers widget tools to it. Dependency-free; the basic catalog's
Text, Image, Icon, Row, Column, List, Card, Tabs, Divider, Modal, Button, CheckBox, TextField, DateTimeInput, ChoicePicker and Slider.
Not yet: List templates, function calls inside values, Video, AudioPlayer.

- Try it without LiveKit: open `demo.html` (it feeds sample messages to the renderer and shows what would be sent back).
- Tests of the protocol logic: `node --test web/test`. The drawing was checked once in headless Edge (13 checks: rendering,
  actions, input binding, URL safety, tabs, delete); that harness is not kept in the repo. Not yet seen on a phone.
