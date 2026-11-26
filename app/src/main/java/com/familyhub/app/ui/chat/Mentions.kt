package com.familyhub.app.ui.chat

/**
 * @mentions in the family chat: "@Asha can you check?" Typing "@" suggests family members; a mentioned
 * person gets a notification even if the chat is muted. Mentions are plain text in the message, so they need
 * no extra database fields or reads. Plain Kotlin, unit-tested in tools/chat-test.
 */
object Mentions {
    /** Letters, digits and the vowel signs of Indian scripts (so "अमित" keeps its "ि"). */
    private fun wordChar(c: Char): Boolean =
        c.isLetterOrDigit() || Character.getType(c).let { it == Character.NON_SPACING_MARK.toInt() || it == Character.COMBINING_SPACING_MARK.toInt() }

    /** The word used after "@": the first part of a person's name, letters and digits only ("Asha Rao" → "Asha"). */
    fun handle(name: String): String =
        name.trim().split(Regex("\\s+")).firstOrNull().orEmpty().filter { wordChar(it) }

    /**
     * The name being typed after a final "@" (at the start or after a space), or null when the text does not end
     * in a mention: "Hi @As" → "As", "Hi @" → "", "mail@x" → null.
     */
    fun partial(text: String): String? {
        val at = text.lastIndexOf('@')
        if (at < 0) return null
        if (at > 0 && !text[at - 1].isWhitespace()) return null
        val tail = text.substring(at + 1)
        return if (tail.all { wordChar(it) }) tail else null
    }

    /** Replaces the "@As" being typed with "@Asha " (keeps everything before it). */
    fun complete(text: String, name: String): String {
        val at = text.lastIndexOf('@')
        val h = handle(name)
        if (at < 0 || h.isEmpty()) return text
        return text.substring(0, at) + "@" + h + " "
    }

    /** Members whose handle starts with what is being typed (case ignored), at most [max]. */
    fun suggestions(partial: String, names: List<String>, max: Int = 5): List<String> =
        names.filter { handle(it).isNotEmpty() && handle(it).startsWith(partial, ignoreCase = true) }
            .distinctBy { handle(it).lowercase() }
            .take(max)

    /** Where each "@Handle" for a known person sits in [text], for bold highlighting. */
    fun ranges(text: String, handles: Collection<String>): List<IntRange> {
        val known = handles.filter { it.isNotEmpty() }.map { it.lowercase() }.toSet()
        if (known.isEmpty()) return emptyList()
        val out = ArrayList<IntRange>()
        Regex("(?<![\\p{L}\\p{M}\\p{N}])@([\\p{L}\\p{M}\\p{N}]+)").findAll(text).forEach { m ->
            if (m.groupValues[1].lowercase() in known) out.add(m.range)
        }
        return out
    }

    /** True when [text] mentions the person called [name] ("@asha" counts for "Asha Rao"). */
    fun mentions(text: String, name: String): Boolean {
        val h = handle(name)
        return h.isNotEmpty() && ranges(text, listOf(h)).isNotEmpty()
    }
}
