package com.familyhub.app.ui

import androidx.compose.runtime.Composable
import com.familyhub.app.data.model.FamilyGroup
import com.familyhub.app.data.model.FamilyMember
import com.familyhub.app.data.model.UserProfile
import com.familyhub.app.data.remote.FamilyRepository
import com.familyhub.app.data.remote.Rec
import com.familyhub.app.data.remote.RecordRepository
import com.familyhub.app.ui.ai.AssistantScreen
import com.familyhub.app.ui.health.HealthScreen
import com.familyhub.app.ui.hub.HubScreen
import com.familyhub.app.ui.hub.Hubs
import com.familyhub.app.ui.health.PeriodScreen
import com.familyhub.app.ui.money.AccountDetailScreen
import com.familyhub.app.ui.money.AccountsScreen
import com.familyhub.app.ui.money.CardDetailScreen
import com.familyhub.app.ui.money.CreditCardsScreen
import com.familyhub.app.ui.money.DueCalendarScreen
import com.familyhub.app.ui.money.LoanDetailScreen
import com.familyhub.app.ui.money.LoansScreen
import com.familyhub.app.ui.money.TransferScreen
import com.familyhub.app.ui.money.ImportScreen
import com.familyhub.app.ui.money.InsightsScreen
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
    /** Super Admin (the family's creator) or a Family Admin: may delete for good, reset, back up and restore. */
    val isAdmin: Boolean = isOwner,
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
val ExtraRouteIds: Set<String> = setOf(
    "profile", "families", "arrange", "assistant", "assistant_voice", "health_hub", "periods", "insights", "cards", "import", "sos",
    "home_hub", "life_hub", "reminders_hub", "arrange_home",
    "accounts_home", "loans_home", "due_calendar", "transfer", "statement_import", "data_tools", "calls", "safety",
    "transactions", "categories", "assistant_family", "ai_settings", "duplicates", "activity", "recurring"
)

/** Screens for one account, card, loan or chat: "acct:<id>", "ccard:<id>", "loan:<id>", "chat:<id>". */
private val DetailPrefixes = listOf("acct:", "ccard:", "loan:", "chat:")

fun isExtraRoute(route: String?): Boolean =
    route != null && (route in ExtraRouteIds || DetailPrefixes.any { route.startsWith(it) })

@Composable
fun ExtraRoute(route: String, env: AppEnv) {
    when {
        route.startsWith("acct:") -> { AccountDetailScreen(env, route.removePrefix("acct:")); return }
        route.startsWith("ccard:") -> { CardDetailScreen(env, route.removePrefix("ccard:")); return }
        route.startsWith("loan:") -> { LoanDetailScreen(env, route.removePrefix("loan:")); return }
        route.startsWith("chat:") -> { com.familyhub.app.ui.chat.ChatScreen(env, route.removePrefix("chat:"), env.onBack); return }
    }
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
        "arrange_home" -> ArrangeScreen(
            order = env.userDoc.stringList("homeOrder"),
            hidden = env.userDoc.stringList("homeHidden").toSet(),
            onSave = { order, hidden ->
                env.repo.updateUserDoc(env.uid, mapOf("homeOrder" to order, "homeHidden" to hidden))
                env.onBack()
            },
            onBack = env.onBack,
            all = com.familyhub.app.ui.hub.HomeCategories.map {
                com.familyhub.app.ui.navigation.SecondaryDestination(it.route, it.title, it.icon)
            },
            title = "Arrange Home",
            help = "Use the arrows to move the categories on Home up or down. Turn the switch off to hide one."
        )
        // The universal assistant; FamilyHub actions (add expenses, reminders and so on, with confirmation) stay in the
        // family assistant, which page AI buttons open directly.
        "assistant" -> com.familyhub.app.ui.ai.UniversalChatScreen(env)
        "assistant_family" -> AssistantScreen(env)
        "ai_settings" -> com.familyhub.app.ui.ai.AiSettingsScreen(env)
        "assistant_voice" -> AssistantScreen(env, startVoice = true)
        "sos" -> com.familyhub.app.ui.sos.SosScreen(env)
        "safety" -> com.familyhub.app.ui.sos.SafetyScreen(env)
        "transactions" -> com.familyhub.app.ui.money.TransactionsScreen(env)
        "categories" -> com.familyhub.app.ui.money.CategoriesScreen(env)
        "duplicates" -> com.familyhub.app.ui.money.DuplicatesScreen(env)
        "activity" -> com.familyhub.app.ui.family.ActivityTimelineScreen(env)
        "recurring" -> com.familyhub.app.ui.family.RecurringScreen(env)
        "calls" -> com.familyhub.app.ui.calls.CallsScreen(env)
        "health_hub" -> HealthScreen(env)
        "periods" -> PeriodScreen(env)
        "insights" -> InsightsScreen(env)
        "cards" -> CreditCardsScreen(env)
        "accounts_home" -> AccountsScreen(env)
        "loans_home" -> LoansScreen(env)
        "due_calendar" -> DueCalendarScreen(env)
        "transfer" -> TransferScreen(env)
        "statement_import" -> com.familyhub.app.ui.money.StatementImportScreen(env)
        "data_tools" -> com.familyhub.app.ui.settings.DataToolsScreen(env)
        "import" -> ImportScreen(env)
        else -> Hubs.screens[route]?.let { HubScreen(it, env) }
    }
}
