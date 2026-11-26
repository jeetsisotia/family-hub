package com.familyhub.app.core.ai

/**
 * Keeps the assistant well inside Gemini's free tier: at most [PER_MINUTE] requests a minute and [PER_DAY] a day
 * from this phone. Plain Kotlin, tested in tools/ai-test. Over the limit, the app says so instead of calling.
 */
object AiLimiter {
    const val PER_MINUTE = 8
    const val PER_DAY = 150
    private const val MINUTE = 60_000L
    private const val DAY = 24 * 60 * MINUTE

    private val sent = ArrayList<Long>()

    /** Null if a request may go now (and records it), otherwise the message to show. */
    @Synchronized
    fun tryAcquire(now: Long = System.currentTimeMillis()): String? {
        val msg = check(sent, now)
        if (msg == null) sent.add(now)
        sent.removeAll { now - it >= DAY }
        return msg
    }

    /** The decision alone, for tests. */
    fun check(times: List<Long>, now: Long): String? {
        val lastMinute = times.count { now - it in 0 until MINUTE }
        val lastDay = times.count { now - it in 0 until DAY }
        return when {
            lastDay >= PER_DAY -> "You have used today's free AI limit on this phone ($PER_DAY questions). Money questions still work offline."
            lastMinute >= PER_MINUTE -> "Too many AI questions in a minute. Please wait a moment and try again."
            else -> null
        }
    }
}
