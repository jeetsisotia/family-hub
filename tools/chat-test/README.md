# Chat tests

Checks for @mentions in the family chat (`app/src/main/java/com/familyhub/app/ui/chat/Mentions.kt`), which is plain Kotlin:

```
cp ../../app/src/main/java/com/familyhub/app/ui/chat/Mentions.kt .
kotlinc Mentions.kt ChatTest.kt -include-runtime -d t.jar
java -jar t.jar
```

The last line is `ALL PASSED` or the number of failures.
