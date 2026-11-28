package com.familyhub.app.data.remote

import java.util.Base64
import java.util.Locale

/**
 * Chat attachments on the free Firebase plan (no Cloud Storage): a file is cut into parts of at most
 * [PART_BYTES], each kept as text in its own Firestore document (chats/{chat}/parts/{id}_{n}) that only the
 * people in the chat can read. The message carries a short description ([ChatRepository.Attachment]).
 */
object ChatMedia {
    const val PART_BYTES = 600_000
    const val MAX_PARTS = 8
    /** Largest file that can be sent (8 parts), about 4.5 MB. */
    const val MAX_BYTES = PART_BYTES * MAX_PARTS - 300_000
    const val MAX_VOICE_MS = 3 * 60_000L
    const val PHOTO_MAX_SIDE = 1280
    const val WAVE_BARS = 40
    val KINDS = setOf("photo", "voice", "file")

    fun partId(fileId: String, n: Int) = "${fileId}_$n"

    /** Cuts bytes into Firestore-sized pieces, each as Base64 text. */
    fun split(bytes: ByteArray, partBytes: Int = PART_BYTES): List<String> {
        require(bytes.isNotEmpty()) { "Nothing to send" }
        require(bytes.size <= MAX_BYTES) { "Too big to send in chat (largest ${sizeText(MAX_BYTES.toLong())})" }
        return (bytes.indices step partBytes).map { i -> Base64.getEncoder().encodeToString(bytes.copyOfRange(i, minOf(bytes.size, i + partBytes))) }
    }

    /** Puts the pieces back together, in order; fails if one is missing. */
    fun join(parts: List<String?>): ByteArray {
        require(parts.isNotEmpty() && parts.all { it != null }) { "Part of this file is missing" }
        val chunks = parts.map { Base64.getDecoder().decode(it) }
        val out = ByteArray(chunks.sumOf { it.size })
        var at = 0
        chunks.forEach { c -> System.arraycopy(c, 0, out, at, c.size); at += c.size }
        return out
    }

    fun sizeText(bytes: Long): String = when {
        bytes >= 1_000_000 -> String.format(Locale.ROOT, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> "${bytes / 1_000} KB"
        else -> "$bytes B"
    }

    fun durationText(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
    }

    /** The words shown in chat lists, notifications, search and replies for an attachment. */
    fun label(kind: String, name: String, durationMs: Long): String = when (kind) {
        "photo" -> "📷 Photo"
        "voice" -> "🎤 Voice message (${durationText(durationMs)})"
        else -> "📄 " + name.ifBlank { "File" }.take(120)
    }

    /** Loudness samples (any count) reduced to [bars] bars from 0 to 100, for the voice-message waveform. */
    fun wave(samples: List<Int>, bars: Int = WAVE_BARS): List<Int> {
        if (samples.isEmpty()) return List(bars) { 8 }
        val peak = samples.maxOrNull()?.coerceAtLeast(1) ?: 1
        return List(bars) { b ->
            val from = b * samples.size / bars
            val to = maxOf(from + 1, (b + 1) * samples.size / bars).coerceAtMost(samples.size)
            val v = samples.subList(from.coerceAtMost(samples.size - 1), to).maxOrNull() ?: 0
            (8 + 92.0 * v / peak).toInt().coerceIn(8, 100)
        }
    }

    /** A safe file name to save or open a received file with. */
    fun safeName(name: String, kind: String, mime: String): String {
        val clean = name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").trim().take(80)
        if (clean.isNotBlank() && clean.contains('.')) return clean
        val ext = when {
            kind == "photo" || mime == "image/jpeg" -> "jpg"
            kind == "voice" -> "m4a"
            mime == "application/pdf" -> "pdf"
            mime.startsWith("image/") -> mime.removePrefix("image/")
            else -> "bin"
        }
        return clean.ifBlank { kind } + "." + ext
    }

    /**
     * Whether someone's "typing" time is recent enough to show "typing…". The time comes from the server, so a
     * phone whose clock runs a little behind may see it slightly in the future: up to a minute is allowed.
     */
    fun isTyping(at: Long, now: Long, windowMs: Long = 7_000): Boolean = at > 0 && now - at in -60_000L..windowMs

    /** The line under the chat title: "Asha is typing…", "Asha and Ravi are typing…", "3 people are typing…". */
    fun typingLine(names: List<String>): String = when (names.size) {
        0 -> ""
        1 -> "${names[0]} is typing…"
        2 -> "${names[0]} and ${names[1]} are typing…"
        else -> "${names.size} people are typing…"
    }
}
