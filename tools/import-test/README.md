# Import tests

These check the bank SMS/email reader (`money/TxnParser.kt`) and the statement import (`money/StatementParser.kt` and `money/StatementImport.kt`). They are plain Kotlin, so they run on any computer with a Kotlin compiler.

## Running them

```
M=../../app/src/main/java/com/familyhub/app/money
cp $M/TxnParser.kt $M/AlertParser.kt $M/StatementParser.kt $M/StatementImport.kt $M/Ledger.kt .
kotlinc TxnParser.kt AlertParser.kt StatementImport.kt StatementParser.kt Ledger.kt TxnTest.kt -include-runtime -d txn.jar && java -jar txn.jar
kotlinc StatementParser.kt StatementImport.kt TxnParser.kt AlertParser.kt Ledger.kt StmtTest.kt -include-runtime -d stmt.jar && java -jar stmt.jar
```

`StmtTest` also reads a small Excel file named `s.xlsx`. Its Excel rows are:

| Txn Date | Narration | Withdrawal Amt. | Deposit Amt. | Closing Balance |
|---|---|---|---|---|
| 46340 | UPI/DR/432112345678/SWIGGY/YESB/swiggy@yesb | 450 | | 101550 |
| 46341 | NEFT CR-ACME PVT LTD-SALARY NOV | | 85000 | 186550 |
| 46342 | CREDIT CARD PAYMENT XX4321 | 12601 | | 173949 |
| 46343 | ATM CASH WDL NFS/12345 | 2000 | | 171949 |

## What they cover

**SMS and email alerts**
- UPI debits, with the reference number and confidence level
- card spends, with the available limit
- credits, with the balance
- card bill payments, from both the bank side and the card side
- OTP messages, which are skipped

**Statements**
- a bank CSV with a few lines before the header, quoted cells, and separate debit and credit columns
- an Axio/Walnut export with one AMOUNT column and a DR/CR column
- a card statement whose credits end in "Cr", with dates in several formats
- an Excel file with Excel date numbers
- card payments and own transfers saved as money moved
- ATM cash moved to the Cash account
- refunds to a card, which are not counted as income
- old .xls files, which are refused with a message
