package com.familyhub.app.data.remote

/** Offline, sync and conflict rules. Pure, unit-tested in tools/sync-test. */
object SyncMath {
    /** Fields that change on every save and say nothing about what the person edited. */
    private val META = setOf("id", PRIVATE_FLAG, "updatedAt", "updatedByUid", "updatedByName", "createdAt", "createdByUid", "createdByName")

    private fun ms(v: Any?): Long = when (v) {
        is Number -> v.toLong()
        is java.util.Date -> v.time
        // A Firestore Timestamp (read without depending on Firebase, so this stays testable on its own).
        else -> runCatching { (v?.javaClass?.getMethod("toDate")?.invoke(v) as? java.util.Date)?.time }.getOrNull() ?: 0L
    }

    /** Field names whose values differ between two versions of a record (metadata ignored). */
    fun changedFields(a: Map<String, Any?>, b: Map<String, Any?>): List<String> =
        (a.keys + b.keys).filter { it !in META && a[it] != b[it] }.distinct().sorted()

    /**
     * While a form was open, did someone else save this record? Then saving would overwrite their change.
     * [opened] is the record as the form started, [live] is the record now.
     */
    fun conflict(opened: Map<String, Any?>, live: Map<String, Any?>?, myUid: String): List<String> {
        if (live == null) return emptyList()
        if (ms(live["updatedAt"]) <= ms(opened["updatedAt"])) return emptyList()
        val by = live["updatedByUid"] as? String
        if (by != null && by == myUid) return emptyList()
        return changedFields(opened, live)
    }

    /** The other person's change kept, then the fields this person edited in the form put on top. */
    fun merge(opened: Map<String, Any?>, mine: Map<String, Any?>, live: Map<String, Any?>): Map<String, Any?> {
        val out = HashMap(live)
        for (k in mine.keys) if (opened[k] != mine[k]) out[k] = mine[k]
        return out
    }

    enum class Level { SAVED, SYNCING, OFFLINE_PENDING, OFFLINE }

    fun level(online: Boolean, pending: Int): Level = when {
        !online && pending > 0 -> Level.OFFLINE_PENDING
        !online -> Level.OFFLINE
        pending > 0 -> Level.SYNCING
        else -> Level.SAVED
    }

    fun text(online: Boolean, pending: Int): String = when (level(online, pending)) {
        Level.OFFLINE_PENDING -> "Offline · $pending ${if (pending == 1) "change" else "changes"} will sync when you're back online"
        Level.OFFLINE -> "Offline · showing what's saved on this phone"
        Level.SYNCING -> "Syncing $pending ${if (pending == 1) "change" else "changes"}…"
        Level.SAVED -> "All changes saved"
    }

    /** After a restore: which restored entries the server does not have (collection to ids). */
    fun missingAfterRestore(restored: Map<String, Set<String>>, onServer: Map<String, Set<String>>): Map<String, Set<String>> =
        restored.mapValues { (c, ids) -> ids - onServer[c].orEmpty() }.filterValues { it.isNotEmpty() }
}
