package com.familyhub.app.ui

import androidx.compose.runtime.Composable
import com.familyhub.app.data.model.FamilyGroup
import com.familyhub.app.data.model.FamilyMember
import com.familyhub.app.data.model.UserProfile
import com.familyhub.app.data.remote.FamilyRepository
import com.familyhub.app.data.remote.Rec
import com.familyhub.app.data.remote.RecordRepository
import com.familyhub.app.ui.profile.ArrangeScreen
import com.familyhub.app.ui.profile.FamiliesScreen
import com.familyhub.app.ui.profile.ProfileScreen
import kotlinx.coroutines.CoroutineScope

/** Everything the newer screens need from the main screen, in one place. */
data class AppEnv(
    val uid: String,
    val profile: UserProfile,
    val userDoc: Rec,
    val familyId: String,
    val family: FamilyGroup,
    val members: List<FamilyMember>,
    val memberNames: List<String>,
    val isOwner: Boolean,
    /** Family plus "Only me" records, keyed by collection (items, events, expenses, tasks, trips). */
    val data: Map<String, List<Rec>>,
    val repo: RecordRepository,
    val familyRepository: FamilyRepository,
    val scope: CoroutineScope,
    val onBack: () -> Unit,
    val navigate: (String) -> Unit
) {
    val familyIds: List<String>
        get() = (profile.familyIds + familyId).filter { it.isNotBlank() }.distinct()
}

fun Rec.stringList(key: String): List<String> = (this[key] as? List<*>)?.mapNotNull { it as? String }.orEmpty()

/** Routes handled here instead of by the generic record screen. */
val ExtraRouteIds: Set<String> = setOf("profile", "families", "arrange")

@Composable
fun ExtraRoute(route: String, env: AppEnv) {
    when (route) {
        "profile" -> ProfileScreen(
            uid = env.uid,
            profile = env.profile,
            userDoc = env.userDoc,
            familyIds = env.familyIds,
            repo = env.repo,
            onBack = env.onBack
        )
        "families" -> FamiliesScreen(
            uid = env.uid,
            activeFamilyId = env.familyId,
            familyIds = env.familyIds,
            repo = env.repo,
            scope = env.scope,
            onSwitch = { id -> env.familyRepository.switchActiveFamily(env.uid, id) },
            onCreate = { name ->
                env.familyRepository.createFamilyGroup(env.profile.copy(familyIds = env.familyIds), name)
                    .fold(onSuccess = { null }, onFailure = { it.message ?: "Could not create the family." })
            },
            onJoin = { code ->
                env.familyRepository.joinFamilyByCode(env.profile.copy(familyIds = env.familyIds), code, "Family Member")
                    .fold(onSuccess = { null }, onFailure = { it.message ?: "Could not join with that code." })
            },
            onBack = env.onBack
        )
        "arrange" -> ArrangeScreen(
            order = env.userDoc.stringList("moduleOrder"),
            hidden = env.userDoc.stringList("hiddenModules").toSet(),
            onSave = { order, hidden ->
                env.repo.updateUserDoc(env.uid, mapOf("moduleOrder" to order, "hiddenModules" to hidden))
                env.onBack()
            },
            onBack = env.onBack
        )
    }
}
