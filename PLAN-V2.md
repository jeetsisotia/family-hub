# Family Hub v2 plan

Working plan for the v2 transformation: finance, health, home, roles, data tools, AI and a UI refresh.
Each phase ships as its own commit and must build green before the next one starts.

## Decisions (from Jeet)

- **Sign-in stays exactly as it is:** Google sign-in plus the optional fingerprint lock. There is no PIN.
- **Roles:** whoever creates a family is that family's **Super Admin**. They can make members **Family Admins**. Everyone else is a **Member**.
- **Firestore rules:** they may be updated. Back up the current rules from the Firebase console first, then publish and test.
- **Safety:** the backup branch `backup/before-v2-2026-11-26` was made from `main` at 9676884 before any v2 change.

## Ground rules

- Keep all existing data. New fields are added next to the old ones. Nothing is migrated destructively, and older records keep working (for example, a loan saved with only an outstanding amount and an EMI).
- A reminder or alert is never proof of payment. Only a payment the person confirms, or a matched bank or card entry, marks a due as paid.
- The app never asks for bank passwords, CVVs or OTPs. Only the last four digits of an account or card are kept.
- Axio: there is no public API, so the import is CSV/Excel only. No private APIs are reverse-engineered.
- Health: values come from Health Connect or from what the person types in. Nothing is made up and there are no AI diagnoses. Health data never goes to the AI.
- Deleting or resetting data needs a confirmation and an admin. The chatbot cannot run code or arbitrary database operations; it can only make previewed, confirmable, undoable changes to settings.

## Money engine (`money/Ledger.kt`)

- The engine is pure Kotlin. Its tests are in `tools/ledger-test`.
- **Spending** is EXPENSE, plus BILL and EMI once they are paid.
- **Income** is INCOME, except refunds marked "not income".
- **Moves:** TRANSFER, CARD_PAYMENT and ADJUSTMENT are never income or spending. SAVING is money kept, not spent.
- **Credit cards:**
  - A purchase on a credit card is spending on the day it is made.
  - Paying the card bill lowers what is owed and the bank balance. It is never counted as spending again.
  - A card bill saved as an entry also never counts again.
- **Accounts:**
  - Balance = opening balance (as at the start of its date) + money in − money out.
  - A debit card spend counts against the debit card's linked account.
- **Cards:**
  - The statement and due dates come from the card's days. Without a due day, the bill is due 18 days after the statement.
  - Minimum due is 5% of the bill, at least ₹200, or the minimum given in the bank's alert.
- **Loans:**
  - EMI = P·r·(1+r)^n / ((1+r)^n − 1).
  - The app builds the full schedule. Each instalment is PAID, OVERDUE, DUE or UPCOMING.
- **Dues** (feeding the due calendar, the reminders and Home):
  - Saved bills and EMIs
  - Card bills
  - Loan instalments
  - Subscription renewals
  - Insurance premiums
  - Paid and snoozed dues stop reminding.
- **Duplicates:** two entries are the same payment when they have the same direction and amount, are within 1.5 days of each other, and share a reference number, a card or account ending, or a similar merchant.

## Phases

1. **Audit and backup.** Done.
2. **Finance core.** Includes:
   - opening balances, cash and wallets, debit cards, and card opening dues and rewards
   - loan type, amount, rate, tenure and first EMI date, with the EMI worked out
   - custom categories and subcategories
   - transfers and card payments; merchant, account, member and receipt-photo fields
   - fields that show or hide depending on the entry type
3. **Finance screens.** Includes:
   - dashboard with Mine and Family views
   - accounts and account detail with statement matching
   - credit cards and card detail (pay in full, the minimum or any amount; history; spend by category; rewards)
   - loans and the EMI calendar
   - the month due calendar with Mark paid, Snooze, Reschedule, Edit, Delete or skip, payment proof and view payment
   - transfers
   - reminders driven by the same dues
4. **SMS, Gmail and statement import.** Includes:
   - balances, account and card matching, and card-payment detection
   - confidence levels with confirmation before saving
   - duplicates checked across all sources
   - CSV/Excel import with column mapping (including Axio exports)
5. **Health redesign.** Includes:
   - Health Connect activity, steps, distance, calories, sleep, heart rate and weight
   - BMI, rings, charts and goals
   - week, month and year views
   - manual records
6. **Home, navigation, roles and data tools.** Includes:
   - bottom bar of Home, Finance, Health, Family and More
   - Home with due actions
   - Super Admin, Family Admin and Member roles
   - export, import, backup, restore and resets (admins only, with confirmation)
   - Firestore rules
7. **AI.** Includes:
   - answers worked out on the phone from real data (spend, top category, EMIs pending, next card due, savings, family spend, duplicates)
   - customization of categories, widgets, fields, reminders and theme, with preview, confirm and undo
8. **Release.** Includes:
   - tests and test-screen photos
   - the APK
   - install and setup notes
   - a feature report and a testing report
   - known limits and the integrations that need outside approval
