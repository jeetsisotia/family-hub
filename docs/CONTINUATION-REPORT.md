# Continuation report

For picking up the work after a usage limit or a new session. Read this file and `PROGRESS.md`, fetch `main`, and continue from the first unfinished task. Do not repeat finished work.

## Current brief (Brief 5, 27 Nov 2026 12:38)

**Brief:** "FamilyHub — Master development prompt" (four phases).

**Your instructions:**
- Before: finish **Priority Zero and Phase 1 only** (done).
- 27 Nov 15:41: "First complete the phases, then give me the 'needs your phone' tasks in one go at the end." So continue with Phase 2 → 3 → 4, then the premium features if time allows. Collect every phone test and list them only in the final report.

**Rules:**
- Keep the existing app: package, Firebase project, rules, signing, backup and data.
- Spark plan only. No secrets in code, APKs, commits or logs.
- Nothing counts as PASS without test evidence.
- Statuses: PASS, PARTIAL, BLOCKED, NOT STARTED.

### Checklist (update after every bundle)

Priority Zero and Phase 1: **done**. Latest full emulator run: Build APK #86 (`7ba2b33`).

| # | Item | Status | Evidence / commit |
|---|---|---|---|
| P0-A | AI matching: "Change the category of Mukul to Shopping" and 13 other phrasings (English and Hinglish), the AI's own wording cleaned, closest matches offered when nothing matches exactly | **PASS** (emulator #83, ledger 175) | p58 `31649aa`; p60 scroll fix |
| P0-B | AI screen: the chips above the message box (Web search, FamilyHub data, Google) no longer wrap; checked at 1.3× text and 360dp width | **PASS** (#86 screenshots at 360dp and 1.3× text) | p58, p60, p61 |
| P0-C | Due Summary: tap to fold and open checked on the emulator (the rest was checked before) | **PASS** (emulator #83) | p58 |
| P0-D | Health (vitals, medicines), Documents and Reminders add forms, light and dark | **PASS** (#83 screenshots; Reminders label fixed in p60) | p58, p60 |
| P0-E | Gemini errors: invalid key, key refused, API not enabled, rate limit, daily quota, web search quota and network problems each get their own message (`GeminiErrors`, 11 tests) | **PASS** (unit); live on your key: NEEDS MY PHONE | p58 |
| P0-F | Regression table | **Done**: VERIFICATION-REPORT §8 | p60 |
| 1.1 | Family activity timeline (More → Family activity): built from records already loaded; filters by type and member; "Only me", private, health and ID records never shown; documents show name and type only; saves now record who last updated and who completed | **PASS** (#85 screenshots, phase1-test) | p59 `9847982`, p60 |
| 1.2 | Document expiry tracker: Remind me window per document, renewal status and notes, more categories (Warranty, Vehicle PUC), summary card on Documents (expired, due soon, active, next to renew); notifications name documents only generically | **PASS** (#85, phase1-test); expiry notification on a phone: NEEDS MY PHONE | p59 |
| 1.3 | Recurring payment suggestions (Finance → Recurring payments): weekly, monthly, quarterly and yearly patterns; Track saves a Subscription (or a weekly reminder) after you confirm name, amount and frequency; Dismiss; never creates transactions; no duplicates of what is already tracked | **PASS** (#86 Track check, phase1-test) | p59 |

### Phase 2

| # | Item | Status | Evidence / commit |
|---|---|---|---|
| 2.1 | Shopping lists: several lists, quantity, category, estimated price, who buys it, Pending / Not available / Bought history, search, estimate of what is left, "Only me" items (route `shopping`) | **PASS** (#89, #92) | p62 `e71d047` |
| 2.2 | Home and vehicle maintenance: warranty, last service and provider fields; overview with what is due in 60 days; "Log service" keeps a service history (cost, who, what) and moves the next date for repeating jobs | **PASS** (#89, #92) | p62 |
| 2.3 | Calendar 2.0 (above the calendar list): Month, Week and Agenda views; filters by member and category; clash warnings; whose event and who else is going; end time; birthdays shown | **PASS** (#89, #92) | p62 |
| 2.4 | Subscription manager: provider, status (Active, Paused, Cancelled), payment method; monthly and yearly totals; renewals in the next 30 days; history; paused and cancelled ones have no dues | **PASS** (#89, #92) | p62 |

### Phase 3

| # | Item | Status | Evidence / commit |
|---|---|---|---|
| 3.1 | Savings goals: progress card above Savings goals, add or take out money with who added it, contribution history, by member, monthly amount needed, archive, notification when reached; shared or Only me as before | **PASS** (#92); notification on a phone | p63 `bdea772` |
| 3.2 | Budget planner (Finance → Budget planner): family budget against spending; category limits (monthly or yearly, family or Only me) planned against actual; remaining; alerts; 6-month trend; this month against last; CSV export | **PASS** (#92) | p63 |
| 3.3 | Money insights (Finance → Money insights): month summary, biggest increases, unusual spends, repeating payments, by member (shared entries only), suggestions marked "not financial advice" | **PASS** (#92) | p63 |
| 3.4 | Reports (Finance → Reports): monthly expenses, categories, members, recurring, goals and budget as CSV; PDF summary; shared through the share sheet | **PASS** (#92) | p63 |

### Phase 4

| # | Item | Status | Evidence / commit |
|---|---|---|---|
| 4.1 | Family AI planner (both AI screens, answered on the phone): "What's scheduled this weekend / tomorrow / this week" (with clashes), "Show upcoming bills", "Summarise this week's family activity", "Create a shopping list for tomorrow: milk, eggs" (named list, cards to confirm). "Remind me about…" still goes to the AI and comes back as cards | **PASS** (unit); emulator in the final run | p64 `9052934` |
| 4.2 | Expense questions: "Which category increased", "Show recurring expenses", "How much is pending in upcoming bills" (spend questions were already answered on the phone) | **PASS** (unit) | p64 |
| 4.3 | Smart reminder agent: "Suggest reminders" offers reminders for documents, services, warranties, insurance and PUC, active subscriptions, events and tasks that have none yet; each is confirmed | **PASS** (unit) | p64 |
| 4.4 | Email-to-task: in the Gemini chat, after reading Gmail (Google → Gmail), "Find tasks and dates" turns the emails into task, reminder and event cards; skips ones already in Family Hub; never sends or changes email | Code; **needs a phone** (Google sign-in) | p64 |
| 4.5 | Document assistant: attach a PDF or photo in the Gemini chat to summarise it or ask about it (already there); "Find tasks and dates" suggests reminders from it | Code; needs a phone and a Gemini key | p64 |
| 4.6 | Weekly family brief (More → Weekly brief, or "weekly brief" in the chat): events, tasks, bills, documents, maintenance, activity, money; each section can be switched off | **PASS** (unit) | p64 |
| 4.7 | AI action security: every AI change goes through the shared runner (permission re-check, a preview card, your confirmation, only the approved action, result checked, audit log in AI settings, Undo where possible). Firestore rules unchanged | Existing + Phase 4 actions use it | p55–p64 |

All four phases are built. Premium features: see VERIFICATION-REPORT §9 (most already existed; savings challenges and per-record permissions are not started).

### Audit against the master prompt (27 Nov 17:40, bundle p66)

| Gap found | Fix | Status |
|---|---|---|
| "How much did we spend on groceries this month?" gave only the overall total | `Planner.spendTopic` / `matchCategory`; MoneyAnswers answers for that category (or payee), with last month alongside | Unit PASS; emulator check `ask_groc` |
| Notification centre had no priority or category filters | Due soon: overdue first; chips All, Overdue, and each section | Emulator check `alc` |
| Subscription history did not show real payments | `Subscriptions.payments`: "Paid recently (from Finance)" | Unit PASS; emulator check `p2sub` |
| Maintenance had no warranty start or receipt | Warranty starts; receipt or bill photo (stays on the phone) for maintenance and vehicles | Build |
| Docs had no commit SHAs for Phases 2–4; Household inventory marked PARTIAL though Home Vault exists | VERIFICATION §9 corrected | Done |

### AI audit brief (27 Nov 19:02, bundle p67)

Phone report: "How much did we spend on grocery this month?" → "couldn't find". Causes in code: (1) "we" questions used the family view, which drops your own Only me entries (the Transport question said "I", which keeps them); (2) only the main category was searched, never subcategories; (3) no synonyms. Fix: `Planner.spendTopic` (English and Hinglish), `Planner.findCategories` (stored categories and subcategories only, synonyms such as food shopping / supermarket / kirana → Groceries, several real matches → ask), `MoneyAnswers.spendOn` (total, count, last month, Only me share named). phase4-test +31 checks; 9 emulator checks. Other AI areas were checked earlier (VERIFICATION §4, §7–§9) and not repeated. Backup: any local photo in any field (receipt photos included) goes into the backup file (`DataToolsScreen.backupJson`) and is restored (`LocalPhotos.import`); restore on a phone not tested.

### Phone tests to give at the end (collect here)

1. Mukul-style category change on your own data, in both AI screens.
2. Document expiry notification wording.
3. Web search and the Gemini error messages with your key.
4. Gmail still works.
5. Family activity with entries made by other members.
6. Phase 2: a shopping item added on one phone appears on another member's phone; an "Only me" item does not.
7. Phase 3: share a PDF report to WhatsApp or Gmail and open it; the goal-reached notification.
8. Phase 4: Gmail → "Find tasks and dates" on a real email; attach a PDF (for example an insurance policy) and use "Find tasks and dates".

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
