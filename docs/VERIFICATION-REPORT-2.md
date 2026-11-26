# Verification report: Finance redesign and Universal AI assistant

27 November 2026. This compares every requirement of the second brief with what is in the code on `main`.

**Statuses:**
- **PASS:** implemented and tested
- **FAIL:** implemented but not working
- **NOT IMPLEMENTED**
- **NOT TESTED:** could not be verified here
- **NEEDS EXTERNAL SETUP:** needs Google setup, OAuth or a server
- **NEEDS MY PHONE:** needs a real phone

Here, "tested" means plain-Kotlin unit tests run in Claude's workspace, the GitHub build, and the GitHub emulator screen check, which opens each screen with sample data and records crashes.

**The screenshots were not inspected.** The emulator takes them, but they could not be downloaded from Claude's workspace (the storage host is blocked), so nothing visual was judged. Every look-and-feel item is therefore **NOT TESTED** or **NEEDS MY PHONE**.

## 1. Finance

| Requirement | Status | Evidence |
|---|---|---|
| Monthly balance, income and expenses | PASS (calculations) | `FinanceMonth.view` over `Ledger.totals`; tests check paise-exact totals, refunds, and that transfers, deleted entries and unpaid bills are left out |
| Comparison with the previous month | PASS (calculations) | Test: 25,000 → 27,350.25 = +9%; empty months show no comparison |
| Top 5 expense categories | PASS (calculations) | Test checks the order |
| Latest 5 transactions and View all | PASS (calculations); screen opens | Test: newest first, unpaid dues left out |
| Month selector (previous and next) | Implemented; NEEDS MY PHONE | Month ranges across years tested |
| No repeated information | Implemented | The old long overview card was replaced; accounts, cards and loans are reached from tiles |
| One Transactions screen: Expenses and Income tabs, total, month selector, search, category filter, breakdown, grouped by date | PASS (logic); screen opens in the emulator (dark) | Tests: lists, totals, search by text and amount, category filter, breakdown shares, grouping by day |
| This month, Last month, Custom range | Implemented; NEEDS MY PHONE (range picker) | — |
| Shared add and edit form: type, amount, category with icon, account or card, date, description, collapsed attachment | Implemented; NEEDS MY PHONE | Saves through the same `FinanceModule.postProcess` as the old form |
| Custom categories: name and emoji, preview, edit, delete with confirmation, separate income and expense | Implemented; screen opens in the emulator | — |
| No duplicate categories within the same type | PASS | 6 tests: built-in clash, case and spaces, the other type allowed, "Both", renaming to itself |
| Categories shared with family members | Implemented; NEEDS MY PHONE (two phones) | Saved as family records (`items`, type `category`) |
| Past transactions keep their category on rename or delete | PASS (by design) | Transactions store the category name; rename and delete never touch transactions. The delete dialog shows how many use it |
| All existing default categories kept | PASS | `financeCategories` unchanged; income built-ins added |
| Cards: wallet style, last 4 digits only, limit, available, due date, transactions, add, edit, remove with confirmation | PASS (already in the app; checked in code) | No CVV or full-number fields anywhere; delete asks first |
| Accounts: name, type, balance, transactions, management | PASS (already in the app) | Balances are covered by money tests |
| Never fabricate missing financial data | PASS | Empty states ("No spending this month", "—") instead of guesses |
| Monthly budget: spent, remaining, progress | Implemented on the dashboard; PASS (uses the tested totals) | The budget editor and Insights already existed |
| Category-wise spending, top 5, detailed insights | PASS (existing Insights plus the new breakdown) | — |
| Compact Finance header, no duplicate titles | Implemented on every Finance screen (compact bar) | Visuals NOT TESTED |
| System-bar insets, oversized back button | NOT TESTED | Needs screenshots or a phone |
| Imports, backup and restore unchanged | PASS (tests: SMS 15, statements 22; no change to that code) | — |
| Existing data integrity | PASS | No migration; no saved data rewritten |

## 2. Universal AI

