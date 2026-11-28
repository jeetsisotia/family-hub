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

### P0 (27 Nov 20:33, bundle p68): money question asked for a Gemini key

Phone, build #96, no key: "how much did we apend on grocery this month" → "add your own free Gemini API key". Cause: the typo "apend" missed every local money pattern, so the question fell through to the AI path, which needs a key. Fix: `Planner.fixTypos` (one-letter slips and swaps, real words and Hinglish pronouns kept) before money matching; `Planner.spendPeriod` / `periodRange` (this or last month, this or last week, Hinglish); `MoneyAnswers.isMoneyQuestion`, and the Gemini chat answers money questions on the phone before any Gemini call. General questions without a key still ask for one. phase4-test 87 checks; emulator: 10 no-key checks.

### Ultimate master prompt (27 Nov 22:38): scope agreed

The brief has 16 phases (A–P), more than one session. Jeet chose, in order: (1) Liquid Glass design system plus universal search, applied to every screen; (2) calling stays on free STUN (no TURN, no cost; some networks will not connect; group calls up to about 4); messaging, new modules and the rest follow in later sessions.

Audit: most of the glass system already existed (`core/theme/Glass.kt`, `DesignSystem.kt`: glass modifier, GlassCard ×131, floating GlassTabBar with spring animation, collapsing ScreenTopBar, SegmentedControl, grouped rows, reduce transparency, reduced motion, haptics). Chat, one-to-one WebRTC calls (beta), SOS, QR joining, Home Vault, Meals, Memories, Chores, Settle Up, Travel and Notice Board modules exist.

Bundle p70: `core/theme/GlassComponents.kt` (GlassDialog with real window blur on Android 12+, GlassBottomSheet, GlassActionSheet, GlassFilterChip, GlassInputField, GlassFloatingActionButton, GlassSearchBar, GlassNotificationCard, GlassDropdownMenu, GlassSkeleton). 130 Material calls in 25 files now use them; 3 Material segmented rows now use SegmentedControl; dark background is deep charcoal instead of black. Universal search (`core/search/SearchIndex.kt`, `ui/search/SearchScreen.kt`, route `search`, bar on Home): every module and members, live suggestions, recent searches with clear, voice search where the phone supports it, section filter chips with counts, bold matches, amounts; opening a result opens that section with its search filled in (`SearchSeed`). Tests: tools/search-test (22 checks), 10 emulator checks.

Mapping of the brief's names: GlassToolbar = `ScreenTopBar`, GlassNavigationBar = `GlassTabBar`, GlassTabBar = `SegmentedControl`, GlassCard = `GlassCard`.

### Session 28 Nov (Jeet away; "complete all phases"): chat attachments and typing (bundle p72)

Chat already had replies, reactions, edit, delete for everyone/me, forward, copy, star, pin, polls, drafts, in-chat search, Seen, unread counts, offline sending, pin/mute/archive chats, @mentions. Added: photos (shrunk to 1280 px JPEG), voice messages (AAC 24 kbps, up to 3 min, waveform, 1×/1.5×/2×), documents (up to ~4.5 MB), full-screen photo viewer (pinch zoom, share), shared media sheet, "typing…" (written at most every 4 s; switch "Show when I'm typing"), forwarding attachments, delete for everyone also removes the file.
Spark plan: no Cloud Storage, so files are cut into Base64 parts of 600 KB in `chats/{chat}/parts/{fileId}_{n}` (rules: only people in the chat read; sender writes once; only sender deletes; ≤ 850,000 characters each; ≤ 8 parts; message `attachment` map checked). Each part read costs one Firestore read; received files are cached on the phone. Video is not supported (too large for this approach). Rules backup: `firebase/firestore.rules.backup-2026-11-28-before-attachments`. Tests: rules-test +5, chat-test +19, emulator +12.

### Calling (bundle p73)

