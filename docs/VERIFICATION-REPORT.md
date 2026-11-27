# Verification report: Brief 4 (27 Nov 2026)

Brief: "FamilyHub — Final development, user feedback & release prompt".
Starting point: `4b53592…`, build #71.

Labels: **PASS**, **FAIL**, **NOT TESTED**, **NEEDS MY PHONE**, **NEEDS MY INPUT**, **BLOCKED**.

Nothing below is marked PASS on code presence or a green build alone. Evidence is one of:
- your confirmation;
- an emulator screenshot that was opened and looked at;
- a unit test run;
- a live API run.

## 1. Your phone tests (confirmed by you)

| Test | Result | Evidence |
|---|---|---|
| New APK installs over the old app | **PASS** | Your confirmation, 27 Nov |
| Bulk category change and Undo | **PASS** | Your confirmation |
| Remove duplicate and Undo | **PASS** | Your confirmation |
| Home and More, light and dark | **PASS** | Your confirmation |
| Gemini key added in AI settings | **PASS** | Your confirmation |

## 2. Home Due Summary

| Check | Result | Evidence |
|---|---|---|
| One summary replaces "Due soon" and "To pay"; no repeated bill cards | **PASS** | `demo_home`, `demo_dark_home`: one card, each bill listed once |
| Shows due count, total and overdue count | **PASS** after fixes p52 + p54 | Build #72 showed "14 due ·" with no total (line wrap). After p52 the total showed but was cut ("₹37,4…") next to the badge. p54 shows it short ("₹37.4 k"); see section 7 |
| Expanded near the top | **PASS** | `demo_home`, `demo_dark_home` |
| Folds on scroll; overdue lines stay visible | **PASS** | `demo_home_scrolled`, `demo_dark_home_scrolled`: folded card with the 2 overdue items |
| Manual toggle | **NOT TESTED** on screen | Code path is the same chevron row. The emulator demo does not tap |
| Smooth, no flicker | **NEEDS MY PHONE** | Hysteresis is in code (folds after 120 px, opens under 24 px). Animation smoothness can't be judged from stills |
| Reminders and notifications unchanged | **PASS** (code) | No change to the reminder, notification or worker files in p48–p52 (diff checked) |
| Post-fix header with total | See section 7 | Screenshot from the p52/p53 build |

## 3. Visual pass, light and dark (emulator screenshots, build #72)

| Screen | Light | Dark |
|---|---|---|
| Home | PASS | PASS |
| More | PASS | PASS |
| Finance | PASS | PASS |
| Transactions | PASS | PASS |
| Categories | PASS | PASS |
| Duplicates | PASS | PASS |
| Add / edit transaction form | PASS (opaque sheet, chips readable) | PASS |
| Transaction detail sheet | PASS | PASS |
| Add/edit forms in other modules (Health, Documents, Reminders…) | **NOT TESTED** | **NOT TESTED** |

The forms in other modules use the same shared sheet and theme, but there are no demo routes for them yet, so no screenshot has been checked.

## 4. AI

These checks ran through the manual **AI live check** workflow using the repository's `GEMINI_API_KEY` secret. That is not the key on your phone. The key is never printed, committed or put in the APK.

| Check | Result | Evidence |
|---|---|---|
| Invalid key gives a clear error | **PASS** | Live check #1/#2: "API key not valid" |
| General question answered | **PASS** | Live check: answer from `gemini-flash-lite-latest` |
| Follow-up keeps context | **PASS** | Live check: 7×6 follow-up answered 42 |
| Web search with real sources | **FAIL** on the repo key | HTTP 429 (free quota) on both free models; 2.x models return 404 (retired). The app now says plainly that web search isn't available on this key's free quota (p51). No sources are ever invented |
| Web search on your key | **NEEDS MY PHONE** | Ask any "latest…" question with web search on, and check that it shows sources or the clear quota message |
| Agent actions (add record, change category, remove duplicate) with confirm and Undo | **NEEDS MY PHONE** | Code: permission re-check, confirmation, audit log, Undo. Local command tests: ai-test 28/28. Not run live against Gemini |
| No paid model switch | **PASS** (code) | Model list is free-tier only (`gemini-flash-lite-latest`, `gemini-flash-latest`) |
| Key storage | **PASS** (code) | Android Keystore (KeyVault). Excluded from backup and sync |

## 5. Automated tests

| Suite | Result |
|---|---|
| Unit tests workflow (8 suites) on every push | **PASS** (runs #11–#17) |
| ledger-test (138 checks) | **PASS** (local) |
| ai-test (28 checks) | **PASS** (local) |
| Firestore rules tests | **NOT RE-RUN**: no rule changes in this brief. The last run passed |
| Build APK + emulator smoke test | **PASS** #72. Later builds: see section 7 |

## 6. Data, security and compatibility

All PASS (code review of the diffs):
- No change to Firestore rules, the backup format, the package name, the signing setup or the Firebase project.
- No paid services and no keys in the repo or APK.

## 7. Release

See `CONTINUATION-REPORT.md` → State for the final commit, build number and artifact (`family-hub-apk`).
