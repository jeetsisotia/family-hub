# Family Hub 2.0: feature report

Status key:
- **Done:** built and working in the app.
- **Partly:** works, with the gap described.
- **Not done:** not built, with the reason.

Everything listed as Done is a working screen on real data, not a placeholder. Screenshots of each screen, taken with sample data by the automatic emulator test, are on the [`demo-screenshots`](https://github.com/jeetsisotia/family-hub/tree/demo-screenshots) branch.

## 1. Existing app kept

| Item | Status | Notes |
|---|---|---|
| Backup before changes | Done | Branch `backup/before-v2-2026-11-26`, plus the old Firestore rules in `firebase/` |
| Existing data kept | Done | New fields sit next to old ones, and nothing was migrated destructively. Old loans, cards and entries still open and count. |
| Sign-in | Done, unchanged | Google sign-in plus an optional fingerprint lock (your choice: "keep exactly as today") |
| Family invite | Done, improved | Invite code as before, plus a **QR code** to show and a **Scan QR** button when joining. An admin still approves every join. |

## 2. Finance

| Item | Status | Where |
|---|---|---|
| Dashboard | Done | Finance tab. **Mine** and **Family (shared)** views, showing:<br>• bank, cash, income, spending and savings %<br>• card outstanding, loans outstanding, EMIs due, card bills due and total liabilities |
| Bank accounts | Done | Finance → Accounts:<br>• savings, current, cash and wallets<br>• opening balance and date<br>• money in and out, history per account<br>• statement import<br>• **Match** to the bank's balance (reconcile) |
| Transfers | Done | Finance → Accounts → Transfer. A transfer, or a card bill payment, is never counted as income or spending. |
| Credit cards | Done | Finance → Credit cards, a separate section:<br>• bank and nickname, last 4 digits only<br>• limit, statement day, due day<br>• outstanding, available credit, minimum due<br>• statements history and spending by category<br>• rewards<br>• pay in full, the minimum or any amount |
| Card rule | Done, tested | A card purchase is spending on the day you buy. Paying the bill lowers what you owe and your bank balance, and is not counted again. |
| Debit cards | Done | Linked to an account. Spends reduce that account. Imports match cards by their last 4 digits. |
| Loans and EMIs | Done | Finance → Loans:<br>• personal, home, vehicle, education, consumer durable, card EMI or custom<br>• EMI worked out from principal, rate and tenure<br>• full schedule marked paid, overdue, due or upcoming<br>• outstanding principal, instalments left and interest payable<br>• tap an instalment to pay it |
| Due calendar | Done | Finance → Due calendar.<br>Covers card bills, EMIs, insurance premiums, rent, electricity, mobile and other repeating bills, and subscriptions.<br>Each due has **Mark paid** (full, minimum or any amount, and from which account), **Snooze**, **Reschedule**, **Edit**, **Delete** or **Skip this month**, **Payment proof** (photo) and **View payment**. |
| Paid updates everything | Done | Marking a due paid saves a real payment, updates the dashboard and Home, and stops its reminder. |
| Categories | Done | Money categories: add, rename, remove, and subcategories under any category. The suggested list (Food, Groceries, Shopping and so on) is built in. |
| Entry fields | Done | Date, amount, merchant, payment method, account, card, category, subcategory, description, family member and receipt photo |

## 3. Automatic tracking

| Item | Status | Notes |
|---|---|---|
| Bank SMS | Done | Reads bank, card, UPI and loan messages on the phone. It finds:<br>• amount, date, merchant, debit or credit<br>• account or card, balance and available limit<br>• reference number, card payments, EMIs and bill dues<br>Each find gets a confidence level. You tick what to add; unclear and duplicate finds start unticked. |
| Gmail | Done, needs Google approval to share | Read-only Gmail access through Google's own consent screen. See [LIMITATIONS.md](LIMITATIONS.md). |
| Duplicates | Done, tested | The same payment seen in SMS, Gmail and a statement is added once. A card payment seen from both the bank side and the card side counts as one. |
| Reminders are not payments | Done | A bill reminder or due alert never marks anything paid. |
| No passwords or OTPs | Done | The app never asks for bank passwords, CVVs or OTPs. OTP messages are skipped. |
| Axio | Done (CSV/Excel) | Axio has no public API, so you export from Axio and import the CSV or Excel file. Bank statements (CSV or .xlsx) import the same way, with column matching and duplicate checks. |

## 4. Health

| Item | Status | Notes |
|---|---|---|
| Health dashboard | Done | Health tab:<br>• **Today** card with Move (active calories), Steps and Exercise rings<br>• distance, sleep, heart rate, weight, BMI and water |
| Trends | Done | Week, month and year charts for steps, sleep and active calories |
| Health Connect | Done | Steps (needed to connect) plus distance, calories, exercise, sleep, heart rate, resting heart rate and weight, if you allow them. Data stays on the phone. |
| Steps without a watch | Done | The phone's own step sensor, with a goal |
| Manual records | Done | Vitals (weight, blood pressure, sugar and so on), workouts, water, habits, medicines with reminders, periods, and doctors and reports with photos |
| Family health | Done | Family steps board, plus the blood group and details each member chooses to share |
| No made-up values | Done | A missing reading shows "—". Step-based distance and calories are labelled "(est.)". BMI uses the standard WHO bands and says it is not a diagnosis. There is no AI diagnosis, and health data is never sent to the AI. |
| Nutrition | Partly | There is a meal planner, but no food or calorie diary |
| Health goals | Partly | Step and water goals. Other goals go in the general Goals section. |

## 5. Home

| Item | Status | Notes |
|---|---|---|
| Greeting, profile and family switch | Done | Tap your picture for profile, families and settings |
| Family View and My View | Done | Switches Home's numbers and categories |
| Money at a glance | Done | In accounts, spent this month and card dues for the chosen view. Tap to open Finance. |
| Health at a glance | Done | Steps, sleep and active calories today. Tap to open Health. |
| Dues with actions | Done | Shows the next 10 days. Each one has Mark paid, Snooze, Reschedule, Edit and Delete. Paid dues leave the list straight away. |
| Quick categories | Done | Arrange or hide them with **Arrange** |

## 6. Data and roles

| Item | Status | Notes |
|---|---|---|
| Roles | Done | **Super Admin** is whoever created the family. They can make members **Family Admins** (Family → member → ⋮). Everyone else is a **Member**. The Firestore rules enforce this on the server too. |
| Safe delete | Done | Deleting a shared entry moves it to **Recently deleted**. Admins can restore it, or delete it for good. |
| Backup and export | Done | Data & backup → Save backup file. It is a JSON file of every entry you can see. |
| Restore and import | Done | Adds back missing entries from a backup file and never overwrites existing ones |
| Resets | Done | Options:<br>• Finance<br>• my health records<br>• one kind (for example Reminders, Credit cards or Shopping)<br>• all shared data<br>A backup file is always saved first, and you type RESET to confirm. Admins only. |

## 7. AI

| Item | Status | Notes |
|---|---|---|
| Money questions | Done | Answered on the phone from your own entries, with the same rules as the Finance dashboard. It handles:<br>• how much did I spend<br>• top category<br>• EMIs pending<br>• next card bill<br>• savings<br>• family spending<br>• dues and balances<br>• uncategorised spends<br>• duplicates |
| Health questions | Done | Logging and progress are understood on the phone. Health data is never sent to the AI. |
| Safe changes by chat | Done | "Add a category called Pets", "add subcategory Vet under Pets", "set the family budget to 80k" (admins only), "switch to dark mode". Each one shows a card first, and nothing changes until you tap it. Each change can be undone in the app. |
| Bigger changes by chat | Not done, on purpose | The chat cannot change the app's code or run database commands. Your brief asked for that limit. New screens, widgets and reports are built as code changes in the repository (like this release) and arrive as a new APK. |

## 8. Look and feel

| Item | Status | Notes |
|---|---|---|
| Bottom bar | Done | Home, Finance, Health, Family, More |
| Light and dark | Done | Follows the phone, or pick **Light** or **Dark** in Family → Settings → Appearance (or ask the assistant) |
| Reduced motion | Done | With Android's "Remove animations" turned on, cards, rings and charts appear without movement |
| Design | Done | Rounded glass cards, one colour per meaning (green for money in and steps, red for overdue and move, blue for actions), ring and bar charts |

## 9. Reminders

| Item | Status | Notes |
|---|---|---|
| Money reminders | Done | EMIs, card bills, premiums, utility bills, subscriptions and custom bills, worked out from the same dues as the calendar. Paid or snoozed dues stop reminding. |
| Other reminders | Done | Medicines (with a Taken button), events, birthdays, renewals and custom reminders, with repeats |
| After a restart | Done | Reminders are set again when the phone restarts or the app updates |

## 10. Security

| Item | Status | Notes |
|---|---|---|
| Family data kept apart | Done | Firestore rules: only a family's approved members can read it. Only admins can delete for good. "Only me" entries are readable only by their owner. Private documents are readable by their owner, the people they chose and the Super Admin. Families cannot be listed; joining looks up one invite code. |
| Sensitive data | Done | Health data, ID numbers and notes never go to the AI. Only the last 4 digits of cards and accounts are kept. |
| Network | Done | HTTPS only (Firebase and Google APIs). AI calls time out after 45 seconds with a clear message, and another model is tried when the first is busy. Firestore keeps working offline and syncs when the phone is back online. |
