# Installing and setting up Family Hub

## 1. Get the APK

Every change to `main` builds a new APK on GitHub.

1. Open **Actions → Build APK**: https://github.com/jeetsisotia/family-hub/actions/workflows/build-apk.yml
2. Open the newest run that has a green tick.
3. Under **Artifacts**, download **family-hub-apk**. It is a zip file; inside it is `app-debug.apk`.

You need to be signed in to GitHub to download artifacts. GitHub keeps them for 90 days. Every build is signed with the same key, so a newer APK installs over an older one and keeps everything on the phone.

## 2. Install on a phone

1. Copy `app-debug.apk` to the phone and open it.
2. If Android asks, allow installs from that app (for example Files or Chrome), then tap **Install**.
3. If Play Protect warns about an unknown app, choose **Install anyway**. It warns because the app is not from the Play Store.

You need Android 8.0 or newer.

## 3. First start

1. Tap **Continue with Google** and pick your Google account. There is no separate password or PIN.
2. You can turn on the fingerprint lock later, in **Family → Settings → My Profile**.
3. **To start a family:** type a family name and tap **Create Family Group**. You become its **Super Admin**.
4. **To join a family:** type the invite code (for example `FH-1A2B3C`), or tap **Scan the family's QR code** and scan the QR shown on the admin's phone. Then wait for an admin to approve you.
5. To show your family's code or QR, go to **Family**, then the invite card, then **Copy**, **Share** or **QR**.

## 4. Permissions (all optional)

The app asks for each permission only when you use the feature that needs it.

| Permission | What it is for | If you say no |
|---|---|---|
| Notifications | Reminders for bills, EMIs, card dues, medicines and events | You get no reminders. Everything still shows in the app. |
| SMS | Reads bank and card alerts on this phone to suggest entries. Nothing is added until you confirm it. | Add entries by hand, or import statements and Gmail alerts |
| Gmail (read-only) | Finds bank alert emails and statements. Only the subject and short preview are read. | Same as above |
| Health Connect | Steps, distance, calories, exercise, sleep, heart rate and weight from Google Fit, Samsung Health or a watch | Health shows only what you log yourself |
| Physical activity | Counts steps with the phone's own sensor | No step count from this phone |
| Calendar | Finds bills and renewals in your calendar during a scan | Calendar is skipped |
| Location | Only when you share your location from the SOS screen | The SOS message is sent without a location |
| Phone | Only when the SOS button calls your emergency contact after its countdown | SOS opens the dialer instead |

If Android greys out the SMS switch, open **Settings → Apps → Family Hub → ⋮ → Allow restricted settings** and try again. Android does this for some apps installed from a file.

## 5. For the developer: building and changing the app

The app is Kotlin with Jetpack Compose, Hilt and Firebase (Auth and Firestore, on the free Spark plan).

- **Firebase project:** `family-hub-1465f`.
- **Builds:** they run only on GitHub Actions. `.github/workflows/build-apk.yml` builds the debug APK.
- **Emulator check:** after each build, the same workflow installs the APK on an emulator. It opens the main screens with sample data, in light and dark, and saves screenshots named after each screen to the `demo-screenshots` branch.
- **Unit tests:** `.github/workflows/unit-tests.yml` runs every plain-Kotlin test suite in `tools/` on each push to `main`.
- **Uploading changes from a browser:** upload a file named `fhbundle-<name>.tgz` to the repository root. `.github/workflows/apply-bundle.yml` unpacks it, commits the change and starts a build.

### GitHub secrets the build needs

| Secret | Used for |
|---|---|
| `DEBUG_KEYSTORE_BASE64` | Signing every build with the same key. That key's SHA-1 is registered in Firebase, so Google sign-in works. |
| `GOOGLE_WEB_CLIENT_ID` | Google sign-in (the Web client ID from Firebase Authentication) |

Never commit a keystore, `google-services.json` from another project, or any key. The repository is public.

### Firestore security rules

- The live rules are kept in `firebase/firestore.rules`.
- The rules that were live before v2 are saved in `firebase/firestore.rules.backup-2026-11-26`.
- To change them: Firebase console → Firestore → Rules. Paste, publish, then test in the Rules Playground.
- `firebase/README.md` lists what the rules allow.

### Plain-Kotlin tests

`tools/` has tests for the parts of the app that do not need Android:

- the money engine
- the SMS and email reader
- the statement import
- the assistant's question matching

Each folder's README shows how to run them with `kotlinc`.

### Rolling back

`backup/before-v2-2026-11-26` is the code exactly as it was before v2. Any earlier APK can also be downloaded again from its Actions run while it is kept (90 days).
