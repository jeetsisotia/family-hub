package com.familyhub.app.family

/**
 * What each family member may see and change, section by section (None / View / Edit), set by admins.
 * Kept in the member's record as "perms" (section → level). Anything not set is Edit, so families that never
 * use this keep working exactly as before. Admins always have full access. The same lists are in
 * firebase/firestore.rules, which enforces them on the server.
 */
object Permissions {
    const val NONE = "none"
    const val VIEW = "view"
    const val EDIT = "edit"
    val LEVELS = listOf(NONE, VIEW, EDIT)

    data class Section(val key: String, val label: String, val itemTypes: List<String>, val collections: List<String> = emptyList())

    val SECTIONS = listOf(
        Section("finance", "Finance", listOf("subscription", "loan", "account", "card", "debit_card", "policy", "goal", "challenge", "category", "settle", "budget_limit", "merchant_rule"), listOf("expenses")),
        Section("calendar", "Calendar", listOf("birthday"), listOf("events")),
        Section("tasks", "Tasks and shopping", listOf("reminder", "chore", "note", "habit", "meal"), listOf("tasks")),
        Section("documents", "Documents", listOf("document")),
        Section("health", "Health", listOf("health", "medicine", "vital", "food", "workout", "period")),
        Section("home", "Home and vehicles", listOf("vault", "maintenance", "vehicle", "property")),
        Section("family", "Family information", listOf("staff", "emergency", "notice", "pet", "school")),
        Section("memories", "Memories", listOf("memory")),
        Section("travel", "Travel", emptyList(), listOf("trips")),
        Section("chat", "Chat", emptyList())
    )
    private val byType: Map<String, String> = SECTIONS.flatMap { s -> s.itemTypes.map { it to s.key } }.toMap()
    private val byCollection: Map<String, String> = SECTIONS.flatMap { s -> s.collections.map { it to s.key } }.toMap()
    val KNOWN_ITEM_TYPES: List<String> get() = byType.keys.toList()

    fun label(section: String) = SECTIONS.firstOrNull { it.key == section }?.label ?: section

    /** The section a record belongs to (null: not covered by permissions, open to every member). */
    fun sectionOf(collection: String, type: String?): String? =
        if (collection == "items") type?.let { byType[it] } else byCollection[collection]

    fun level(perms: Map<String, String>, section: String?, admin: Boolean): String =
        if (admin || section == null) EDIT else perms[section]?.takeIf { it in LEVELS } ?: EDIT

    fun canSee(perms: Map<String, String>, section: String?, admin: Boolean) = level(perms, section, admin) != NONE
    fun canEdit(perms: Map<String, String>, section: String?, admin: Boolean) = level(perms, section, admin) == EDIT

    /** Someone with "None" anywhere must load data section by section (the server refuses whole collections). */
    fun restricted(perms: Map<String, String>, admin: Boolean) = !admin && perms.values.any { it == NONE }

    /** Item types this person may load (for section-by-section loading). */
    fun visibleItemTypes(perms: Map<String, String>, admin: Boolean): List<String> =
        SECTIONS.filter { canSee(perms, it.key, admin) }.flatMap { it.itemTypes }

    /** Whether a whole collection (events, expenses, tasks, trips) may be loaded. */
    fun canLoadCollection(perms: Map<String, String>, collection: String, admin: Boolean) =
        collection == "items" || canSee(perms, byCollection[collection], admin)

    /** Lists of at most 30 values (Firestore's limit for "in" queries). */
    fun chunks(types: List<String>): List<List<String>> = types.distinct().chunked(30)

    /** Fields to save on a member when an admin changes access (keeps the older "can edit Finance" flag in step). */
    fun memberFields(perms: Map<String, String>): Map<String, Any> {
        val clean = perms.filter { (k, v) -> SECTIONS.any { it.key == k } && v in LEVELS && v != EDIT }
        return mapOf("perms" to clean, "canEditFinance" to (clean["finance"] == null))
    }

    /** One line for the member list, e.g. "No Health · View only: Finance". */
    fun summary(perms: Map<String, String>): String {
        val none = SECTIONS.filter { perms[it.key] == NONE }.map { it.label }
        val view = SECTIONS.filter { perms[it.key] == VIEW }.map { it.label }
        return listOfNotNull(
            none.takeIf { it.isNotEmpty() }?.let { "No " + it.joinToString(", ") },
            view.takeIf { it.isNotEmpty() }?.let { "View only: " + it.joinToString(", ") }
        ).joinToString(" · ")
    }
}
