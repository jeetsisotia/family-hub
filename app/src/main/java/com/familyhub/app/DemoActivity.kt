package com.familyhub.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import com.familyhub.app.core.theme.FamilyHubTheme
import com.familyhub.app.data.model.FamilyGroup
import com.familyhub.app.data.model.FamilyMember
import com.familyhub.app.data.model.UserProfile
import com.familyhub.app.data.remote.FamilyRepository
import com.familyhub.app.data.remote.PRIVATE_FLAG
import com.familyhub.app.data.remote.Rec
import com.familyhub.app.data.remote.RecordRepository
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.ExtraRoute
import com.familyhub.app.ui.isExtraRoute
import com.familyhub.app.ui.ai.AssistantScreen
import com.familyhub.app.ui.common.dayKey
import com.familyhub.app.ui.modules.DAY_MS
import com.familyhub.app.ui.modules.ModuleByRoute
import com.familyhub.app.ui.modules.ModuleScreen
import com.familyhub.app.ui.modules.noonToday
import com.familyhub.app.ui.navigation.MoreHubGrid
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Test screen used by the automatic emulator check after every build. It opens one screen with
 * made-up sample data (nothing is read from or written to anyone's account), so each screen can be
 * checked for crashes and photographed without signing in.
 */
@AndroidEntryPoint
class DemoActivity : FragmentActivity() {

    @Inject lateinit var familyRepository: FamilyRepository
    @Inject lateinit var recordRepository: RecordRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        // Test runs only (debug builds): "store=emulator" points this screen at a local Firestore emulator on the
        // computer running the test (10.0.2.2), so a change can really be saved, read back and undone without
        // touching anyone's account. "store=offline" points it at a database that cannot be reached, to check that
        // nothing is ever shown as done without the server. Must run before Firestore is first used.
        val store = intent.getStringExtra("store")?.takeIf { BuildConfig.DEBUG }
        if (store != null) {
            try {
                com.google.firebase.firestore.FirebaseFirestore.getInstance()
                    .useEmulator("10.0.2.2", if (store == "emulator") 8080 else 9)
            } catch (e: Exception) { android.util.Log.w("DemoActivity", "useEmulator: ${e.message}") }
        }
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // The emulator test names each screen by its real route; "dark:" and "solid:" prefixes are allowed.
        val asked = intent.getStringExtra("route") ?: "health_hub"
        // "dark:" shows the screen in the dark theme, "solid:" with Reduce transparency (not saved).
        val dark = asked.startsWith("dark:")
        val solid = asked.startsWith("solid:")
        val route0 = asked.removePrefix("dark:").removePrefix("solid:")
        // "limited:" shows the screen as a member a Family Admin restricted (no Health, view-only Finance and Chat).
        val limited = route0.startsWith("limited:")
        val route = route0.removePrefix("limited:")
        com.familyhub.app.family.AccessState.set(
            if (limited) mapOf("health" to "none", "finance" to "view", "chat" to "view") else emptyMap(), !limited
        )
        if (solid) {
            com.familyhub.app.core.theme.GlassPrefs.load(this)
            com.familyhub.app.core.theme.GlassPrefs.reduceTransparency.value = true
        }
        com.familyhub.app.data.remote.DemoChats.seed(DemoData.FAMILY, DemoData.UID, "Demo User", listOf("asha" to "Asha", "ravi" to "Ravi"))
        val ask = intent.getStringExtra("ask")?.replace('_', ' ')?.takeIf { it.isNotBlank() }
        setContent {
            FamilyHubTheme(darkTheme = if (dark) true else com.familyhub.app.core.theme.themeIsDark()) {
                val scope = rememberCoroutineScope()
                val baseEnv = androidx.compose.runtime.remember { DemoData.env(recordRepository, familyRepository, scope) }
                // With the test database, Finance entries come live from it (the same listener the app uses).
                val liveExpenses = if (store == "emulator") DemoStore.liveExpenses(recordRepository, baseEnv) else null
                val env = if (liveExpenses == null) baseEnv else baseEnv.copy(data = baseEnv.data + ("expenses" to liveExpenses))
                Column(modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                    Text(
                        "Sample data • test screen",
                        fontSize = 10.sp,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.CenterHorizontally)
                            .background(Color(0x99000000), MaterialTheme.shapes.small)
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                    val titleCollapse = androidx.compose.runtime.remember { com.familyhub.app.core.theme.TitleCollapse() }
                    androidx.compose.runtime.CompositionLocalProvider(com.familyhub.app.core.theme.LocalTitleCollapse provides titleCollapse) {
                    Box(modifier = Modifier.fillMaxSize().nestedScroll(titleCollapse.connection)) {
                        // "form:<module>" opens that module's add form (Health, Documents, Reminders…) for the screenshot check.
                        val def = ModuleByRoute[route.removePrefix("form:")]
                        val financeOverview: @Composable () -> Unit = { com.familyhub.app.ui.money.FinanceOverview(env) }
                        when {
                            route == "assistant" -> AssistantScreen(
                                env,
                                autoAsk = ask,
                                autoAsks = listOf(
                                    "Add the Rs. 40 in expense of travel today from HDFC bank via UPI to Rajesh",
                                    "Start my periods from today with heavy flow and cramps",
                                    "Weight 63.5 kg and BP 118/76, walked 30 minutes",
                                    "Book an Uber to Bangalore airport and find Android developer jobs in Gurugram on LinkedIn"
                                )
                            )
                            route == "import" -> com.familyhub.app.ui.money.ImportScreen(env, demoTexts = DemoData.SAMPLE_SMS)
                            route == "home" || route == "home_scrolled" -> Column(Modifier.fillMaxSize()) {
                                val homeList = androidx.compose.foundation.lazy.rememberLazyListState()
                                val alerts = androidx.compose.runtime.remember(env.data) {
                                    (com.familyhub.app.ui.modules.allAlerts(env.data)).sortedBy { it.dateMillis }
                                }
                                // "home_scrolled" starts part-way down, to photograph the folded Due Summary.
                                if (route == "home_scrolled") androidx.compose.runtime.LaunchedEffect(Unit) { kotlinx.coroutines.delay(1500); homeList.scrollToItem(4) }
                                com.familyhub.app.ui.money.DueSummary(env, alerts, homeList) {}
                                Box(Modifier.weight(1f)) {
                                    com.familyhub.app.ui.screens.HomeDashboardScreen(
                                        family = env.family,
                                        currentUser = env.profile,
                                        currentMember = env.members.first(),
                                        members = env.members,
                                        events = emptyList(),
                                        transactions = emptyList(),
                                        documents = emptyList(),
                                        tasks = emptyList(),
                                        trips = emptyList(),
                                        onQuickNavigate = {},
                                        aiCard = { com.familyhub.app.ui.ai.AiBriefCard(env) },
                                        records = env.data,
                                        duesCard = null,
                                        glanceCard = { fv -> com.familyhub.app.ui.money.HomeGlance(env, fv) },
                                        todayCard = { com.familyhub.app.ui.screens.TodayCard(env) },
                                        listState = homeList
                                    )
                                }
                            }
                            route == "more" -> MoreHubGrid(onNavigate = {}, onEdit = {})
                            // The Family Admin's access sheet for one member.
                            route == "access_sheet" -> com.familyhub.app.ui.modules.MemberAccessSheet(
                                com.familyhub.app.data.model.FamilyMember(uid = "ravi", name = "Ravi", perms = mapOf("health" to "none", "finance" to "view")),
                                onDismiss = {}, onSave = {}
                            )
                            route == "chat_list" -> com.familyhub.app.ui.chat.ChatListScreen(env) { }
                            route.startsWith("chat:") -> com.familyhub.app.ui.chat.ChatScreen(env, route.removePrefix("chat:"), onBack = {})
                            route == "assistant_new" -> AssistantScreen(env)
                            // One question only, for the planner checks.
                            route == "ask" -> AssistantScreen(env, autoAsk = ask)
                            // Group call screens with made-up people (no connection), for the screenshots.
                            route == "group_ringing" || route == "group_call" -> {
                                val names = mapOf("asha" to "Asha", DemoData.UID to "Demo User", "ravi" to "Ravi", "dadi" to "Dadi")
                                androidx.compose.runtime.LaunchedEffect(route) {
                                    com.familyhub.app.calling.GroupCallManager.showForDemo(
                                        if (route == "group_ringing") com.familyhub.app.calling.GroupUi(
                                            com.familyhub.app.calling.GroupPhase.RINGING, "demo-g", "asha", "Asha", true, names, listOf("asha", DemoData.UID, "ravi"), listOf("asha")
                                        ) else com.familyhub.app.calling.GroupUi(
                                            com.familyhub.app.calling.GroupPhase.IN_CALL, "demo-g", DemoData.UID, "Demo User", false, names,
                                            listOf(DemoData.UID, "asha", "ravi", "dadi"), listOf(DemoData.UID, "asha", "dadi"),
                                            peerStates = mapOf("asha" to "connected", "dadi" to "reconnecting"),
                                            quality = mapOf("asha" to com.familyhub.app.calling.GroupCallMath.Quality.GOOD, "dadi" to com.familyhub.app.calling.GroupCallMath.Quality.FAIR),
                                            speaking = "asha", startedAt = System.currentTimeMillis() - 65_000
                                        )
                                    )
                                }
                                com.familyhub.app.ui.calls.GroupCallOverlay(env)
                            }
                            // Universal search with a query typed in, for the search checks.
                            route == "search_q" -> com.familyhub.app.ui.search.UniversalSearchScreen(env, initialQuery = ask.orEmpty())
                            route == "assistant_tidy" -> AssistantScreen(
                                env,
                                autoAsks = listOf(
                                    "Find duplicate transactions",
                                    "Merge refunds",
                                    "Add emergency contact Papa 98765 43210",
                                    "Act on tasks added by the owner"
                                )
                            )
                            route == "txn_sheet" || route == "txn_select" -> ModuleScreen(
                                def = com.familyhub.app.ui.modules.FinanceModule,
                                familyId = DemoData.FAMILY,
                                uid = DemoData.UID,
                                userName = "Demo User",
                                memberNames = env.memberNames,
                                monthlyBudget = env.family.monthlyBudget,
                                isOwner = true,
                                repo = recordRepository,
                                onBack = null,
                                onOpen = {},
                                onAskAi = {},
                                preview = env.data["expenses"],
                                previewState = if (route == "txn_sheet") "sheet" else "select"
                            )
                            route == "settings" -> com.familyhub.app.ui.modules.SettingsScreen(
                                family = env.family, user = env.profile, isOwner = true,
                                onBack = {}, onSignOut = {}, onOpen = {}
                            )
                            route == "family_tab" -> com.familyhub.app.ui.modules.FamilyScreen(
                                family = env.family, members = env.members, currentUid = env.uid, isOwner = true,
                                onApprove = {}, onRemove = {}
                            )
                            route == "alerts" -> com.familyhub.app.ui.modules.AlertsScreen(
                                alerts = com.familyhub.app.ui.modules.buildAlerts(env.data), onBack = {}, onOpen = {}
                            )
                            route == "finance" -> com.familyhub.app.ui.money.FinanceDashboard(env)
                            route == "universal" -> com.familyhub.app.ui.ai.UniversalChatScreen(env)
                            route == "universal_connected" -> com.familyhub.app.ui.ai.UniversalChatScreen(env, previewConnected = true)
                            // The assistant changing a transaction's category, with the real Finance row for it below.
                            route == "ai_recat" -> Column(Modifier.fillMaxSize()) {
                                Box(Modifier.weight(1f)) { AssistantScreen(env, autoAsk = ask) }
                                DemoStore.FinanceRows(env, store)
                            }
                            // Forms and detail sheets, opened directly so the emulator can photograph them.
                            route == "form_expense" -> Box(Modifier.fillMaxSize()) {
                                com.familyhub.app.ui.money.TransactionsScreen(env)
                                com.familyhub.app.ui.money.TransactionForm(env, null, com.familyhub.app.money.FinanceMonth.Kind.EXPENSE) {}
                            }
                            route == "form_edit_expense" -> Box(Modifier.fillMaxSize()) {
                                val r = env.data["expenses"].orEmpty().first { it["type"] == "EXPENSE" }
                                com.familyhub.app.ui.money.TransactionsScreen(env)
                                com.familyhub.app.ui.money.TransactionForm(env, r, com.familyhub.app.money.FinanceMonth.Kind.EXPENSE) {}
                            }
                            route == "sheet_transaction" -> Box(Modifier.fillMaxSize()) {
                                val all = env.data["expenses"].orEmpty()
                                com.familyhub.app.ui.money.TransactionsScreen(env)
                                // The Swiggy entry the assistant check changes (live data can come back in any order).
                                val r = all.firstOrNull { (it["title"] as? String).orEmpty().startsWith("Swiggy order") } ?: all.first { it["type"] == "EXPENSE" }
                                com.familyhub.app.ui.money.TransactionDetails(env, r, all, {}) {}
                            }
                            isExtraRoute(route) -> ExtraRoute(route, env)
                            def != null -> ModuleScreen(
                                def = def,
                                familyId = DemoData.FAMILY,
                                uid = DemoData.UID,
                                userName = "Demo User",
                                memberNames = env.memberNames,
                                monthlyBudget = env.family.monthlyBudget,
                                isOwner = true,
                                repo = recordRepository,
                                onBack = {},
                                onOpen = {},
                                onAskAi = {},
                                overview = if (def.kind == "finance") financeOverview
                                    else if (def.route in com.familyhub.app.ui.family.ModuleOverviewRoutes) ({ com.familyhub.app.ui.family.ModuleOverview(def.route, env) }) else null,
                                preview = env.data[def.collection],
                                previewState = if (route.startsWith("form:")) "form" else null
                            )
                            else -> Text("Unknown screen: $route", modifier = Modifier.padding(24.dp))
                        }
                    }
                    }
                }
            }
        }
    }
}

