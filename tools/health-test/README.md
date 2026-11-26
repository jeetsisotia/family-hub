# Health and AI-limit tests

Checks for `app/src/main/java/com/familyhub/app/health/HealthMath.kt` and `core/ai/AiLimiter.kt` (plain Kotlin):

- **Medicine adherence:** doses due in the last 7 days against doses marked taken. Course dates are respected, and a dose counts only after an hour.
- **Food diary:** today's calories, the 7-day average, and entries without calories.
- **AI request limit:** 8 a minute and 150 a day.

```
kotlinc ../../app/src/main/java/com/familyhub/app/health/HealthMath.kt ../../app/src/main/java/com/familyhub/app/core/ai/AiLimiter.kt HealthTest.kt -include-runtime -d t.jar
java -jar t.jar
```
