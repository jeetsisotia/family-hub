# Family Hub: report for brief 3 (27 Nov 2026)

Brief: "FamilyHub — Master development & release prompt" plus the "Final completeness requirement". Earlier phases are in `docs/FINAL-REPORT.md`. Details are in `PROGRESS.md` and `docs/CONTINUATION-REPORT.md`.

**Labels:**
- **PASS**: implemented and verified, with the evidence named
- **FAIL**: attempted but not working
- **NOT TESTED**
- **NEEDS MY PHONE**
- **NEEDS MY INPUT**
- **BLOCKED**
- **PARTIAL**: some of it done, the rest listed

## Where things stand

| | |
|---|---|
| Latest code commit | `ca703ec` on `main` (bundle p42); this report is updated in p43 |
| APK | GitHub → Actions → **Build APK** → the newest successful run → artifact **family-hub-apk** (`app-debug.apk`, signed with the same key as before, so it installs over the old app) |
| Builds | **#64** (p39), **#65** (p40), **#66** (p41) and **#67** (p42): all **PASS**, each with the emulator smoke test (no crash) |
| Unit tests on GitHub | "Unit tests" workflow, runs #1 to #4, all 8 suites PASS |
| Firestore rules | Not changed in this brief, so nothing was republished. The live rules are those of run #7 (79 tests PASS) |
| Screenshots | `demo-screenshots` branch (from build #67), named after each screen, in light and dark. Inspected by eye. Defects found in build #65 were fixed and checked again in build #67 |

## Results by task

| # | Task | Result | Evidence |
|---|---|---|---|
| 1 | **Inventory** of every module, screen, route, action and integration, with each one's home and its gaps | **PASS** | `docs/INVENTORY.md`, made from the code |
| 2 | **Finance: move past transactions to another category**. Multi-select in Transactions; "Apply to existing transactions" on any category; rename and delete carry entries along; every change confirmed with counts; Finance permission re-checked; Undo | **PASS** (logic and build), **NEEDS MY PHONE** (tapping through) | ledger-test (21 checks: nothing else changes, no copies, totals follow, transfers and deleted entries skipped); builds #64 and #65; Categories screenshots |
| 3 | **New categories appear at once in the add and edit forms** | **PASS** (code review) | Both forms read the live category list |
| 4 | **Transactions:** Expenses and Income tabs, month arrows, date range, search, category filter, **account or card filter**, grouped by day | **PASS** (logic and screens) | ledger-test; Transactions screenshots (light and dark) |
| 5 | **Duplicates:** reasons, confidence, which to keep, "Not duplicates", confirm with a count, Recently deleted, Undo. Recurring payments, other shops, transfers, two people and card payments are handled. The AI uses the same finder | **PASS** (logic and screens), **NEEDS MY PHONE** (removing on real data) | ledger-test (15 checks); Duplicates screenshots (light and dark) |
| 6 | **Attachments** (receipts) | **PASS** (existing feature, kept) | Receipt in the shared form. Kept as a file on the phone that added it (no free storage) |
| 7 | **Accounts and cards** with safe details only (last 4 digits) | **PASS** (existing, kept) | Cards screenshot |
| 8 | **Accurate totals** everywhere | **PASS** | ledger-test (138 checks in all); one `Ledger.totals` path for every screen |
| 9 | **Home and navigation: one home for each feature** | **PASS** (code and screenshots) | More is a grouped list. Loans, Accounts (both in Finance) and SOS (in Family safety) are no longer repeated there. Home dropped the repeated money card, the 10-shortcut row and the task and document cards, and gained 4 quick actions and Recent. Nothing was removed from the app: each of those screens has one route |
| 10 | **Apple-style UI**: grouped lists, quieter colour, less glass | **PARTIAL** | Done: Home, More, every hub screen, Finance → Manage, the Family tab invite card, the filter chips, and (p42) the shared list screen used by the smaller modules (Reminders, Pets, Vehicles and more), now one inset grouped list. Unchanged: the Insights header card and some detail sheets |
| 11 | **Light and dark themes** checked by eye | **PASS** for the screens above | Build #65 screenshots: Home, More, Finance, Transactions, Categories, Duplicates, Settings, Safety and Health in dark; Home with Reduce transparency |
| 12 | **Shared AI**: one assistant layer; actions re-check membership and Finance permission just before writing; confirm before any change; a log of every AI action and whether it really worked (AI settings) | **PASS** (code), **NEEDS MY INPUT** (a Gemini key to test real answers) | `AiAudit.kt`, `checkActionAllowed`; duplicate removal through the same path as Finance |
| 13 | **AI undo** for records the AI adds | **NOT DONE** | Such records can be deleted normally, and go to Recently deleted. Undo exists for duplicate removal and category changes |
| 14 | **Contextual AI suggestions** tailored to every page | **PARTIAL** | Unchanged from before: suggestions exist per page (Finance, tasks, shopping, calendar), but they are not rewritten for every module |
| 15 | **Gemini: your own key (BYOK)** in the Android Keystore; never logged, synced or backed up; no shared key in the APK | **PASS** (code review; done in p34) | `KeyVault.kt`; build no longer reads `GEMINI_API_KEY` |
| 16 | **Backup and restore**: backward-compatible, never overwrites newer data | **PASS** (code review; p37), **NEEDS MY PHONE** | Restore adds only entries that are missing on the server. A backup is refused when the server can't be reached |
| 17 | **CI**: the hanging Gemini check removed; unit tests on every push; screenshots named by screen, light and dark | **PASS** | Commit `cbd9555`; Unit tests run #1 |
| 18 | **Docs** reconciled (AI-SETUP, LIMITATIONS, TESTING, INSTALL, CONTINUATION-REPORT) | **PASS** | |
| 19 | **Family permissions and sync** | **PASS** (rules, 79 tests, unchanged) | Bulk changes check "Finance editing" for the person |

## Needs you

- **NEEDS MY PHONE:**
  1. Install the newest APK.
  2. In Finance → Transactions, touch and hold an entry, select a few more, tap **Category** and pick one. Check that the totals change and that **Undo** works (Categories → Recent changes).
  3. In Finance → Categories, tap ✓ on a category and move some old entries into it.
  4. Rename a category that is in use: it should offer to rename its entries too.
  5. In Finance → Duplicates, check the suggestions. Remove one, then Undo.
  6. Look at Home and More in light and dark mode.
- **NEEDS MY INPUT:**
  - a Gemini key in AI settings, to test real AI answers
  - optionally, delete the unused `GEMINI_API_KEY` secret on GitHub

## Known remaining issues

- Recent changes and the AI log are kept on the phone that made them, not shared with the family.

## Next priority

1. The Apple-style pass on the Insights header card and the detail sheets.
2. Tailored AI suggestions per page.
3. Undo for records the AI adds.
