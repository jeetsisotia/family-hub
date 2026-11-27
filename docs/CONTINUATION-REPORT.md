# Continuation report

For picking up the work after a usage limit or a new session. Read this file and `PROGRESS.md`, fetch `main`, and continue from the first unfinished task. Do not repeat finished work.

## Current brief (Brief 5, 27 Nov 2026 12:38)

**Brief:** "FamilyHub — Master development prompt" (four phases).

**Your instruction:** finish **Priority Zero and Phase 1 only**, then stop. Phases 2–4 and the premium features wait for your go-ahead.

**Rules:**
- Keep the existing app: package, Firebase project, rules, signing, backup and data.
- Spark plan only. No secrets in code, APKs, commits or logs.
- Nothing counts as PASS without test evidence.
- Statuses: PASS, PARTIAL, BLOCKED, NOT STARTED.

### Checklist (update after every bundle)

| # | Item | Status | Evidence / commit |
|---|---|---|---|
| P0-A | AI matching: "Change the category of Mukul to Shopping" and 13 other phrasings (English and Hinglish), the AI's own wording cleaned, closest matches offered when nothing matches exactly | Code + 175 ledger checks | p58 |
| P0-B | AI screen: the chips above the message box (Web search, FamilyHub data, Google) no longer wrap; checked at 1.3× text and 360dp width | Code; emulator check added | p58 |
| P0-C | Due Summary: tap to fold and open checked on the emulator (the rest was checked before) | Emulator check added | p58 |
| P0-D | Health (vitals, medicines), Documents and Reminders add forms, light and dark | Demo routes `form:<module>` added | p58 |
| P0-E | Gemini errors: invalid key, key refused, API not enabled, rate limit, daily quota, web search quota and network problems each get their own message (`GeminiErrors`, 11 tests) | Code + tests | p58 |
| P0-F | Regression table | To do after the build | |
| 1.1 | Family activity timeline (More → Family activity): built from records already loaded; filters by type and member; "Only me", private, health and ID records never shown; documents show name and type only; saves now record who last updated and who completed | Code + tests (phase1-test) | p59 |
| 1.2 | Document expiry tracker: Remind me window per document, renewal status and notes, more categories (Warranty, Vehicle PUC), summary card on Documents (expired, due soon, active, next to renew); notifications name documents only generically | Code + tests | p59 |
| 1.3 | Recurring payment suggestions (Finance → Recurring payments): weekly, monthly, quarterly and yearly patterns; Track saves a Subscription (or a weekly reminder) after you confirm name, amount and frequency; Dismiss; never creates transactions; no duplicates of what is already tracked | Code + tests | p59 |

### How to resume

1. Fetch `main` and read this table.
2. Pick up the first row that is not PASS.

Bundles are uploaded through the upload page and applied by `apply-bundle.yml`. Workflow files are uploaded on their own; never include `.github` in a bundle.

## State (Brief 4)

| | |
|---|---|
| Starting commit | `4b53592` (build #71) |
| Bundles | p48 Due Summary (`f147faf`), p49–p50 AI live check, p51 web-search quota message (`04ce4c0`), p52 Due Summary header total (`0225e5d`), p53 docs, p54 header short total (`27bc0c7`), p55–p56 AI Finance action (`7805873`, `26d8046`), p57 docs |
| How code reaches GitHub | Bundles through the upload page and `apply-bundle.yml`. Never include `.github/` in a bundle (p50 deleted a workflow that way). Workflow files are changed on the upload page itself |
| Artifact | `family-hub-apk` on the Build APK run for the latest commit |

## Brief 4 tasks

| # | Task | Status |
|---|---|---|
| 1 | Home Due Summary (count, total, overdue; folds on scroll; manual toggle; no repeats) | **Done**, screenshots checked light and dark; header total fixed in p52 |
| 2 | Apple-style forms and sheets | Transaction form and detail sheet **done** (both themes). Other module forms: no demo routes yet, **not screenshotted** |
| 3 | Gemini live checks | Invalid key, general answer and follow-up **pass** on the repo key; web search **fails** on that key's free quota (429). Your key: needs your phone |
| 4 | Agent actions live | Needs your phone |
| 5 | Docs | VERIFICATION-REPORT, AI-SETUP, LIMITATIONS, PROGRESS, this file: **done** |
| 6 | AI Finance action (change a transaction's category from both AI screens) | **Done**, p55 `7805873` + p56 `26d8046`. Emulator check with a real local database passed (Build APK #80). Your phone: still to check |

## Next exact step

1. Check the screenshot of the Due Summary header from the p52/p53 build.
2. Add demo routes for the Health, Documents and Reminders add forms and screenshot them.
3. Re-run AI live check when the free quota resets, to retry web search.
