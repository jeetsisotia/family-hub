# Safety tests

Checks for `app/src/main/java/com/familyhub/app/safety/SafetyLogic.kt` (plain Kotlin, no Android code): the low-battery trigger (one alert per episode, 6-hour cooldown, simulated discharges), the automatic SMS limit, SMS wording (location link and time, medical card only when turned on), live-location upload limits (writes per hour), stale and expired shares, and the safe-arrival timer.

Nothing here sends an SMS or uses a phone.

```
kotlinc ../../app/src/main/java/com/familyhub/app/safety/SafetyLogic.kt SafetyTest.kt -include-runtime -d t.jar
java -jar t.jar
```

The last line is `ALL PASSED (n checks)` or the number of failures.
