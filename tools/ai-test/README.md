# Assistant question tests

These check which answer the assistant gives to a question (`ui/ai/MoneyAnswers.kt`). Each test is a question and the answer it should get, for example "How much EMI is pending?" gets the EMI answer. A request to add or log something must go to the assistant, not to a money answer.

## Running them

```
python3 extract.py
kotlinc Regexes.kt AiTest.kt -include-runtime -d a.jar && java -jar a.jar
```

`extract.py` copies the question patterns from `MoneyAnswers.kt` into `Regexes.kt`, so the tests always use the app's current patterns. The last line printed is `ALL PASSED` or the number of failures.