| Requirement | Status | Evidence |
|---|---|---|
| Gemini as the provider, behind a provider interface | PASS (compiles); NOT TESTED live | `AiProvider` and `GeminiClient` |
| Configurable model, model switching | Implemented; NOT TESTED live | Automatic = cheapest free flash, never paid by itself |
| Connection test, errors, timeouts | Implemented; NOT TESTED live | 15 s connect, 60 s read; clear messages per error |
| **Shared family API key behind a secure backend** | **NOT IMPLEMENTED: NEEDS EXTERNAL SETUP** | Needs a server (paid Firebase plan or another host). Disabled and explained in AI settings and `docs/AI-SETUP.md` |
| No API key in the APK or source | PASS | The build no longer injects `GEMINI_API_KEY`; no key in the repository |
| BYOK: connect, update, test, disconnect, status, AI Studio steps | Implemented; NEEDS MY PHONE (with your own key) | — |
| Keystore encryption, private, not logged, synced or backed up | PASS (code review) | AES-256-GCM Android Keystore, per user; backup exclusions added |
| General questions, writing, translation, code, maths, planning, follow-ups | Implemented; NOT TESTED live | Last 14 turns sent |
| Document and image understanding | Implemented (image, PDF, text up to 7 MB); NOT TESTED live | — |
| Web research with sources, current vs model knowledge, no invented sources | Implemented; NOT TESTED live | Sources only from Google grounding data. On free keys Google may refuse search for Gemini 3 models; the app says so |
| Gmail search, read and summarise; draft replies | Implemented (read-only; drafts shown for copying); **NEEDS EXTERNAL SETUP** (test users) | — |
| Drive search, read and summarise | Implemented (Docs and text files); **NEEDS EXTERNAL SETUP** (Drive API, scope, test users) | — |
| Calendar: view events, help with scheduling | Implemented (read-only); **NEEDS EXTERNAL SETUP** (Calendar API, scope, test users) | — |
| Google access separate from sign-in, explained, tokens protected, revocation guidance, no send, delete or modify | PASS (by design) | Read-only scopes only; tokens in memory only |
| FamilyHub actions with confirmation, roles respected, no other family's data, truthful success | PASS (existing, checked in code) | `applyAction` saves only after a tap and throws on failure; budget is admin-only; only the active family |
| Chat UI: welcome prompts, history, new conversation, copy, regenerate, stop (closes the stream), streaming answers, Markdown, code copy, attachments, provider and model shown, states | Implemented; the not-connected screen opens in the emulator; NEEDS MY PHONE for the rest | — |
| AI settings: provider, model, status, key management, test, usage, disconnect, privacy | Implemented; the screen opens in the emulator | — |
| Usage tracking, daily and monthly limits, quota messages, rate limits | PASS (limit logic, 3 tests plus the earlier per-minute tests); counting NOT TESTED live | — |
| No automatic switch to paid models; charges explained | PASS (by design) | — |
| Prompt-injection protection | Implemented | Fetched content is marked as data; the chat cannot change data |
| Conversation history controls | Implemented | On the phone only; delete one, delete all, don't save |

## 3. Security verification

- **Keys:** no AI key in the APK, the repository or Firestore. Each person's key is Keystore-encrypted, per user, and excluded from backups.
- **Google:** Google tokens are never stored. Scopes are read-only.
- **Data rules:**
  - Firestore rules are unchanged by this brief (79 emulator tests passed earlier).
  - Custom categories use the existing family `items` rules.
  - AI context leaves out health records, ID numbers, private notes, "Only me" reminders and the emergency card.
- **Remaining risk:** on the free tier, Google may use prompts to improve its products. AI settings says so.

## 4. Data integrity

- No data migration, and no saved transaction or category rewritten.
- The new form writes the same fields through the same rules as before.
- The full old Finance list remains under "All entries".

## 5. Build and emulator results

| Build | Content | Result |
|---|---|---|
| #59 | Finance redesign (commit `3f9b2c9`) | **PASS**: compiled on its first build; emulator screen check passed with no crash (dashboard, Transactions in dark, Categories and 18 other screens) |
| #60 | Universal AI (commit `e5d6693`) | **PASS**: compiled on its first build; emulator screen check passed with no crash (universal chat not connected, AI settings and 19 other screens). Attempt 1 was cancelled when the old "Check Gemini API key" step hung; attempt 2 passed |

## 6. Remaining limitations

1. The shared family key needs a server (NEEDS EXTERNAL SETUP).
2. Gmail, Drive and Calendar need the Google Cloud setup in `docs/AI-SETUP.md` (NEEDS EXTERNAL SETUP).
3. Web search may be unavailable on free keys for Gemini 3 models (Google's rule).
4. Screenshots were not inspected; every look-and-feel item needs a phone check.
5. The "Check Gemini API key" CI step keeps hanging. It is now unused, because the key is no longer built in, and can be deleted from `.github/workflows/build-apk.yml`.
6. Answers stream word by word (Gemini SSE), and Stop closes the connection and keeps the text so far. Not tested live (no key here).

## 7. APK

The latest successful APK is under **GitHub → Actions → Build APK → the newest green run → Artifacts → family-hub-apk**:
https://github.com/jeetsisotia/family-hub/actions/workflows/build-apk.yml