object DemoData {
    /** Typical Indian bank alerts, plus an OTP and an offer that must be ignored. */
    val SAMPLE_SMS = listOf(
        "Rs.450.00 debited from A/c XX1234 on 24-11-26 to VPA swiggy@icici (UPI Ref No 432112345678). Not you? Call 18002586161 - HDFC Bank",
        "INR 2,199.00 spent on ICICI Bank Card XX4321 on 23-Nov-26 at AMAZON PAY IN. Avl Lmt: INR 97,801.00. If not you, call 1800 2662",
        "Dear Customer, Rs 25,000.00 credited to your A/c XXXXXX5678 on 25-11-2026 by NEFT from ACME PVT LTD. Avl Bal Rs 1,02,345.67 -SBI",
        "Your OTP for transaction of Rs 999 is 123456. Do not share it with anyone.",
        "Payment of Rs 3,804.00 received towards your HDFC Bank Credit Card XX4321. Thank you.",
        "Sent Rs.120.00 from Kotak Bank AC X9876 to zepto@ybl on 25-11-26. UPI Ref 532198765432. Not you, call 18602662666",
        "Rs.1,840.00 paid towards BESCOM electricity bill via BBPS from A/c **1234 on 22/11/2026.",
        "Get 10% cashback up to Rs 500 on your next recharge! Offer valid till 30 Nov.",
        "Your HDFC Bank Credit Card XX4321 statement is generated. Total due Rs 12,450.00, minimum due Rs 620.00. Payment due date 08-Dec-2026.",
        "Dear Customer, your Airtel postpaid bill of Rs 599.00 for 98XXXXXX10 is due on 05/12/2026. Pay now to avoid late fee.",
        "Your Netflix subscription of Rs 649 will be renewed on 29 Nov 2026 using your card XX4321.",
        "Premium of Rs 25,000 for your Star Health policy is due on 15-12-2026. Renew on time to stay covered.",
        "Complete your KYC for SBI account by 10-12-2026 to avoid restrictions on your account."
    )

