package com.familyhub.app.family

/**
 * Document expiry status and the reminder window each document chooses. Plain Kotlin, tested in tools/phase1-test.
 */
object DocExpiry {
    enum class Status(val label: String) { EXPIRED("Expired"), DUE_SOON("Expiring soon"), ACTIVE("Active"), NO_DATE("No expiry date"), RENEWED("Renewed") }

    const val DEFAULT_WINDOW_DAYS = 30
    private const val DAY = 86_400_000L

    /** "30 days before" -> 30; blank -> the default. */
    fun windowDays(r: Map<String, Any?>): Int =
        (r["remindBefore"] as? String).orEmpty().filter(Char::isDigit).toIntOrNull()?.coerceIn(1, 365) ?: DEFAULT_WINDOW_DAYS

    private fun startOfDayUtcAgnostic(t: Long, tzOffset: Long) = ((t + tzOffset) / DAY) * DAY - tzOffset

    /** Whole days from today to the expiry (negative when past), in the phone's time zone. */
    fun daysLeft(expiry: Long, now: Long, tzOffset: Long): Int =
        ((startOfDayUtcAgnostic(expiry, tzOffset) - startOfDayUtcAgnostic(now, tzOffset)) / DAY).toInt()

    fun status(r: Map<String, Any?>, now: Long, tzOffset: Long = 0L): Status {
        val exp = (r["expiryDateMillis"] as? Number)?.toLong() ?: return Status.NO_DATE
        val left = daysLeft(exp, now, tzOffset)
        if ((r["renewalStatus"] as? String).orEmpty().equals("Renewed", true) && left < windowDays(r)) return Status.RENEWED
        return when {
            left < 0 -> Status.EXPIRED
            left <= windowDays(r) -> Status.DUE_SOON
            else -> Status.ACTIVE
        }
    }

    data class Summary(val expired: Int, val dueSoon: Int, val active: Int, val noDate: Int, val next: List<Pair<Map<String, Any?>, Int>>)

    /** Counts and the next ones to act on (expired first, then soonest), for the Documents summary card. */
    fun summary(docs: List<Map<String, Any?>>, now: Long, tzOffset: Long = 0L, max: Int = 5): Summary {
        val live = docs.filter { it["isDeleted"] != true }
        val st = live.associateWith { status(it, now, tzOffset) }
        val next = live.filter { st[it] == Status.EXPIRED || st[it] == Status.DUE_SOON }
            .map { it to daysLeft((it["expiryDateMillis"] as Number).toLong(), now, tzOffset) }
            .sortedBy { it.second }.take(max)
        return Summary(st.count { it.value == Status.EXPIRED }, st.count { it.value == Status.DUE_SOON },
            st.count { it.value == Status.ACTIVE || it.value == Status.RENEWED }, st.count { it.value == Status.NO_DATE }, next)
    }
}
