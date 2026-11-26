# Family Hub: feature inventory

Made 27 Nov 2026 from the code at commit `6e8cdbb` (101 Kotlin files, about 28,000 lines). Each status here comes from reading the code and, where it says so, from automated tests. **A screen existing is not counted as working.**

**Status words**
- **Verified**: automated tests or CI checks cover it (named in the row).
- **Code only**: written and compiles, but nothing has checked its behaviour.
- **Needs my phone**: only a real phone can show it works.
- **Gap**: missing or incomplete against the current brief.
- **Duplicate**: reachable from more places than it should be.

## 1. Navigation map (as found)

Bottom tabs: **Home, Finance, Health, Chats, Family, More**.

The More grid has these tiles:
- AI Assistant, SOS, Family safety and Calendar
- three hubs: Home, Family life and Reminders
- **Loans & EMIs** and **Bank accounts**. Both duplicate tiles inside Finance (`loans_home`, `accounts_home`).
- Settings

Home has, top to bottom:
1. a header with a profile menu (Profile, Families, Arrange Home, Settings)
2. a Family/My switch
3. a glance card (chat count)
4. Today
5. Dues
6. an 8-tile category grid (Finance, Health, Reminders, Calendar, Home, Family Life, Documents, Ask AI)
7. an AI card
8. a members row
9. a "Monthly expenses" card
10. a row of 10 shortcuts: Speak, SOS, Health, Medicines, Reminders, Insights, Card bills, Ask AI, Import and Periods
11. "Pending tasks" and "Doc renewals" cards

**Duplicates found on Home**
- Finance appears 3 times: the category tile, the expenses card and Insights or Card bills in shortcuts. It is also its own tab.
- Health appears 3 times: the tile, the Health shortcut and Medicines or Periods in shortcuts. It is also its own tab.
- AI appears 3 to 4 times: the "Ask AI" tile, the AI card, Speak and Ask AI in shortcuts. It is also on More.
- Reminders appears 3 times: the tile, the shortcut and the Today card.
- Household tasks and document alerts appear twice: the category tiles and the cards at the bottom.
- SOS appears twice: the shortcut and the Today card. It is also on More.

**Duplicates found elsewhere**
- More → "Loans & EMIs" (`loans`) and Finance → "Loans & EMIs" (`loans_home`).
- More → "Bank accounts" (`accounts`) and Finance → "Accounts" (`accounts_home`).
- More has "SOS" and "Family safety" as two tiles; SOS opens Safety and Safety opens SOS.
- The Reminders hub repeats Subscriptions, Insurance, Card bills, Birthdays, Vehicles and Maintenance from other hubs. This is contextual (renewal dates) and can stay as shortcuts.

## 2. Modules

