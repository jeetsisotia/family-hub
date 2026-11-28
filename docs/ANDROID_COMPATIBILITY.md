# Android compatibility (Samsung Galaxy Note 8 and newer phones)

- The Galaxy Note 8's last major update was **Android 9 Pie (API 28)**; it did not get Android 10.
- FamilyHub: `minSdk 26` (Android 8.0), `targetSdk 36`. So a Note 8 on Android 8 or 9 can install it. minSdk was not raised;
  targetSdk was not lowered.

| Area | Android 9 (Note 8) | Notes |
|---|---|---|
| Install, sign-in screen, main screens | Checked in CI on an **Android 9 emulator** (job `android9-check`) | Not the real phone |
| Notifications | No runtime permission before Android 13 | Works without asking |
| Health Connect | Needs the Health Connect app from the Play Store on Android 9–13 | Health still works with entries you log |
| Calls (WebRTC), camera, microphone | API 21+ | Free STUN only; some networks need TURN (not used) |
| Photo picker | Android's backported picker via Google Play services | Falls back to the system file picker |
| Background work, exact alarms | Android 9 rules | Samsung battery optimisation can delay reminders: set Family Hub to "Unrestricted" |
| Widgets | Supported | |

Real Note 8 checks are on `docs/PHONE_TEST_CHECKLIST.md` and are **not verified** until you run them.
