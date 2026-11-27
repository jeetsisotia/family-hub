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

## 7. AI Finance action fix (phone test of 27 Nov, 11:09)

Your phone test:
- **PASS:** task creation (confirm card, saved, Undo).
- **FAIL:** changing a Finance category. The Gemini chat said it can't, and another screen couldn't find the test transaction.

Root causes and the fix are in `AI-SETUP.md` §6a.

Emulator evidence: Build APK #80 (`26d8046`, run https://github.com/jeetsisotia/family-hub/actions/runs/45949389483). It used a real local Firestore emulator as the database (no real data) and tapped the real buttons.

| Check | Result | Evidence |
|---|---|---|
| Confirmation card shows the real entry and "Dining → Shopping" before anything changes | **PASS** | `demo_ai_recat_1_confirm`, logged CHECK |
| Nothing written before Change | **PASS** | Change button still waiting; Finance row still Dining |
| After Change, the card says "Changed ✓" only after the save | **PASS** | `demo_ai_recat_2_changed` |
| Result read back from the database ("The server confirmed it") | **PASS** | same |
| Finance row (same component as Finance → Transactions) shows Shopping | **PASS** | same, live from the database |
| Undo restores Dining, confirmed by the database | **PASS** | `demo_ai_recat_3_undone` |
| Finance detail sheet shows the new category after an app restart | **PASS** | `demo_ai_recat_4_finance_details` |
| Several matches: each listed, person picks one | **PASS** (screenshot) | `demo_ai_recat_5_choose`. The logged check looked for text that had scrolled off screen, so it is fixed to count the Change buttons; see the next build |
| Database unreachable: never says Changed, shows "Not confirmed" | **PASS** | `demo_ai_recat_6_offline`, two logged CHECKs |
| No crash | **PASS** | RESULT: NO CRASH |

Automated tests:

| Suite | Result |
|---|---|
| ledger-test | **PASS** 157 checks (19 new: reading requests in English and Hinglish, matching, several matches, "last", unknown shop or category, "put milk in shopping" is not a category change, the write touches only category, subcategory and updatedAt) |
| Unit tests workflow | **PASS** (runs #22, #23) |
| Firestore rules tests | **PASS** (run #8; 3 new tests for this write). Rules unchanged |
| ai-test | **PASS** 28 |

Your phone:
- **NEEDS MY PHONE:** the same request on your own data in both AI screens:
  1. In **FamilyHub actions**, type "change the category of <shop> to Shopping", check the card, tap **Change**, check Finance, then tap **Undo**.
  2. In the **Gemini chat**, do the same.

## 8. Brief 5: Priority Zero and Phase 1 (27 Nov 2026, 12:38)

Statuses: **PASS** (tested, with evidence), **PARTIAL** (built, testing incomplete), **BLOCKED**, **NOT STARTED**, **NEEDS MY PHONE**.

The emulator evidence below comes from these runs:
- Build APK #83 (`b566838`): Priority Zero checks.
- Build APK #85 (`5a6f156`): Phase 1 screenshots.
- The final run after p60 is recorded in `CONTINUATION-REPORT.md`.

Real writes and Undo were tested against a local Firestore emulator (test data only).

### Priority Zero

| Item | Status | Evidence |
|---|---|---|
| A. "Change the category of Mukul to Shopping" (and 13 other English and Hinglish phrasings) | **PASS** | ledger-test 175 checks (14 Mukul phrasings, the AI's copied request words cleaned, closest matches when nothing matches exactly). Emulator #83: found the UPI payment to Mukul, Other → Shopping; changed only after confirming; read back from the database; Undo confirmed. 4/4 CHECKs |
| A. Several matches: pick one, never all | **PASS** after fix | "Do all" never offered. #83 showed the chat scrolled to the *last* card, hiding the question. Fixed in p60: it now scrolls to the question and the first card |
| B. AI screen: Google chip wrapping, spacing, widths and font sizes | **PARTIAL**, fix in p60 | #83: the chips no longer wrap at 1.3× text or 360dp, but the long "Web search (off in settings)" label pushed Google off screen (CHECK FAIL). p60 shortens the labels (Web search, Family data, Google); tapping Web search when it is off explains why |
| C. Due Summary: fold and open on tap | **PASS** | #83: both tap CHECKs pass; screenshots `demo_due_tapped_fold`, `demo_due_tapped_open` |
| D. Health (vitals, medicines), Documents and Reminders add forms, light and dark | **PASS** after one fix | #83: 8 screenshots checked; the Reminders "For (on a shared reminder…)" label wrapped into the field outline and is now "Whose phone rings" |
| E. Gemini errors: invalid key, key refused, API not enabled, rate limit (with Google's wait time), daily quota, web search quota, network | **PASS** (unit) | `GeminiErrors` + 11 tests (Unit tests run #27). No paid fallback: only free models are tried. Live wording on your key: **NEEDS MY PHONE** |
| E. General answers, follow-up, web search on your key; Gmail still working | **NEEDS MY PHONE** | General answers and follow-ups passed earlier on the repository key; web search was refused by that key's free quota. The Gmail code is unchanged |
| F. Regression (unit) | **PASS** | Unit tests #27–#29 (all suites, incl. ledger 175, ai 28, Gemini errors 11, Phase 1 30); rules tests #8; emulator "NO CRASH" |
| F. Notifications, backup and restore, sign-in and permissions | **NOT RETESTED** | No code change, except that document notifications now name only "A document". Backup, sign-in and rules are unchanged |

### Phase 1

| Feature | Status | Evidence |
|---|---|---|
| 1.1 Family activity timeline (More → Family activity) | **PASS** | phase1-test (timeline 12 checks: newest first; Only me, private and health never shown; documents name and type only; names from uid; completions; filters; future times never shown). Screenshots light and dark (#85) |
| 1.2 Document expiry tracker | **PASS** (app) | phase1-test (8 checks); Documents summary card (expired, due soon, active; next to renew) in light and dark (#85). **NEEDS MY PHONE:** a real expiry notification on your phone |
| 1.3 Recurring payment suggestions (Finance → Recurring payments) | **PARTIAL** until the final run | phase1-test (10 checks: weekly, monthly, yearly; irregular and deleted ignored; already tracked and dismissed not offered). Screenshots light and dark (#85): Milkman weekly suggested; Netflix not suggested because it is already a subscription. The Track → confirm → save check is in the final run |

Phase 2–4 and the premium features: **NOT STARTED**, as you asked.

## 9. Release

See `CONTINUATION-REPORT.md` → State for the final commit, build number and artifact (`family-hub-apk`).
