# Testing report: Family Hub 2.0

There were four kinds of checks:

1. Plain-Kotlin tests of the money engine, the SMS and statement readers, and the assistant's question matching.
2. A full build of every change on GitHub Actions.
3. An emulator test after every build: it installs the APK, opens every main screen with sample data and saves screenshots.
4. Firestore rules tests in the Firebase Rules Playground.

Parts that need a real phone, a real inbox or a real Google account are listed at the end, with a checklist.

## 1. Automated tests (plain Kotlin)

Run on 26 Nov 2026 against the code in this release. Every check passed.

| Suite | Folder | Checks | Result |
|---|---|---|---|
| Money engine (`Ledger.kt`) | `tools/ledger-test` | 37 | All passed |
| SMS and email alerts (`TxnParser.kt`) | `tools/import-test` | 12 | All passed |
| Statement and Axio import (`StatementParser.kt`, `StatementImport.kt`) | `tools/import-test` | 22 | All passed |
| Assistant questions (`MoneyAnswers.kt`) | `tools/ai-test` | 22 | All passed |

### What the money tests prove
- **Accounts:** opening balance counted from the start of its date; transfers move money without changing income or spending; debit card spends reduce the linked account.
- **Credit cards:**
  - The statement amount, outstanding, amount due, minimum due and available limit.
  - A purchase is counted as spending once, when it is made.
  - Paying the bill lowers the card and the bank, and is not counted as spending again. A partial payment is tested too.
  - A card bill saved as an entry is never charged twice.
- **Loans:** the EMI formula; the full schedule; paid, overdue, due and upcoming instalments; outstanding principal; interest payable.
- **Dues:** an unpaid electricity bill, a card bill (with the default due day, wrapping past month end) and an overdue EMI all appear, and a saved card bill replaces the generated one.
- **Duplicates:**
  - The same payment from SMS and email is found as one.
  - A card payment seen from the bank side and the card side is one payment.
  - Payments to two different cards are not duplicates.

### What the import tests prove
- **Alerts:**
  - UPI debits, card spends (with limit), credits (with balance) and card bill payments are read correctly, each with a confidence level.
  - OTP messages are skipped, and vague messages are marked unclear or skipped.
- **Statements:**
  - CSV and .xlsx files are read: delimiters, quoted values, Excel dates and day/month order are detected.
  - Headers such as "Withdrawal Amt." and "Chq./Ref.No." are matched to the right columns.
  - Card payments, self-transfers and ATM cash are recognised. Old .xls files are refused with a message.

### What the assistant tests prove
- Each of the questions in your brief reaches the right on-phone answer:
  - How much did I spend this month
  - What is my highest spending category
  - How much EMI is pending
  - Which credit card bill is due next
  - What is my monthly savings
  - Show family spending
  - Categorize pending transactions
- Requests to add or log something ("Add expense 500 for groceries", "Remind me…") go to the assistant, not to a money answer.
- "Add a category called Pets", "add subcategory Vet under Pets", "Set the family budget to 80k" and "Switch to dark mode" each become a confirm card.
- **Bug found and fixed:** while writing these tests, the category name was being read from the wrong part of the sentence. It is fixed in this release.

## 2. Builds

Every change was built from scratch on GitHub Actions (`Build APK`). Each build:
- compiles all the Kotlin and Compose code,
- checks that the APK is signed with the key registered in Firebase,
- checks that the Google sign-in client ID is set.

| Change | Build | Emulator test |
|---|---|---|
| Finance v2: money engine, dashboard, accounts, cards, loans, due calendar | #42 ✅ | ✅ |
| Statement import, SMS card payments, roles, safe delete, data tools, new tabs | #43 ✅ | ✅ |
| Health redesign, assistant money answers, reduced motion | #44 ✅ | ✅ |
| QR invite, Home at a glance, theme choice, reset one kind, docs | #45 ✅ | ✅ |
| Invite card buttons and BMI labels (from the #45 screenshots) | #46 | see the Actions page |

## 3. Emulator test (after every build)

The workflow:

1. Starts an Android emulator.
2. Installs the new APK and opens the real login screen.
3. Taps **Continue with Google** and fails the build if the app crashes.
4. Opens each main screen with sample data and saves a screenshot to the [`demo-screenshots`](https://github.com/jeetsisotia/family-hub/tree/demo-screenshots) branch.

The screens checked:
- Home, with dues and money at a glance
- Finance dashboard
- Due calendar
- Accounts
- Card detail
- Loan detail with the EMI calendar
- Statement and SMS import
- Family tab
- More
- Health
- Data & backup
- Assistant

The screenshots were checked by eye after each run. Problems found that way and fixed:
- **Calendar mismatch.** The Finance overview and the due calendar disagreed when another member had spent on a card. Fixed: balances now count every entry on an account or card, whichever view is shown.
- **Member names wrapping.** Names on the Family tab broke over two lines because of the admin buttons. Fixed by moving those actions into a ⋮ menu.
- **Labels too long.** The QR button label and the BMI range were too long for their spaces. Both were shortened.

## 4. Firestore rules (Rules Playground)

The new rules were published on 26 Nov 2026, after the old rules were backed up. Results:

| Test | Expected | Result |
|---|---|---|
| Signed out: read a family | Denied | ✅ Denied |
| Super Admin: delete a shared entry | Allowed | ✅ Allowed |
| Someone not in the family: delete a shared entry | Denied | ✅ Denied |

## 5. Checklist for your phone

These need a real phone, a real inbox or a real Google account, so they could not run on the build server. They take about 15 minutes.

- [ ] **Sign-in:** install the new APK over the old one. Your data and sign-in are still there.
- [ ] **Accounts:** add an account with today's balance. Add a debit card linked to it. Its spends lower that account.
- [ ] **Card payments:** add a credit card and spend on it, then pay the bill from the account. Spending goes up once. The card's outstanding and the bank balance both go down.
- [ ] **Due calendar:** Mark paid a due. It turns green, leaves Home, and its reminder stops. Snooze, Reschedule and Skip this month also work.
- [ ] **SMS scan:** Finance → Scan SMS & Gmail → SMS. Your recent bank messages appear with confidence levels. Nothing is added until you tick and save.
- [ ] **Gmail:** tap Gmail and allow access on Google's screen. Your account must be a test user (see LIMITATIONS.md).
- [ ] **Statement import:** import your bank's CSV or Excel statement. Rows already added from SMS are flagged as duplicates.
- [ ] **Health Connect:** Health → connect, then allow sleep and heart rate. If a watch writes them, the Today card fills in.
- [ ] **Family and QR:** on Family, tap QR. On a second phone, use Join Existing Family → Scan the family's QR code. Approve the member and make them a Family Admin from the ⋮ menu.
- [ ] **Data & backup:** save a backup file. Delete a shared entry and restore it from Recently deleted.
- [ ] **Assistant:** ask "How much EMI is pending?" and "Switch to dark mode", then tap Apply.
- [ ] **Reduced motion:** with Android's Remove animations turned on, screens appear without movement.


## Finance redesign and universal AI (27 Nov 2026)

| Test | Result |
|---|---|
| `tools/ledger-test` (102 checks, including the dashboard, Transactions and category checks) | Passed |
| `tools/health-test` (21, including AI usage limits) | Passed |
| Builds #59 and #60 with emulator screen checks | Passed, no crash |
| Screenshots | Not inspected |
| Live Gemini, web search and Google consent | Need your own key and phone |

Full list: `docs/VERIFICATION-REPORT-2.md`.