    const val UID = "demo-user"
    const val FAMILY = "demo-family"

    private var counter = 0

    private fun rec(vararg pairs: Pair<String, Any?>, private: Boolean = false): Rec {
        val m = HashMap<String, Any?>()
        m["id"] = "demo-" + (counter++)
        pairs.forEach { (k, v) -> m[k] = v }
        if (private) m[PRIVATE_FLAG] = true
        return m
    }

    fun env(repo: RecordRepository, families: FamilyRepository, scope: kotlinx.coroutines.CoroutineScope): AppEnv {
        val now = noonToday()
        val d = DAY_MS
        val today = dayKey()
        val yesterday = dayKey(now - d)
        val items = listOf(
            rec("type" to "medicine", "title" to "Vitamin D3", "dose" to "1 tablet", "times" to "08:00, 21:00",
                "food" to "After food", "memberName" to "Demo User", "stock" to 4.0,
                "taken" to mapOf(today + "_0800" to true), private = true),
            rec("type" to "medicine", "title" to "Thyroxine 50", "dose" to "1 tablet", "times" to "07:00",
                "food" to "Before food", "memberName" to "Asha", "stock" to 20.0),
            rec("type" to "reminder", "title" to "Pay school fees", "dateMillis" to now + 2 * d, "time" to "10:00",
                "repeat" to "Monthly", "done" to false, private = true),
            rec("type" to "food", "title" to "Poha and tea", "meal" to "Breakfast", "kcal" to 320.0, "kcalText" to "320 kcal",
                "dateMillis" to now, "memberName" to "Demo User", private = true),
            rec("type" to "food", "title" to "2 rotis, dal and salad", "meal" to "Lunch", "kcal" to 540.0, "kcalText" to "540 kcal",
                "dateMillis" to now, "memberName" to "Demo User", private = true),
            rec("type" to "reminder", "title" to "Call the plumber", "dateMillis" to now, "time" to "18:30",
                "repeat" to "None", "earlyAlert" to "15 minutes before", "done" to false, private = true),
            rec("type" to "reminder", "title" to "Water the plants", "dateMillis" to now - 10 * d, "time" to "",
                "repeat" to "Every few days", "everyDays" to 2.0, "done" to false, private = true),
            rec("type" to "vital", "kind" to "Weight (kg)", "value" to "64.5", "dateMillis" to now - 20 * d, private = true),
            rec("type" to "vital", "kind" to "Weight (kg)", "value" to "63.8", "dateMillis" to now - 10 * d, private = true),
            rec("type" to "vital", "kind" to "Weight (kg)", "value" to "63.1", "dateMillis" to now - 1 * d, private = true),
            rec("type" to "vital", "kind" to "Blood pressure", "value" to "118/78", "dateMillis" to now - 2 * d, private = true),
            rec("type" to "vital", "kind" to "Sleep (hours)", "value" to "7.5", "dateMillis" to now - 1 * d, private = true),
            rec("type" to "habit", "title" to "Morning walk", "doneDays" to listOf(yesterday, dayKey(now - 2 * d)), private = true),
            rec("type" to "habit", "title" to "No sugar", "doneDays" to listOf(today), private = true),
            rec("type" to "workout", "activity" to "Yoga", "minutes" to 40.0, "minutesText" to "40 min", "dateMillis" to now - d, private = true),
            rec("type" to "workout", "activity" to "Walk", "minutes" to 35.0, "minutesText" to "35 min", "dateMillis" to now - 3 * d, private = true),
            rec("type" to "period", "title" to "Period", "startMillis" to now - 58 * d, "endMillis" to now - 54 * d, private = true),
            rec("type" to "period", "title" to "Period", "startMillis" to now - 29 * d, "endMillis" to now - 25 * d,
                "flow" to "Medium", "symptoms" to listOf("Cramps"), private = true),
            rec("id" to "demo-hdfc-card", "type" to "card", "title" to "HDFC Millennia", "bank" to "HDFC", "last4" to "4321", "rewardPoints" to 2140.0,
                "statementDay" to 20.0, "dueDay" to 8.0, "limitInr" to 100000.0, private = true),
            rec("type" to "card", "title" to "SBI SimplyCLICK", "bank" to "SBI", "last4" to "9876",
                "statementDay" to 3.0, "dueDay" to 23.0, "limitInr" to 60000.0, private = true),
            rec("type" to "subscription", "title" to "Netflix", "amountInr" to 649.0, "cycle" to "Monthly",
                "renewalDateMillis" to now + 3 * d, "renewalMillis" to now + 3 * d),
            rec("id" to "demo-hdfc", "type" to "account", "title" to "HDFC Savings", "category" to "Savings", "bank" to "HDFC", "last4" to "1234",
                "openingBalance" to 142000.0, "openingDateMillis" to now - 100 * d, private = true),
            rec("type" to "account", "title" to "Cash", "category" to "Cash", "openingBalance" to 3500.0, "openingDateMillis" to now - 100 * d,
                "memberUid" to UID, "memberName" to "Demo User"),
            rec("type" to "debit_card", "title" to "HDFC Debit", "account" to "HDFC Savings", "accountId" to "demo-hdfc", "last4" to "5566", private = true),
            rec("id" to "demo-loan", "type" to "loan", "title" to "Car loan", "loanType" to "Car", "lender" to "HDFC Bank",
                "principal" to 600000.0, "interestRate" to 9.5, "tenureMonths" to 60.0, "startMillis" to now - 150 * d,
                "account" to "HDFC Savings", "memberUid" to UID, "memberName" to "Demo User"),
            rec("type" to "policy", "title" to "Family health cover", "insurer" to "Star Health", "category" to "Health",
                "amountInr" to 24500.0, "premiumCycle" to "Yearly", "premiumDueMillis" to now + 12 * d, "memberUid" to UID),
            rec("type" to "category", "title" to "Swiggy", "parent" to "Dining", "kind" to "Spending"),
            rec("type" to "category", "title" to "Pet care", "parent" to "None", "kind" to "Spending", "emoji" to "🐶"),
            rec("type" to "category", "title" to "Freelance", "parent" to "None", "kind" to "Income", "emoji" to "💼")
        )
        val cats = listOf("Groceries", "Dining", "Fuel", "Shopping", "Utilities", "Health", "Entertainment", "Transport")
        val places = listOf("BigBasket", "Swiggy", "Indian Oil", "Amazon", "Airtel", "Apollo Pharmacy", "PVR", "Uber")
        val methods = listOf("UPI", "Credit card", "Debit card", "UPI", "Net banking", "UPI", "Credit card", "UPI")
        val people = listOf("Demo User", "Asha", "Ravi")
        val expenses = ArrayList<Rec>()
        for (i in 0 until 60) {
            val c = i % cats.size
            val card = if (methods[c] == "Credit card") (if (i % 2 == 0) "HDFC Millennia" else "SBI SimplyCLICK") else "None"
            expenses.add(
                rec(
                    "type" to "EXPENSE", "title" to places[c], "category" to cats[c],
                    "amountInr" to (150.0 + (i * 137) % 2400), "dateMillis" to now - (i * 1.6 * d).toLong(),
                    "paymentMethod" to methods[c], "card" to card, "memberName" to people[i % 3],
                    "memberUid" to if (i % 3 == 0) UID else "other", "isPaid" to true, "isDeleted" to false,
                    private = i % 5 == 0
                )
            )
        }
        // A payment saved twice (by hand and from SMS), and a refund to merge.
        expenses.add(0, rec("type" to "EXPENSE", "title" to "Swiggy order from Meghana Foods Koramangala", "category" to "Dining",
            "amountInr" to 645.0, "dateMillis" to now - 2 * d, "paymentMethod" to "UPI", "memberName" to "Demo User", "memberUid" to UID,
            "isPaid" to true, "isDeleted" to false))
        expenses.add(rec("type" to "EXPENSE", "title" to "SWIGGY", "category" to "Other", "sourceKey" to "sms-demo-1",
            "amountInr" to 645.0, "dateMillis" to now - 2 * d + 600_000L, "paymentMethod" to "UPI", "isPaid" to true, "isDeleted" to false))
        // A UPI payment to a person, for "Change the category of Mukul to Shopping".
        expenses.add(rec("type" to "EXPENSE", "title" to "UPI/MUKUL KUMAR/okaxis", "category" to "Other", "amountInr" to 350.0,
            "dateMillis" to now - 3 * d, "paymentMethod" to "UPI", "memberName" to "Demo User", "memberUid" to UID, "isPaid" to true, "isDeleted" to false))
        expenses.add(rec("type" to "EXPENSE", "title" to "Myntra shoes", "category" to "Shopping", "amountInr" to 2499.0,
            "dateMillis" to now - 9 * d, "paymentMethod" to "Credit card", "card" to "HDFC Millennia", "memberName" to "Demo User",
            "memberUid" to UID, "isPaid" to true, "isDeleted" to false))
        expenses.add(rec("type" to "INCOME", "title" to "Myntra refund", "category" to "Other", "amountInr" to 2499.0,
            "dateMillis" to now - 4 * d, "isDeleted" to false))
        expenses.add(rec("type" to "EXPENSE", "title" to "Transfer to self HDFC", "category" to "Other", "amountInr" to 10000.0,
            "dateMillis" to now - 3 * d, "paymentMethod" to "Net banking", "isPaid" to true, "isDeleted" to false, "notExpense" to true))
        expenses.add(rec("type" to "INCOME", "title" to "Salary", "category" to "Salary", "amountInr" to 85000.0,
            "dateMillis" to now - 20 * d, "memberName" to "Demo User", "memberUid" to UID, "isDeleted" to false, "account" to "HDFC Savings"))
        // Money moved, not spent: a card bill paid from the bank and cash taken out.
        expenses.add(rec("type" to "CARD_PAYMENT", "title" to "HDFC Millennia payment", "amountInr" to 5000.0, "dateMillis" to now - 6 * d,
            "account" to "HDFC Savings", "card" to "HDFC Millennia", "cardId" to "demo-hdfc-card", "memberUid" to UID, "isDeleted" to false))
        expenses.add(rec("type" to "TRANSFER", "title" to "HDFC Savings → Cash", "amountInr" to 2000.0, "dateMillis" to now - 5 * d,
            "account" to "HDFC Savings", "toAccount" to "Cash", "memberUid" to UID, "isDeleted" to false))
        // Four car-loan EMIs paid; the fifth is still open.
        for (i in 1..4) {
            expenses.add(rec("type" to "EMI", "title" to "Car loan EMI $i/60", "category" to "Loan & EMI", "amountInr" to 12601.0,
                "dateMillis" to now - (150 - (i - 1) * 30) * d, "isPaid" to true, "loanId" to "demo-loan", "installmentNo" to i.toDouble(),
                "account" to "HDFC Savings", "memberUid" to UID, "isDeleted" to false))
        }
        expenses.add(rec("type" to "BILL", "title" to "Electricity bill", "category" to "Utilities", "amountInr" to 1840.0,
            "dateMillis" to now + 4 * d, "dueDateMillis" to now + 4 * d, "isPaid" to false, "isDeleted" to false))
        val events = listOf(
            rec("title" to "Asha's birthday", "category" to "Birthday", "startTimeMillis" to now + 5 * d, "recurrence" to "YEARLY", "isDeleted" to false),
            rec("title" to "Parent-teacher meeting", "category" to "School", "startTimeMillis" to now + 1 * d, "recurrence" to "NONE", "isDeleted" to false)
        )
        val tasks = listOf(
            rec("type" to "SHOPPING", "title" to "Milk", "isCompleted" to false),
            rec("type" to "SHOPPING", "title" to "Atta 5 kg", "isCompleted" to false),
            rec("type" to "TASK", "title" to "Car service", "dueDateMillis" to now + 6 * d, "isCompleted" to false, "assignedToName" to "Ravi"),
            rec("type" to "TASK", "title" to "Book a cab to the airport for Friday", "dueDateMillis" to now + 2 * d, "isCompleted" to false,
                "createdByUid" to UID, "createdByName" to "Demo User"),
            rec("type" to "TASK", "title" to "Order milk and eggs", "isCompleted" to false, "createdByUid" to UID, "createdByName" to "Demo User")
        )
        // Phase 1 sample data: documents with expiry dates, a monthly and a weekly payee, a finished task.
        val docs = listOf(
            rec("type" to "document", "title" to "Passport (Asha)", "category" to "Passport", "holder" to "Asha", "number" to "J1234567",
                "expiryDateMillis" to now + 20 * d, "remindBefore" to "60 days before", "renewalStatus" to "In progress",
                "createdAt" to now - 3 * 3_600_000L, "createdByName" to "Asha"),
            rec("type" to "document", "title" to "Car insurance", "category" to "Vehicle insurance", "holder" to "Ravi",
                "expiryDateMillis" to now - 5 * d, "renewalStatus" to "Not started", "createdAt" to now - 2 * d, "createdByName" to "Ravi"),
            rec("type" to "document", "title" to "Driving licence", "category" to "Driving licence", "holder" to "Demo User",
                "expiryDateMillis" to now + 400 * d, "createdAt" to now - 6 * d, "createdByName" to "Demo User")
        )
        for (k in 0..3) expenses.add(rec("type" to "EXPENSE", "title" to "NETFLIX.COM", "category" to "Entertainment", "amountInr" to 649.0,
            "dateMillis" to now - (5 + 30 * k) * d, "paymentMethod" to "Credit card", "memberName" to "Demo User", "isPaid" to true, "isDeleted" to false))
        for (k in 0..4) expenses.add(rec("type" to "EXPENSE", "title" to "Milkman Ramesh", "category" to "Groceries", "amountInr" to 300.0 + (k % 2) * 10,
            "dateMillis" to now - (1 + 7 * k) * d, "paymentMethod" to "UPI", "memberName" to "Asha", "isPaid" to true, "isDeleted" to false))
        // AI spending checks: an "Only me" grocery entry, a second category that also means transport, and a subcategory.
        expenses.add(rec("type" to "EXPENSE", "title" to "DMart", "category" to "Groceries", "amountInr" to 1250.0, "dateMillis" to now - 2 * d,
            "paymentMethod" to "UPI", "memberName" to "Demo User", "createdByUid" to UID, "isPaid" to true, "isDeleted" to false, private = true))
        expenses.add(rec("type" to "EXPENSE", "title" to "Uber", "category" to "Cab", "amountInr" to 220.0, "dateMillis" to now - 2 * d,
            "paymentMethod" to "UPI", "memberName" to "Demo User", "isPaid" to true, "isDeleted" to false))
        expenses.add(rec("type" to "EXPENSE", "title" to "Steel utensils", "category" to "Household", "subcategory" to "Kitchen supplies",
            "amountInr" to 640.0, "dateMillis" to now - 3 * d, "paymentMethod" to "UPI", "memberName" to "Demo User", "isPaid" to true, "isDeleted" to false))
        // Phase 2 sample data: lists with prices, two clashing appointments, a paused subscription, an AC and a car.
        val p2Shop = listOf(
            rec("type" to "SHOPPING", "title" to "Tomatoes", "qty" to "1 kg", "listName" to "Groceries", "category" to "Grocery", "estPrice" to 40.0,
                "assignedToName" to "Ravi", "isCompleted" to false, "status" to "Pending"),
            rec("type" to "SHOPPING", "title" to "Basmati rice", "qty" to "5 kg", "listName" to "Groceries", "category" to "Grocery", "estPrice" to 110.0,
                "isCompleted" to false, "status" to "Pending"),
            rec("type" to "SHOPPING", "title" to "LED bulbs", "qty" to "4", "listName" to "Household", "category" to "Household", "estPrice" to 120.0,
                "isCompleted" to false, "status" to "Unavailable"),
            rec("type" to "SHOPPING", "title" to "Balloons", "qty" to "20", "listName" to "Asha's party", "category" to "Other", "estPrice" to 5.0,
                "assignedToName" to "Asha", "isCompleted" to false, "status" to "Pending"),
            rec("type" to "SHOPPING", "title" to "Bread", "listName" to "Groceries", "isCompleted" to true, "completedAt" to now - 20 * 3_600_000L, "completedByName" to "Asha")
        )
        val p2Events = listOf(
            rec("title" to "Dentist (Asha)", "category" to "Appointment", "startTimeMillis" to now + 2 * d, "time" to "10:00", "endTime" to "11:00",
                "ownerName" to "Asha", "recurrence" to "NONE", "isDeleted" to false),
            rec("title" to "School PTM", "category" to "School", "startTimeMillis" to now + 2 * d, "time" to "10:30", "participants" to "Ravi, Asha",
                "recurrence" to "NONE", "isDeleted" to false),
            rec("title" to "Yoga", "category" to "Family", "startTimeMillis" to now - 3 * d, "time" to "07:00", "ownerName" to "Demo User",
                "recurrence" to "WEEKLY", "isDeleted" to false)
        )
        fun challengeItems(t: Long) = listOf(
            rec("type" to "challenge", "title" to "Holiday fund", "kind" to com.familyhub.app.money.Challenges.WEEK52, "stepInr" to 50.0,
                "startMillis" to t - 25 * d, "savedInr" to 300.0, "done" to false, "isDeleted" to false),
            rec("type" to "challenge", "title" to "No-spend fortnight", "kind" to com.familyhub.app.money.Challenges.NO_SPEND, "length" to 14.0,
                "startMillis" to t - 6 * d, "done" to false, "isDeleted" to false)
        )
        val p2Items = listOf(
            rec("type" to "maintenance", "title" to "Bedroom AC", "category" to "AC service", "vendor" to "CoolCare", "repeat" to "Quarterly",
                "nextDueMillis" to now + 10 * d, "warrantyExpiryMillis" to now + 40 * d, "costInr" to 600.0,
                "serviceLog" to listOf(mapOf("at" to now - 80 * d, "cost" to 600.0, "provider" to "CoolCare", "notes" to "Wet service"))),
            rec("type" to "vehicle", "title" to "Honda City", "category" to "Car", "regNumber" to "HR26 AB 1234", "serviceDueMillis" to now + 20 * d,
                "insuranceExpiryMillis" to now + 50 * d, "pucExpiryMillis" to now + 120 * d),
            rec("type" to "goal", "title" to "Goa holiday", "amountInr" to 60000.0, "savedInr" to 42000.0, "targetMillis" to now + 120 * d, "done" to false,
                "contributions" to listOf(mapOf("at" to now - 40 * d, "by" to "Asha", "amount" to 25000.0, "note" to ""),
                    mapOf("at" to now - 10 * d, "by" to "Ravi", "amount" to 17000.0, "note" to "Bonus"))),
            rec("type" to "budget_limit", "category" to "Dining", "amountInr" to 3000.0, "period" to "Monthly"),
            rec("type" to "budget_limit", "category" to "Groceries", "amountInr" to 96000.0, "period" to "Yearly"),
            rec("type" to "budget_limit", "category" to "Shopping", "amountInr" to 2000.0, "period" to "Monthly", private = true),
            rec("type" to "subscription", "title" to "Cult gym", "provider" to "Cult.fit", "amountInr" to 3000.0, "cycle" to "Quarterly",
                "status" to "Paused", "renewalMillis" to now + 15 * d, "updatedAt" to now - 5 * d)
        )
        // Sample "who did what" times for the activity timeline (sample data only).
        expenses.forEach { r ->
            val m = r as MutableMap<String, Any?>
            if (m["createdAt"] == null) (m["dateMillis"] as? Long)?.let { m["createdAt"] = it + 3_600_000L }
            if (m["createdByName"] == null) m["createdByName"] = m["memberName"] ?: "Ravi"
        }
        tasks.forEachIndexed { i, r ->
            val m = r as MutableMap<String, Any?>
            m["createdAt"] = now - (i + 1) * 5 * 3_600_000L
            if (m["createdByName"] == null) m["createdByName"] = listOf("Asha", "Ravi", "Demo User")[i % 3]
        }
        (tasks[2] as MutableMap<String, Any?>).let { it["isCompleted"] = true; it["completedAt"] = now - 3_600_000L; it["completedByName"] = "Ravi" }
        events.forEach { r -> (r as MutableMap<String, Any?>).let { it["createdAt"] = now - 26 * 3_600_000L; it["createdByName"] = "Asha" } }
        val trips = listOf(
            rec("destination" to "Jaipur", "startDateMillis" to now + 20 * d, "endDateMillis" to now + 23 * d)
        )
        val members = listOf(
            FamilyMember(uid = UID, familyId = FAMILY, name = "Demo User", bloodGroup = "B+", heightCm = 165.0, weightKg = 63.1,
                stepsToday = 6400L, stepsDate = dayKey(), stepsWeek = 41200L, dobMillis = now - 30L * 365 * d),
            FamilyMember(uid = "asha", familyId = FAMILY, name = "Asha", bloodGroup = "O+", heightCm = 158.0, weightKg = 66.0,
                stepsToday = 8900L, stepsDate = dayKey(), stepsWeek = 52100L, dobMillis = now + 5 * d - 32L * 365 * d),
            FamilyMember(uid = "ravi", familyId = FAMILY, name = "Ravi", bloodGroup = "A+",
                stepsToday = 3100L, stepsDate = dayKey(), stepsWeek = 19800L)
        )
        val userDoc: Rec = mapOf(
            "name" to "Demo User", "displayName" to "Demo User", "gender" to "Female", "heightCm" to 165.0, "weightKg" to 63.1,
            "bloodGroup" to "B+", "phone" to "98xxxxxx10", "monthStartDay" to 1,
            "categoryBudgets" to mapOf("Dining" to 3000.0, "Groceries" to 8000.0, "Shopping" to 5000.0),
            "idAadhaar" to "123412341234", "idPassportExpiry" to now + 40 * d
        )
        return AppEnv(
            uid = UID,
            profile = UserProfile(uid = UID, name = "Demo User", email = "demo@example.com", activeFamilyId = FAMILY, familyIds = listOf(FAMILY)),
            userDoc = userDoc,
            familyId = FAMILY,
            family = FamilyGroup(id = FAMILY, name = "Demo Family", ownerUid = UID, monthlyBudget = 60000.0, inviteCode = "DEMO42"),
            members = members,
            memberNames = members.map { it.name },
            isOwner = true,
            data = mapOf("items" to items + docs + p2Items + challengeItems(now), "events" to events + p2Events, "expenses" to expenses, "tasks" to tasks + p2Shop, "trips" to trips),
            repo = repo,
            familyRepository = families,
            scope = scope,
            onBack = {},
            navigate = {}
        )
    }
}