| Module | Canonical place | Data (Firestore) | Status | Gaps |
|---|---|---|---|---|
| Sign-in, fingerprint lock, roles, invites, QR, approval | Login; Family tab; Settings | `users`, `families/{f}/members` | Rules **Verified** (79 tests). Sign-in fix: **Needs my phone** | none |
| Home | Home tab | reads all | Code only | **Duplicate** cards (see 1) |
| Finance dashboard | Finance tab | `expenses`, `items` | Totals **Verified** (ledger-test 102) | none |
| Transactions list | Finance → Transactions | `expenses` | Filters **Verified** (ledger-test) | **Gap:** no account or card filter, no multi-select, no bulk change of category |
| Add or edit transaction | shared sheet `TransactionForm` | `expenses` | Code only; offline save once by id | none |
| Categories | Finance → Categories | `items` (kind category) | Duplicate-name check **Verified** | **Gap:** a new or renamed category never reaches past transactions ("past transactions are never changed"). No "Apply to existing transactions". Deleting leaves old entries on a missing category |
| Merchant rules ("same shop") | transaction sheet | `items` (private rule) | **Verified** (MerchantRules in ledger-test) | Applies to future imports only; existing entries not offered |
| Duplicate finder | AI command / Finance | `expenses` | Grouping **Verified** | **Gap:** no confidence, no reasons, no "keep this one", no audit record, recurring and transfer cases not separated |
| Accounts, cash, wallets | Finance → Accounts | `items` | Balances **Verified** | More tile duplicates it |
| Credit and debit cards | Finance → Cards | `items` | Last 4 only **Verified** in code review | none |
| Loans and EMIs | Finance → Loans | `items` | **Verified** (ledger) | More tile duplicates it |
| Budgets and Insights | Finance → Insights | family budget, `expenses` | **Verified** (totals) | none |
| Due calendar, transfer, SMS, Gmail and statement import | Finance tiles | `expenses` | Parsers **Verified** (import-test 15 + 22) | Gmail and SMS on a real inbox: **Needs my phone** |
| Receipts (attachments) | transaction form, "Receipt" | file on the phone, path in record | Code only | Kept on the adding phone only (no free storage) |
| Health hub (steps, vitals, medicines, periods, workouts, habits, food diary, records, emergency info) | Health tab | `users/{u}/activity`, records | Logic **Verified** (health-test 21) | none known |
| Calendar | More → Calendar | `events` | Repeats **Verified** (reminder-test 40) | Not on Home except Today |
| Reminders and alarms | Reminders hub | `reminders` | Rules **Verified**; alarms: **Needs my phone** | none |
| Household tasks, chores, shopping, meals, maintenance, staff, vault, vehicles, property, pets | Home hub | one collection each | Generic record screen; Code only | none known |
| Family life (birthdays, school, travel, memories, notices, notes) | Family life hub | one collection each | Code only | Memories are text only (no free storage) |
| Documents | Home category / Reminders hub | `documents` | Code only | Files stay on the phone |
| Chat | Chats tab | `chats` | Rules **Verified** | Two phones: **Needs my phone** |
| Calls (Beta) | Chats → Calls, one-to-one chat | `calls` | Rules **Verified**, build passed | **Needs my phone** |
| Safety, SOS, live location, check-ins, emergency card | More → Family safety | `safety`, `locations` | Logic **Verified** (safety-test 64), rules **Verified** | **Needs my phone**. Two More tiles for one area |
| Universal AI chat | More → AI Assistant | phone only | Code only (no key here) | **Needs my input** (your own Gemini key) |
| FamilyHub actions (AI that can add or change records) | page AI buttons, `assistant_family` | through repositories | Confirm-before-save in code | **Gap:** no shared action registry with permission re-check, audit log or undo; bulk recategorise and duplicate merge not offered as actions |
| Page AI (contextual "Ask AI" on screens) | `LocalPageAi` | — | Code only | Suggestions are generic on most pages |
| Backup, restore, recently deleted, reset | Settings → Data & backup | all record collections | Code only (server-checked since p37) | **Needs my phone** (4 checks). Restore never overwrites newer data (adds missing only) |
| Settings | Home profile menu, More | prefs | Code only | none |

## 3. Integrations

| Integration | Status |
|---|---|
| Firebase Auth (Google) | Working before; Android 16 fix **Needs my phone** |
| Firestore (Spark) | Rules **Verified**, about 6,700 reads a day |
| Health Connect | Code only, **Needs my phone** |
| SMS read and send | Parsers **Verified**; sending **Needs my phone** |
| Gmail, Drive, Calendar (read-only, AI chat) | **Needs my input**: Google Cloud consent screen and APIs |
| Gemini (your own key, Android Keystore) | Code only. **Needs my input**: a key |
| WebRTC calls with free STUN | Build passed; **Needs my phone** |
| CI: build, emulator smoke test, rules tests | Working. The "Check Gemini API key" step hangs and is no longer needed (the key left the APK in p34) |

## 4. Plan from this inventory (in order)

1. **Finance category reassignment:**
   - "Apply to existing transactions" with counts
   - multi-select in Transactions with "Change category"
   - rename and delete carry the change to past entries after a confirmation
   - an account or card filter
   - tests
2. **Duplicate finder:** confidence, reasons, which to keep, exclusions, an audit record and refreshed totals.
3. **One home for each feature:**
   - Home trimmed to an overview, Today, a few quick actions and recent activity
   - the More duplicates removed (Loans and Accounts stay in Finance; SOS and Safety become one tile)
   - nothing deleted, every screen still reachable
4. **Apple-style pass:** grouped lists instead of stacks of cards on Home, More and Finance, quieter colours and less glass.
5. **Screenshots I can inspect:** CI saves the emulator screenshots to a `ci-screens` branch so they can be checked by eye, in light and dark.
6. **CI and docs:** remove the obsolete Gemini check; update `docs/AI-SETUP.md`, `docs/LIMITATIONS.md`, `docs/TESTING.md`; add `docs/CONTINUATION-REPORT.md`.
7. **AI action registry:** one list of actions, each with a permission check, confirmation for anything that changes data, an audit record and undo where possible. Bulk recategorise and duplicate merge are added as actions.