Free STUN only (Jeet's choice): no relay server, so some mobile networks cannot connect; calls ring only while Family Hub is running on the other phone (no server push on the free plan). Added: group calls of up to 4 (mesh; `groupcalls/{id}` + pair signals `{from}__{to}__offer|answer`, rules: invited only, join/leave only yourself, host or last person ends, pair-only signals); smaller video for groups (480×360, 15 fps); per-person connection state, network quality (round-trip time and loss from WebRTC stats), active speaker outline, two automatic retries when a connection fails; host can "End for everyone" or just leave; incoming-call heads-up / full-screen notification while ringing (1:1 and group); missed-call notifications once per call (1:1 and group); a phone in one kind of call answers "busy" to the other. Not verifiable on the emulator: actual audio/video between phones. Tests: call-test 22, rules-test +5, emulator +7.

### Savings challenges and widgets (bundle p74)

Savings challenges (module `challenges`, items type `challenge`, Finance hub): 52-week (week n saves n × base; total 1,378 × base), same amount every day or week, and no-spend days worked out from Finance. On track / behind plan, one tap "I saved ₹X for Week n" (goal-style contribution history). Home screen widgets (`widget/HomeWidgets.kt`): Family overview (overdue first, due this week) and Shopping list (still to buy); the app writes a summary on the phone after data changes; health items never shown; Settings → Private widgets shows counts only. Not done: configurable per-module permissions (would change rules for every collection; left for a session with Jeet), update checker (needs a GitHub Release to point at; publishing was ruled out). Rules test fix: one test built its batch from two Firestore instances.

### 28 Nov status and blocker

Rules: firebase/firestore.rules adds chat parts, typing and group calls; 92 rules tests pass (run #11). NOT DEPLOYED: both browsers need Jeet to sign in to Google again (Firebase console). Jeet: Firebase console → Firestore → Rules → paste firebase/firestore.rules → Publish (keep the current text as a backup first). Emulator: build #102 85 PASS / 2 known check faults / no crash (cut off at 35 min; limit raised to 55).

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

## 28 Nov — Per-member section access (bundle p77)

- A Family Admin opens Family → a member's ⋮ menu → **Access…** and picks **None / View only / Full** for each section:
  Finance, Calendar, Tasks and shopping, Documents, Health, Home and vehicles, Family information, Memories, Travel, Chat.
- Stored on the member record as `perms` (only the limits; missing means full). `canEditFinance` is kept in step with Finance.
- **Enforced in `firebase/firestore.rules`**, not only hidden: section reads/writes on events, expenses, tasks, trips and
  family items (by `type`), chat reading/sending, and members cannot change their own access. Admins always have full access.
  Backup before this change: `firebase/firestore.rules.backup-2026-11-28-before-permissions`.
- A member with any "None" loads family items by kind (`type in [...]`, ≤30 per query) because the rules refuse a
  whole-list read for them; everyone else loads exactly as before.
- App: hidden sections disappear from More, hubs and Home; a None screen shows "No access"; View only hides Add,
  blocks editing and shows a banner; chat composer is replaced by a view-only note; AI actions are refused for sections
  the person cannot edit. "Only me" records are always the person's own.
- Item kinds not yet in a section stay open to members with no "None" section; a restricted member cannot read or add them.
- Tests: `tools/perms-test` (21 checks incl. app/rules kind lists agree), rules tests "perms: …", emulator checks "access: …".
- **Still needs the user:** publish `firebase/firestore.rules` in the Firebase console (Google sign-in).
- p78 fix: first rules run (Firestore rules tests, run 46113495523) showed a whole-list items read was wrongly allowed for a
  restricted member in the emulator (99/100). Rules now compare the item kind with one computed list of allowed kinds
  (`typesFor`, built from the access map read once), which the query checker can prove for `type in [...]` and refuse for unfiltered reads. Also fixed a
  compile error (nullable section) found by Build APK run 46113493586.
- Verified: Firestore rules tests run 46114298444 (commit cc637bd) — 100/100, all 8 "perms:" tests pass, including:
  a restricted member's unfiltered items read is refused and `type in [allowed kinds]` is allowed.
  Unit tests run 46113637255: "Section access (perms-test): all passed".

## 28 Nov — Update checker with GitHub Releases (bundle p80)

- Build APK now sets `versionCode` = Build APK run number and `versionName` = `2.1.<run>` (env `FH_BUILD_NUMBER`).
- New job `publish-release` (after the emulator job passes with no crash) creates release `build-<run>` with
  `FamilyHub-2.1.<run>.apk`, short notes from recent commit subjects, and marks it latest. Uses the workflow's own
  token; no secrets in the APK or the release.
- App: Settings → **App updates** shows this version, checks `releases/latest` (at most every 12 h at start, or on
  "Check for updates"), shows version, date, size, notes and SHA-256, and a **Download update** button that opens the
  file in the browser. Nothing installs silently: Android asks, and accepts the APK only if it is signed with the same
  key (the fixed CI key) as the installed app.
- Tests: `tools/update-test` (11 checks), emulator checks "updates: …".
- Note: builds installed before this change report build 1, so the first release is offered to them as an update.

## 28 Nov — Travel planner and expense splitting (bundle p81)

- New `money/Split.kt` (pure, paise-exact): split **Equally** (all or chosen people), **Exact amounts**, **Percentages**
  (must total 100) or **Shares**; balances over open expenses; the fewest payments to settle (≤ people − 1);
  a recorded payment is just an expense paid by the debtor and owed fully by the creditor, so balances update.
  Expenses whose amounts/percentages don't add up are skipped and the count is shown, never guessed.
- Settle up: new fields Split with / How to split / per-person detail / Trip. Header shows shared spending, each
  person's net, trip filter chips, and a "Record payment" button per suggested payment (hidden for view-only access).
- Travel: Who is going, Documents to carry, packing list with [x] ticks; header with the next trip's countdown or
  "day N of M", budget and packing progress.
- Tests: `tools/split-test` (16 checks), emulator checks "split: …" and "travel: …".
- p81 compile error (Build APK run 46114751261: nested class through a value alias) fixed in p82.

## 28 Nov — Memories: photo albums, reactions, comments (bundle p82)

- Open a memory to see its story, a photo album (up to 12 photos), reactions (❤️ 😂 😮 🎉 🙏, one per person) and comments.
- Free plan: photo bytes are stored in Firestore parts (≤600 KB each) like chat attachments —
  family memories in `families/{f}/memoryParts`, "Only me" memories in `users/{uid}/memoryParts`.
  Comments and reactions in `families/{f}/memoryNotes`.
- Rules (new): parts readable with Memories access, added only with Memories "Full", owner fixed, size ≤ 850 000 chars,
  never changed, removed by the adder or an admin. Notes: own uid only, comment or reaction (reaction id
  `memoryId_uid_r`), ≤ 500 chars, only the author edits, author or admin deletes. View-only Memories may still comment.
- Memories list header: "On this day" (earlier years) and albums with counts; new Album field.
- Safety already had SOS, check-ins, safe-arrival and low-battery alerts (no change needed in this phase).
- Tests: `tools/memory-test` (6 checks), rules tests "memories: …", emulator checks "memories: …".
