# Google Play Protect: "App blocked to protect your device"

## What was seen
On a Samsung Galaxy Note 8, installing `FamilyHub-2.1.116.apk` downloaded from GitHub Releases showed:
"App blocked to protect your device. This app can request access to sensitive data. This can increase the risk of
identity theft or financial fraud."

## Cause (likely, from Google's own guidance — not yet confirmed on the phone)
Google's developer guidance for this exact message
(https://developers.google.com/android/play-protect/warning-dev-guidance) says Play Protect automatically blocks
installs from internet-sideloading sources (browsers, messaging apps, file managers) of apps that declare any of:
`RECEIVE_SMS`, `READ_SMS`, `BIND_NOTIFICATION_LISTENER_SERVICE`, `BIND_ACCESSIBILITY_SERVICE`, in select markets.

FamilyHub 2.1.116 declared **`READ_SMS`** (for "Scan SMS", which read bank alerts from the inbox) and was downloaded
from GitHub in a browser. It declared none of the other three. `SEND_SMS` (safety texts) is not on that list.

## Permission inventory (main manifest)
| Permission | 2.1.116 | 2.1.117+ | Used by |
|---|---|---|---|
| READ_SMS | yes | **removed** | Scan SMS (inbox), daily bank-SMS check |
| SEND_SMS | yes | yes (kept, your choice) | SOS, safe arrival, low-battery texts (only after you turn them on) |
| RECEIVE_SMS, notification listener, accessibility | no | no | — |
| READ_CALENDAR | yes | yes | Calendar scan on the import screen |
| CAMERA, RECORD_AUDIO, MODIFY_AUDIO_SETTINGS, BLUETOOTH_CONNECT | yes | yes | Calls, voice messages |
| ACCESS_FINE/COARSE_LOCATION, FOREGROUND_SERVICE_LOCATION | yes | yes | SOS location, live location sharing |
| Health Connect reads, ACTIVITY_RECOGNITION, FOREGROUND_SERVICE_HEALTH | yes | yes | Health / steps |
| POST_NOTIFICATIONS, USE_FULL_SCREEN_INTENT, USE_EXACT_ALARM, RECEIVE_BOOT_COMPLETED | yes | yes | Reminders, incoming calls |
| INTERNET, ACCESS_NETWORK_STATE | yes | yes | Firebase, sync notice |

The final (merged) APK manifest — including anything added by libraries — is now checked by CI in every build
("Permission and signature audit" step): the build **fails** if READ_SMS, RECEIVE_SMS, RECEIVE_MMS, RECEIVE_WAP_PUSH,
a notification listener or an accessibility service appears. The full list is printed in the build log.

## What replaced SMS reading
- **Share → Family Hub:** in the Messages app, select bank SMS and share them; Family Hub opens the import screen and
  finds the transactions, bills and accounts in that text. Nothing is added until you tick and tap Add.
- **Paste SMS** on the same screen.
- **Gmail** scan (unchanged), Calendar scan, statement import (CSV/Excel), manual entry.
- The same SMS shared twice is recognised (stable key per message text), so it is not offered twice.
- Not used: the SMS Retriever / User Consent APIs — they are for one-time verification codes, not reading old messages.

What you lose: automatic reading of the whole inbox, and the daily "new bank transactions" notification from SMS.

## Signing
The signing key is unchanged (fixed CI key). CI compares the new APK's certificate SHA-256 with the published
2.1.116 APK and fails if they differ, so the new version installs over the old one and keeps your data.

## Verification status
- CI: permission audit result and certificate comparison are in the Build APK log (see CONTINUATION-REPORT).
- **Not yet verified: installing on your Note 8 with Play Protect on.** Do not turn Play Protect off.
- If a clean APK is still blocked, use the "File a Play Protect appeal" button on the guidance page above, with the
  package name `com.familyhub.app`, the APK's SHA-256 and the reason (private family app, no SMS reading).
