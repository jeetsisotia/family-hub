# Ledger tests

Checks for the money engine (`app/src/main/java/com/familyhub/app/money/Ledger.kt`), which is plain Kotlin with no Android code, so it runs on any computer with a Kotlin compiler:

```
M=../../app/src/main/java/com/familyhub/app/money
cp $M/Ledger.kt $M/MerchantRules.kt $M/FinanceMonth.kt $M/Recategorize.kt $M/Duplicates.kt .
kotlinc Ledger.kt MerchantRules.kt FinanceMonth.kt Recategorize.kt Duplicates.kt LedgerTest.kt -include-runtime -d t.jar
java -jar t.jar
```

The last line is `ALL PASSED` or the number of failures. They cover:

- account balances with opening dates, transfers and debit cards
- credit card statement, outstanding, amount due, minimum and available limit
- the EMI formula and the loan schedule (paid, overdue)
- dues for the calendar, including saved card bills
- what counts as spending
- duplicate payments across SMS and email
- one category for the same merchant (matching names, what is offered, remembered rules)
- shared totals (spent, refunds, income, savings, categories) used by every screen
- finding the same payment saved more than once
- moving existing transactions to another category (picked entries, rename, delete with move), with nothing else changed and nothing copied or lost
- suspected duplicates with reasons, confidence and the entry to keep; recurring payments, different shops, transfers and entries by different people handled
