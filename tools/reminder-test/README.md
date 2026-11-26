# Reminder tests

Checks for `app/src/main/java/com/familyhub/app/work/ReminderRules.kt` (plain Kotlin, no Android code), which every repeating date in the app now uses (reminders, calendar events, birthdays, renewals and the alarm planner):

- repeats: daily, weekly, monthly, quarterly, yearly and every few days
- month ends and 29 February: the old code is kept in the test to show the drift it had
- times typed or picked, and "All day" when there is no time
- early alerts
- a repeating reminder marked done for one day
- re-planning every hour without duplicate alarms

```
kotlinc ../../app/src/main/java/com/familyhub/app/work/ReminderRules.kt ReminderTest.kt -include-runtime -d t.jar
java -jar t.jar
```

The last line is `ALL PASSED (n checks)` or the number of failures.