/** Test database support for the emulator check (debug builds only). */
object DemoStore {
    /** Seeds the sample Finance entries into the empty test database once, then follows them live. */
    @Composable
    fun liveExpenses(repo: RecordRepository, env: AppEnv): List<Rec> {
        val start = env.data["expenses"].orEmpty()
        val state = androidx.compose.runtime.produceState(initialValue = start) {
            try {
                if (repo.fetchFromServer(DemoData.FAMILY, DemoData.UID, "expenses").isEmpty()) {
                    start.forEach { r -> repo.saveScoped(DemoData.FAMILY, DemoData.UID, "expenses", r, r, r[PRIVATE_FLAG] == true) }
                }
                repo.observeMerged(DemoData.FAMILY, DemoData.UID, "expenses").collect { if (it.isNotEmpty()) value = it }
            } catch (e: Exception) {
                android.util.Log.w("DemoStore", "test database: ${e.message}")
            }
        }
        return state.value
    }

    /** The Swiggy entries as Finance → Transactions shows them (same row), from the same data the assistant changes. */
    @Composable
    fun FinanceRows(env: AppEnv, store: String?) {
        val rows = env.data["expenses"].orEmpty().filter { (it["title"] as? String).orEmpty().let { t -> t.startsWith("Swiggy order") || "MUKUL" in t } }
        Column(
            Modifier.fillMaxWidth().background(androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainer)
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            Text(
                "Finance → Transactions, " + when (store) { "emulator" -> "live from the test database"; "offline" -> "database unreachable"; else -> "sample data" },
                fontSize = 11.sp, color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant
            )
            rows.forEach { r -> com.familyhub.app.ui.money.TransactionRow(r, selected = false, selecting = false, onClick = {}, onLongClick = {}) }
        }
    }
}
