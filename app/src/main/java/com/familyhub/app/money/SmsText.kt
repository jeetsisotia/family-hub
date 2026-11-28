package com.familyhub.app.money

/**
 * Bank and card alerts that the person chose to give the app: shared from their Messages app ("Share" → Family Hub)
 * or pasted. The app has no permission to read the SMS inbox (Android and Google Play Protect treat that as
 * high-risk for apps installed outside the Play Store), so only text the person picks ever reaches it.
 * Pure: unit-tested in tools/import-test.
 */
object SmsText {
    /** A sender used for shared text, so parsers that look at the sender treat it as a bank alert. */
    const val SENDER = "SHARED"
    const val MAX_CHARS = 200_000

    /** Splits what was shared or pasted into separate messages (blank lines, or a new message starting with a date). */
    fun messages(text: String): List<String> {
        val t = text.take(MAX_CHARS).replace("\r\n", "\n").replace('\r', '\n')
        val blocks = t.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotBlank() }
        // Messages apps that share several texts at once often put each on its own line with no blank line between.
        return blocks.flatMap { b ->
            val lines = b.lines().map { it.trim() }.filter { it.isNotBlank() }
            val each = lines.size > 1 && lines.all { it.length >= 25 && TxnParser.parse(it, SENDER, 0L, "SMS") != null }
            if (each) lines else listOf(b)
        }.distinct()
    }

    /** A stable key per message text, so sharing the same SMS twice is recognised. */
    fun keyOf(message: String): String {
        val norm = message.lowercase().replace(Regex("\\s+"), " ").trim()
        var h = 1125899906842597L
        for (ch in norm) h = 31 * h + ch.code
        return "shared-" + java.lang.Long.toHexString(h)
    }

    data class Result(val txns: List<ParsedTxn>, val alerts: List<ParsedAlert>, val unread: Int)

    fun parse(text: String, now: Long): Result {
        val txns = ArrayList<ParsedTxn>()
        val alerts = ArrayList<ParsedAlert>()
        var unread = 0
        for ((i, m) in messages(text).withIndex()) {
            val key = keyOf(m)
            val txn = TxnParser.parse(m, SENDER, now - i * 60_000L, "SMS")
            if (txn != null) { txns.add(txn.copy(key = key)); continue }
            val alert = AlertParser.parse(m, SENDER, now - i * 60_000L, "SMS", key)
            if (alert != null) alerts.add(alert) else unread++
        }
        return Result(txns.distinctBy { it.key }, alerts.distinctBy { it.key } + AlertParser.accounts(txns), unread)
    }
}
