package com.familyhub.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.familyhub.app.core.theme.FamilyHubTheme
import com.familyhub.app.data.model.*
import com.familyhub.app.data.remote.AuthRepository
import com.familyhub.app.data.remote.FamilyRepository
import com.familyhub.app.data.remote.Rec
import com.familyhub.app.data.remote.RecordRepository
import com.familyhub.app.ui.auth.LoginScreen
import com.familyhub.app.ui.modules.*
import com.familyhub.app.ui.navigation.MoreHubGrid
import com.familyhub.app.ui.navigation.PrimaryTab
import com.familyhub.app.ui.screens.FinanceReportExporter
import com.familyhub.app.ui.screens.HomeDashboardScreen
import com.familyhub.app.work.ReminderWorker
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var familyRepository: FamilyRepository
    @Inject lateinit var recordRepository: RecordRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FamilyHubTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                val firebaseUser by authRepository.authStateFlow.collectAsState(initial = null)
                var isLoading by remember { mutableStateOf(false) }
                var errorMessage by remember { mutableStateOf<String?>(null) }
                var selectedTab by remember { mutableStateOf(PrimaryTab.HOME) }
                var subRoute by remember { mutableStateOf<String?>(null) }

                val fbUser = firebaseUser
                if (fbUser == null) {
                    LoginScreen(
                        isLoading = isLoading,
                        errorMessage = errorMessage,
                        onGoogleSignInClick = {
                            isLoading = true
                            errorMessage = null
                            scope.launch {
                                val res = authRepository.signInWithGoogle(context)
                                isLoading = false
                                res.onFailure { errorMessage = it.message ?: "Sign-in failed" }
                            }
                        },
                        onOpenLegalDoc = { doc ->
                            errorMessage = if (doc == "privacy") {
                                "Family Hub keeps family data isolated per group and never exposes private vault items publicly."
                            } else {
                                "Family Hub Terms: Use with authorised family members under shared group permissions."
                            }
                        }
                    )
                } else {
                    val uid = fbUser.uid

                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { }

                    LaunchedEffect(uid) {
                        // The profile document may be missing (e.g. the app was closed right after the first sign-in).
                        familyRepository.ensureUserProfile(fbUser)
                        // Daily reminder check for bills, renewals, expiries and events.
                        try {
                            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                                "daily_reminders",
                                ExistingPeriodicWorkPolicy.KEEP,
                                PeriodicWorkRequestBuilder<ReminderWorker>(1, TimeUnit.DAYS).build()
                            )
                        } catch (e: Exception) {
                            android.util.Log.w("MainActivity", "Could not schedule reminders", e)
                        }
                        if (Build.VERSION.SDK_INT >= 33) {
                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                        }
                    }

                    val profileFlow = remember(uid) { authRepository.observeUserProfile(uid) }
                    val storedProfile by profileFlow.collectAsState(initial = null)
                    val profile = storedProfile ?: UserProfile(
                        uid = uid,
                        name = fbUser.displayName ?: "Member",
                        email = fbUser.email.orEmpty(),
                        photoUrl = fbUser.photoUrl?.toString().orEmpty()
                    )

                    val activeFamilyId = profile.activeFamilyId
                    val familyFlow = remember(activeFamilyId) { familyRepository.observeFamily(activeFamilyId) }
                    val membersFlow = remember(activeFamilyId) { familyRepository.observeMembers(activeFamilyId) }
                    val eventsFlow = remember(activeFamilyId) { familyRepository.observeEvents(activeFamilyId) }
                    val transactionsFlow = remember(activeFamilyId) { familyRepository.observeTransactions(activeFamilyId) }
                    val tasksFlow = remember(activeFamilyId) { familyRepository.observeTasks(activeFamilyId) }
                    val tripsFlow = remember(activeFamilyId) { familyRepository.observeTrips(activeFamilyId) }

                    val family by familyFlow.collectAsState(initial = null)
                    val members by membersFlow.collectAsState(initial = emptyList())
                    val events by eventsFlow.collectAsState(initial = emptyList())
                    val transactions by transactionsFlow.collectAsState(initial = emptyList())
                    val tasks by tasksFlow.collectAsState(initial = emptyList())
                    val trips by tripsFlow.collectAsState(initial = emptyList())

                    // Raw records power the reminders and the Documents summary on the dashboard.
                    val itemsRaw = remember(activeFamilyId) { recordRepository.observeRecords(activeFamilyId, "items") }
                    val eventsRaw = remember(activeFamilyId) { recordRepository.observeRecords(activeFamilyId, "events") }
                    val expensesRaw = remember(activeFamilyId) { recordRepository.observeRecords(activeFamilyId, "expenses") }
                    val tasksRaw = remember(activeFamilyId) { recordRepository.observeRecords(activeFamilyId, "tasks") }
                    val tripsRaw = remember(activeFamilyId) { recordRepository.observeRecords(activeFamilyId, "trips") }
                    val itemRecs by itemsRaw.collectAsState(initial = emptyList<Rec>())
                    val eventRecs by eventsRaw.collectAsState(initial = emptyList<Rec>())
                    val expenseRecs by expensesRaw.collectAsState(initial = emptyList<Rec>())
                    val taskRecs by tasksRaw.collectAsState(initial = emptyList<Rec>())
                    val tripRecs by tripsRaw.collectAsState(initial = emptyList<Rec>())

                    val statusFlow = remember(activeFamilyId, uid) { recordRepository.observeMyStatus(activeFamilyId, uid) }
                    val myStatus by statusFlow.collectAsState(initial = "LOADING")

                    val alerts = remember(itemRecs, eventRecs, expenseRecs, taskRecs, tripRecs) {
                        buildAlerts(
                            mapOf(
                                "items" to itemRecs,
                                "events" to eventRecs,
                                "expenses" to expenseRecs,
                                "tasks" to taskRecs,
                                "trips" to tripRecs
                            )
                        )
                    }

                    val lockerDocs = remember(itemRecs) {
                        itemRecs.filter { it.str("type") == "document" }.map {
                            FamilyDocument(
                                id = it.str("id"),
                                familyId = activeFamilyId,
                                title = it.str("title"),
                                category = it.str("category"),
                                expiryDateMillis = it.lng("expiryDateMillis"),
                                isPrivate = false
                            )
                        }
                    }

                    val monthStart = remember {
                        Calendar.getInstance().apply {
                            set(Calendar.DAY_OF_MONTH, 1)
                            set(Calendar.HOUR_OF_DAY, 0)
                            set(Calendar.MINUTE, 0)
                            set(Calendar.SECOND, 0)
                            set(Calendar.MILLISECOND, 0)
                        }.timeInMillis
                    }
                    // The dashboard shows this month's spending plus every unpaid bill.
                    val dashboardTransactions = remember(transactions) {
                        transactions.filter { it.dateMillis >= monthStart || !it.isPaid }
                    }

                    val currentMember = members.firstOrNull { it.uid == uid }
                        ?: FamilyMember(uid = uid, name = profile.name, role = FamilyRole.ADULT)
                    val isOwner = family?.ownerUid == uid || currentMember.role == FamilyRole.OWNER
                    val activeNames = remember(members) { members.filter { it.status == "ACTIVE" }.map { it.name } }
                    val memberNames = if (activeNames.isEmpty()) listOf(profile.name) else activeNames

                    val navigate: (String) -> Unit = { r ->
                        when (r) {
                            "calendar" -> {
                                selectedTab = PrimaryTab.CALENDAR
                                subRoute = null
                            }
                            "finance" -> {
                                selectedTab = PrimaryTab.FINANCE
                                subRoute = null
                            }
                            else -> subRoute = r
                        }
                    }
                    val signOut: () -> Unit = {
                        scope.launch { authRepository.signOutAndSwitchAccount(context) }
                    }
                    val saveBudget: (Double) -> Unit = { v ->
                        scope.launch {
                            try {
                                recordRepository.updateBudget(activeFamilyId, v)
                            } catch (e: Exception) {
                                Toast.makeText(context, "Only the family owner can change the budget.", Toast.LENGTH_LONG).show()
                            }
                        }
                    }

                    BackHandler(enabled = subRoute != null) { subRoute = null }

                    Scaffold(
                        bottomBar = {
                            NavigationBar {
                                PrimaryTab.entries.forEach { tab ->
                                    NavigationBarItem(
                                        selected = selectedTab == tab && subRoute == null,
                                        onClick = {
                                            selectedTab = tab
                                            subRoute = null
                                        },
                                        icon = { Icon(tab.icon, contentDescription = tab.label) },
                                        label = { Text(tab.label) }
                                    )
                                }
                            }
                        }
                    ) { innerPadding ->
                        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                            val currentFamily = family
                            val route = subRoute
                            val moduleDef = route?.let { ModuleByRoute[it] }

                            if (activeFamilyId.isBlank()) {
                                SetupScreen(profile = profile, familyRepository = familyRepository, onSignOut = signOut)
                            } else if (currentFamily == null) {
                                Column(
                                    modifier = Modifier.fillMaxSize(),
                                    verticalArrangement = Arrangement.Center,
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    CircularProgressIndicator()
                                    TextButton(onClick = signOut) { Text("Taking too long? Sign out") }
                                }
                            } else if (myStatus == "LOADING") {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator()
                                }
                            } else if (myStatus != "ACTIVE") {
                                PendingApprovalScreen(
                                    familyName = currentFamily.name,
                                    status = myStatus,
                                    onLeave = { scope.launch { familyRepository.switchActiveFamily(uid, "") } },
                                    onSignOut = signOut
                                )
                            } else if (route == "alerts") {
                                AlertsScreen(alerts = alerts, onBack = { subRoute = null }, onOpen = navigate)
                            } else if (route == "settings") {
                                SettingsScreen(
                                    family = currentFamily,
                                    user = profile,
                                    isOwner = isOwner,
                                    onBack = { subRoute = null },
                                    onSignOut = signOut
                                )
                            } else if (moduleDef != null) {
                                key(moduleDef.route) {
                                    ModuleScreen(
                                        def = moduleDef,
                                        familyId = activeFamilyId,
                                        uid = uid,
                                        userName = profile.name,
                                        memberNames = memberNames,
                                        monthlyBudget = currentFamily.monthlyBudget,
                                        isOwner = isOwner,
                                        repo = recordRepository,
                                        onBack = { subRoute = null },
                                        onSaveBudget = saveBudget
                                    )
                                }
                            } else {
                                when (selectedTab) {
                                    PrimaryTab.HOME -> Column(modifier = Modifier.fillMaxSize()) {
                                        AlertsStrip(
                                            alerts = alerts,
                                            onSeeAll = { subRoute = "alerts" },
                                            onOpen = navigate
                                        )
                                        Box(modifier = Modifier.weight(1f)) {
                                            HomeDashboardScreen(
                                                family = currentFamily,
                                                currentUser = profile,
                                                currentMember = currentMember,
                                                members = members,
                                                events = events,
                                                transactions = dashboardTransactions,
                                                documents = lockerDocs,
                                                tasks = tasks,
                                                trips = trips,
                                                onQuickNavigate = navigate
                                            )
                                        }
                                    }
                                    PrimaryTab.CALENDAR -> ModuleScreen(
                                        def = CalendarModule,
                                        familyId = activeFamilyId,
                                        uid = uid,
                                        userName = profile.name,
                                        memberNames = memberNames,
                                        monthlyBudget = currentFamily.monthlyBudget,
                                        isOwner = isOwner,
                                        repo = recordRepository,
                                        onBack = null
                                    )
                                    PrimaryTab.FAMILY -> FamilyScreen(
                                        family = currentFamily,
                                        members = members,
                                        currentUid = uid,
                                        isOwner = isOwner,
                                        onApprove = { memberUid ->
                                            scope.launch {
                                                try {
                                                    recordRepository.approveMember(activeFamilyId, memberUid)
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "Could not approve: ${e.message}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
                                        onRemove = { memberUid ->
                                            scope.launch {
                                                try {
                                                    recordRepository.removeMember(activeFamilyId, memberUid)
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "Could not remove: ${e.message}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        }
                                    )
                                    PrimaryTab.FINANCE -> ModuleScreen(
                                        def = FinanceModule,
                                        familyId = activeFamilyId,
                                        uid = uid,
                                        userName = profile.name,
                                        memberNames = memberNames,
                                        monthlyBudget = currentFamily.monthlyBudget,
                                        isOwner = isOwner,
                                        repo = recordRepository,
                                        onBack = null,
                                        onSaveBudget = saveBudget,
                                        onExport = {
                                            FinanceReportExporter.exportToCsvAndShare(context, currentFamily.name, transactions)
                                        }
                                    )
                                    PrimaryTab.MORE -> MoreHubGrid(onNavigate = navigate)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupScreen(
    profile: UserProfile,
    familyRepository: FamilyRepository,
    onSignOut: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var groupName by remember { mutableStateOf("") }
    var inviteCode by remember { mutableStateOf("") }
    var setupBusy by remember { mutableStateOf(false) }
    var setupMessage by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Set Up Your Family Group", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(
            value = groupName,
            onValueChange = { groupName = it },
            label = { Text("New Family Name") },
            modifier = Modifier.fillMaxWidth()
        )
        Button(
            enabled = !setupBusy,
            onClick = {
                if (groupName.isNotBlank()) {
                    setupBusy = true
                    setupMessage = null
                    scope.launch {
                        familyRepository.createFamilyGroup(profile, groupName)
                            .onFailure { setupMessage = "Could not create the family: ${it.message}" }
                        setupBusy = false
                    }
                } else {
                    setupMessage = "Enter a family name first."
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(if (setupBusy) "Please wait..." else "Create Family Group") }

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        OutlinedTextField(
            value = inviteCode,
            onValueChange = { inviteCode = it },
            label = { Text("Invite Code (e.g. FH-1A2B3C)") },
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedButton(
            enabled = !setupBusy,
            onClick = {
                if (inviteCode.isNotBlank()) {
                    setupBusy = true
                    setupMessage = null
                    scope.launch {
                        familyRepository.joinFamilyByCode(profile, inviteCode, "Family Member")
                            .onFailure { setupMessage = "Could not join the family: ${it.message}" }
                        setupBusy = false
                    }
                } else {
                    setupMessage = "Enter an invite code first."
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Join Existing Family") }

        val msg = setupMessage
        if (msg != null) {
            Text(
                text = msg,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        TextButton(onClick = onSignOut) { Text("Sign out / switch Google account") }
    }
}
