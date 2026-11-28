package com.familyhub.app.update

/** Pure rules for the update checker (unit-tested in tools/update-test). */
object UpdateMath {
    const val CHECK_EVERY_MS = 12L * 60 * 60 * 1000

    /** Release tags are "build-<number>"; the number is the app's versionCode for that build. */
    fun buildNumber(tag: String): Int? = Regex("^build-(\\d+)$").find(tag.trim())?.groupValues?.get(1)?.toIntOrNull()

    fun isNewer(tag: String, currentVersionCode: Int): Boolean = (buildNumber(tag) ?: 0) > currentVersionCode

    /** The installable file among a release's files (the first .apk). */
    fun pickApk(names: List<String>): String? = names.firstOrNull { it.endsWith(".apk", ignoreCase = true) }

    fun dueToCheck(lastCheckMs: Long, nowMs: Long): Boolean = lastCheckMs <= 0 || nowMs - lastCheckMs >= CHECK_EVERY_MS || nowMs < lastCheckMs

    fun sizeText(bytes: Long): String = when {
        bytes >= 1_000_000 -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> "${bytes / 1_000} KB"
        else -> "$bytes B"
    }

    /** Release notes trimmed to something readable on a phone: no empty lines, no Co-Authored/Session lines, at most [max] lines. */
    fun notes(body: String, max: Int = 12): String = body.lines()
        .map { it.trimEnd() }
        .filter { it.isNotBlank() && !it.startsWith("Co-Authored-By") && !it.startsWith("Claude-Session") }
        .take(max).joinToString("\n")

    /** "sha256:abc…" → "abc…"; other forms are shown as given. */
    fun checksum(digest: String?): String? = digest?.takeIf { it.isNotBlank() }?.removePrefix("sha256:")
}
