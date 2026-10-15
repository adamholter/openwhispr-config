# Left Option as the dictation key

OpenWhispr's own key listener only supports right-side modifiers. This adds Left Option:

- **tap** (press and release alone): start or stop dictation
- **hold** (longer than 0.35 s): push-to-talk, stops on release
- Option used with any other key, click or modifier does nothing, so Option shortcuts keep working.

`main.swift` is a tiny helper that watches the keyboard (listen-only) and prints `TAP`, `HOLD_START` and `HOLD_STOP`. `leftOptionHelper.js` is loaded from OpenWhispr's main process after its own key listener starts:

```js
require("./src/helpers/leftOptionHelper")(windowManager);
```

Because OpenWhispr launches the helper, macOS treats it as part of OpenWhispr and it uses OpenWhispr's Accessibility permission. No separate permission prompt.

```sh
swiftc -O main.swift -o ~/.local/bin/OWLeftOption
```
