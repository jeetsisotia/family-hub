# Production readiness (28 Nov 2026)

| # | Criterion | Status |
|---|---|---|
| 1 | Play Protect warning investigated | Likely cause READ_SMS (Google's guidance); removed; **real-phone install not yet verified** |
| 2 | Unnecessary sensitive permissions removed | READ_SMS removed; CI fails on SMS-reading, notification-listener or accessibility permissions |
| 3 | Banking and safety still usable | Bank SMS by Share/Paste + Gmail + statements; safety SMS unchanged |
| 4 | Signing identity unchanged | CI compares the certificate with 2.1.116 and fails on a mismatch |
| 5 | Login unchanged | No change to sign-in code |
| 6 | Family data preserved | No data migrations; installs as an update |
| 7 | Note 8 | Android 9 emulator checked in CI; **real Note 8 unverified** |
| 8 | Firebase rules | Reviewed and tested; **waiting for your approval to publish** |
| 9 | Attachments and memory photos | Rules-protected; orphan cleanup not built (documented) |
| 10 | Backup/restore | Restore re-reads the server ("Verified on the server"); real-phone run pending |
| 11 | Chat and calls | Emulator and unit checks pass; real calls pending (free STUN only) |
| 12 | AI and Gmail | Per-person Gemini key in Android Keystore, none in the APK; AI actions need confirmation and respect section access; AI Logic stays off |
| 13 | Emulator failures | All three investigated (see CONTINUATION-REPORT) |
| 14 | No paid services | Spark plan only; PR #2 (Cloud Functions, Blaze) not merged |
| 15 | Verified APK + checksum | In the Build APK log and release notes |

Needs your approval: publish Firestore rules; any change to AI (AI Logic stays off; PR #2 stays unmerged).
