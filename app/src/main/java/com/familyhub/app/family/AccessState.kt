package com.familyhub.app.family

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What the signed-in person may see and change in the current family. Filled from their member record by
 * MainActivity. The security rules enforce the same levels, so this only decides what the app asks for and shows.
 */
object AccessState {
    data class Access(val perms: Map<String, String> = emptyMap(), val admin: Boolean = true) {
        val restricted get() = Permissions.restricted(perms, admin)
        fun canSee(section: String) = Permissions.canSee(perms, section, admin)
        fun canEdit(section: String) = Permissions.canEdit(perms, section, admin)
        fun canSeeItem(collection: String, type: String?) = Permissions.canSee(perms, Permissions.sectionOf(collection, type), admin)
        fun canEditItem(collection: String, type: String?) = Permissions.canEdit(perms, Permissions.sectionOf(collection, type), admin)
    }
    private val state = MutableStateFlow(Access())
    val flow: StateFlow<Access> = state
    val now: Access get() = state.value
    fun set(perms: Map<String, String>, admin: Boolean) { val a = Access(perms, admin); if (a != state.value) state.value = a }
}
