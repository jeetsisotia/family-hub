package com.familyhub.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.familyhub.app.ui.ExtraRouteIds
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
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val route = intent.getStringExtra("route") ?: "health_hub"
        val ask = intent.getStringExtra("ask")?.replace('_', ' ')?.takeIf { it.isNotBlank() }
        setContent {
            FamilyHubTheme {
                val scope = rememberCoroutineScope()
                val env = androidx.compose.runtime.remember { DemoData.env(recordRepository, familyRepository, scope) }
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
                    Box(modifier = Modifier.fillMaxSize()) {
                        val def = ModuleByRoute[route]
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
                            route == "home" -> com.familyhub.app.ui.screens.HomeDashboardScreen(
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
                                aiCard = { com.familyhub.app.ui.ai.AiBriefCard(env) }
                            )
                            route == "more" -> MoreHubGrid(onNavigate = {}, onEdit = {})
                            route in ExtraRouteIds -> ExtraRoute(route, env)
                            def != null -> ModuleScreen(
                                def = def,
                                familyId = DemoData.FAMILY,
                                uid = DemoData.UID,
                                userName = "Demo User",
                                memberNames = env.memberNames,
                                monthlyBudget = env.family.monthlyBudget,
                                isOwner = true,
                                repo = recordRepository,
                                onBack = {}
                            )
                            else -> Text("Unknown screen: $route", modifier = Modifier.padding(24.dp))
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
        "Get 10% cashback up to Rs 500 on your next recharge! Offer valid till 30 Nov."
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
            rec("type" to "card", "title" to "HDFC Millennia", "bank" to "HDFC", "last4" to "4321",
                "statementDay" to 20.0, "dueDay" to 8.0, "limitInr" to 100000.0, private = true),
            rec("type" to "card", "title" to "SBI SimplyCLICK", "bank" to "SBI", "last4" to "9876",
                "statementDay" to 3.0, "dueDay" to 23.0, "limitInr" to 60000.0, private = true),
            rec("type" to "subscription", "title" to "Netflix", "amountInr" to 649.0, "renewalDateMillis" to now + 3 * d)
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
        expenses.add(rec("type" to "INCOME", "title" to "Salary", "category" to "Salary", "amountInr" to 85000.0,
            "dateMillis" to now - 20 * d, "memberName" to "Demo User", "memberUid" to UID, "isDeleted" to false))
        expenses.add(rec("type" to "BILL", "title" to "Electricity bill", "category" to "Utilities", "amountInr" to 1840.0,
            "dateMillis" to now + 4 * d, "dueDateMillis" to now + 4 * d, "isPaid" to false, "isDeleted" to false))
        val events = listOf(
            rec("title" to "Asha's birthday", "category" to "Birthday", "startTimeMillis" to now + 5 * d, "recurrence" to "YEARLY", "isDeleted" to false),
            rec("title" to "Parent-teacher meeting", "category" to "School", "startTimeMillis" to now + 1 * d, "recurrence" to "NONE", "isDeleted" to false)
        )
        val tasks = listOf(
            rec("type" to "SHOPPING", "title" to "Milk", "isCompleted" to false),
            rec("type" to "SHOPPING", "title" to "Atta 5 kg", "isCompleted" to false),
            rec("type" to "TASK", "title" to "Car service", "dueDateMillis" to now + 6 * d, "isCompleted" to false, "assignedToName" to "Ravi")
        )
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
            family = FamilyGroup(id = FAMILY, name = "Demo Family", ownerUid = UID, monthlyBudget = 60000.0),
            members = members,
            memberNames = members.map { it.name },
            isOwner = true,
            data = mapOf("items" to items, "events" to events, "expenses" to expenses, "tasks" to tasks, "trips" to trips),
            repo = repo,
            familyRepository = families,
            scope = scope,
            onBack = {},
            navigate = {}
        )
    }
}
