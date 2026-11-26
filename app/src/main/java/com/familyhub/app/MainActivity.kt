@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.familyhub.app

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChatBubbleOutline
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.familyhub.app.core.CrashLog
import com.familyhub.app.core.security.AppLock
import com.familyhub.app.ui.AppEnv
import com.familyhub.app.ui.ExtraRoute
import com.familyhub.app.ui.isExtraRoute
import com.familyhub.app.ui.LockScreen
import com.familyhub.app.ui.stringList
import com.familyhub.app.core.theme.EntryIntro
import com.familyhub.app.core.theme.FamilyHubTheme
import com.familyhub.app.core.theme.GlassTab
import com.familyhub.app.core.theme.GlassTabBar
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
import com.familyhub.app.work.AlarmScheduler
import com.familyhub.app.health.StepService
import com.familyhub.app.health.StepTracker
import com.familyhub.app.core.ai.GeminiClient
import com.familyhub.app.ui.ai.AiBriefCard
import com.familyhub.app.data.model.FamilyGroup
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : FragmentActivity() {
    /**
     * Registered with the activity itself, so it exists every time the activity is created (also after it is
     * recreated). A launcher made inside the screen could be gone by the time it was used.
     */
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Asks once for notification permission on Android 13 and later, only while this screen is showing. */
    fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (isFinishing || isDestroyed || !lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) return
        try {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } catch (e: IllegalStateException) {
            android.util.Log.w("MainActivity", "Notification permission not asked", e)
        }
    }

    companion object {
        /** Screen to open, set by notifications (for example "health_hub" or "import"). */
        const val EXTRA_ROUTE = "open_route"
        val pendingRoute = androidx.compose.runtime.mutableStateOf<String?>(null)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_ROUTE)?.let { pendingRoute.value = it }
    }

    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var familyRepository: FamilyRepository
    @Inject lateinit var recordRepository: RecordRepository

    /** Leaving the app during a video call keeps the call in a small floating window. */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val call = com.familyhub.app.calling.CallManager.ui.value
        if (call.video && call.phase == com.familyhub.app.calling.CallPhase.ACTIVE && android.os.Build.VERSION.SDK_INT >= 26) {
            try {
                enterPictureInPictureMode(
                    android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(9, 16)).build()
                )
            } catch (_: Exception) { }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) intent?.getStringExtra(EXTRA_ROUTE)?.let { pendingRoute.value = it }
        setContent {
            FamilyHubTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope()
                var lastCrash by remember { mutableStateOf(CrashLog.read(context)) }
                var showIntro by rememberSaveable { mutableStateOf(true) }
                var unlocked by rememberSaveable { mutableStateOf(!AppLock.isEnabled(context)) }
                // Lock again when the app comes back after more than a minute in the background.
                val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    var stoppedAt = 0L
                    val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_STOP) stoppedAt = System.currentTimeMillis()
                        if (event == androidx.lifecycle.Lifecycle.Event.ON_START && stoppedAt > 0L &&
                            AppLock.isEnabled(context) && System.currentTimeMillis() - stoppedAt > 60_000L
                        ) {
                            unlocked = false
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                val firebaseUser by authRepository.authStateFlow.collectAsState(initial = null)
                var isLoading by remember { mutableStateOf(false) }
                var errorMessage by remember { mutableStateOf<String?>(null) }
                var selectedTab by remember { mutableStateOf(PrimaryTab.HOME) }
                var subRoute by remember { mutableStateOf<String?>(null) }
                // Screens opened from another screen (for example Medicines from the Health hub), so Back returns there.
                val backStack = remember { mutableStateListOf<String>() }
                val goBack: () -> Unit = {
                    subRoute = if (backStack.isEmpty()) null else backStack.removeAt(backStack.size - 1)
                }

                val fbUser = firebaseUser
                if (fbUser == null) {
                    LoginScreen(
                        enterDelayMillis = if (showIntro) 1100 else 0,
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

                    LaunchedEffect(uid) {
                        // This person's own AI key, decrypted on this phone (nobody else's).
                        com.familyhub.app.core.ai.AiSession.start(context, uid)
                        // Ask for notifications first, before any network call: asking after a slow call could
                        // reach a screen that was already closed, which crashed the app on Android 16.
                        askNotificationPermission()
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
                        // Best-effort chat notifications (about every 15 minutes; see ChatWorker).
                        try { com.familyhub.app.work.ChatWorker.schedule(context) } catch (e: Exception) { }
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
                    // Family records plus my own "Only me" records.
                    val itemsRaw = remember(activeFamilyId, uid) { recordRepository.observeMerged(activeFamilyId, uid, "items") }
                    val eventsRaw = remember(activeFamilyId, uid) { recordRepository.observeMerged(activeFamilyId, uid, "events") }
                    val expensesRaw = remember(activeFamilyId, uid) { recordRepository.observeMerged(activeFamilyId, uid, "expenses") }
                    val tasksRaw = remember(activeFamilyId, uid) { recordRepository.observeMerged(activeFamilyId, uid, "tasks") }
                    val tripsRaw = remember(activeFamilyId, uid) { recordRepository.observeMerged(activeFamilyId, uid, "trips") }
                    val userDocFlow = remember(uid) { recordRepository.observeUserDoc(uid) }
                    val userDoc by userDocFlow.collectAsState(initial = emptyMap<String, Any?>())
                    val itemRecs by itemsRaw.collectAsState(initial = emptyList<Rec>())
                    val eventRecs by eventsRaw.collectAsState(initial = emptyList<Rec>())
                    val expenseRecs by expensesRaw.collectAsState(initial = emptyList<Rec>())
                    val taskRecs by tasksRaw.collectAsState(initial = emptyList<Rec>())
                    val tripRecs by tripsRaw.collectAsState(initial = emptyList<Rec>())

                    val statusFlow = remember(activeFamilyId, uid) { recordRepository.observeMyStatus(activeFamilyId, uid) }
                    val myStatus by statusFlow.collectAsState(initial = "LOADING")

                    // Deleted records stay out of every screen (Recently deleted reads them on its own).
                    // Finance entries are kept whole: skipped dues are deleted entries the due calendar still needs.
                    val recordData = remember(itemRecs, eventRecs, expenseRecs, taskRecs, tripRecs) {
                        fun live(l: List<Rec>) = l.filter { it["isDeleted"] != true }
                        mapOf(
                            "items" to live(itemRecs),
                            "events" to live(eventRecs),
                            "expenses" to expenseRecs,
                            "tasks" to live(taskRecs),
                            "trips" to live(tripRecs)
                        )
                    }
                    val alerts = remember(recordData, userDoc, members) {
                        (allAlerts(recordData) + profileAlerts(userDoc) + memberAlerts(members, uid)).sortedBy { it.dateMillis }
                    }

                    // Exact-time alarms for medicines and reminders follow the records.
                    LaunchedEffect(itemRecs, eventRecs, activeFamilyId, profile.name) {
                        AlarmScheduler.scheduleAll(
                            context, itemRecs.filter { it["isDeleted"] != true }, activeFamilyId, uid,
                            events = eventRecs.filter { it["isDeleted"] != true && it[com.familyhub.app.data.remote.PRIVATE_FLAG] != true },
                            myName = profile.name
                        )
                    }
                    LaunchedEffect(uid, profile.familyIds, activeFamilyId) {
                        StepTracker.setFamilies(context, profile.familyIds + activeFamilyId)
                        if (StepTracker.isEnabled(context)) StepService.start(context)
                    }
                    // Choices for money fields: cards, accounts, categories and each category's subcategories.
                    val moneyOptions = remember(itemRecs) { com.familyhub.app.ui.money.moneyFieldOptions(itemRecs) }

                    val lockerDocs = remember(itemRecs) {
                        itemRecs.filter { it.str("type") == "document" && it["isDeleted"] != true }.map {
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

                    // Safety: low-battery checks, live sharing that Android paused, and a running arrival timer.
                    LaunchedEffect(uid, profile.name, activeFamilyId, myStatus) {
                        if (myStatus == "ACTIVE" && activeFamilyId.isNotBlank()) {
                            com.familyhub.app.safety.SafetyStartup.onAppOpen(context, uid, profile.name, activeFamilyId)
                        }
                    }

                    val currentMember = members.firstOrNull { it.uid == uid }
                        ?: FamilyMember(uid = uid, name = profile.name, role = FamilyRole.ADULT)
                    // The family's creator is its Super Admin; they can make others Family Admins.
                    val isOwner = family?.ownerUid == uid || currentMember.role == FamilyRole.OWNER
                    val isAdmin = isOwner || currentMember.isAdmin
                    // Admins keep the family's invite code working (needed for joining by code or QR).
                    LaunchedEffect(family?.id, family?.inviteCode, family?.name, isAdmin) {
                        val f = family
                        if (isAdmin && f != null) familyRepository.ensureInvite(f, uid)
                    }
                    val activeNames = remember(members) { members.filter { it.status == "ACTIVE" }.map { it.name } }
                    val memberNames = if (activeNames.isEmpty()) listOf(profile.name) else activeNames

                    val navigate: (String) -> Unit = { r ->
                        when (r) {
                            "health_hub" -> {
                                selectedTab = PrimaryTab.HEALTH
                                subRoute = null
                                backStack.clear()
                            }
                            "finance" -> {
                                selectedTab = PrimaryTab.FINANCE
                                subRoute = null
                                backStack.clear()
                            }
                            "family" -> {
                                selectedTab = PrimaryTab.FAMILY
                                subRoute = null
                                backStack.clear()
                            }
                            "chats" -> {
                                selectedTab = PrimaryTab.CHATS
                                subRoute = null
                                backStack.clear()
                            }
                            else -> {
                                val from = subRoute
                                if (from != null && from != r) {
                                    backStack.remove(r)
                                    backStack.add(from)
                                }
                                subRoute = r
                            }
                        }
                    }
                    // Families this person belongs to, for "share with which family".
                    var familyList by remember { mutableStateOf(emptyList<com.familyhub.app.data.remote.FamilyInfo>()) }
                    LaunchedEffect(profile.familyIds, activeFamilyId) {
                        familyList = try {
                            recordRepository.familyInfos((profile.familyIds + activeFamilyId).filter { it.isNotBlank() })
                        } catch (e: Exception) {
                            emptyList()
                        }
                    }
                    // Every page's AI button opens the one assistant with that page's context.
                    val askAi: (String, String) -> Unit = { page, text ->
                        com.familyhub.app.ui.ai.AiDraft.open(page, text)
                        navigate("assistant_family")
                    }
                    val signOut: () -> Unit = {
                        com.familyhub.app.core.ai.AiSession.signOut()
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

                    BackHandler(enabled = subRoute != null) { goBack() }

                    // Open the screen a notification asked for.
                    val pending by pendingRoute
                    LaunchedEffect(pending) {
                        val r = pending
                        if (r != null) {
                            pendingRoute.value = null
                            navigate(r)
                        }
                    }

                    fun envFor(fam: FamilyGroup) = AppEnv(
                        uid = uid,
                        profile = profile,
                        userDoc = userDoc,
                        familyId = activeFamilyId,
                        family = fam,
                        members = members,
                        memberNames = memberNames,
                        isOwner = isOwner,
                        isAdmin = isAdmin,
                        data = recordData,
                        repo = recordRepository,
                        familyRepository = familyRepository,
                        scope = scope,
                        onBack = goBack,
                        navigate = navigate
                    )

                    // Chats with new messages from others, for the red count on the Chats tab. Uses the same
                    // listener as the chat list (Firestore shares it), so it costs no extra reads.
                    val chatList by remember(activeFamilyId, uid, myStatus) {
                        if (activeFamilyId.isBlank() || uid.isBlank() || myStatus != "ACTIVE") kotlinx.coroutines.flow.flowOf(emptyList<com.familyhub.app.data.remote.ChatRepository.Chat>())
                        else com.familyhub.app.data.remote.ChatRepository.observeChats(activeFamilyId, uid)
                    }.collectAsState(initial = emptyList())
                    val chatBadge = remember(chatList, com.familyhub.app.ui.chat.ChatPrefs.seenTick.intValue) {
                        com.familyhub.app.ui.chat.ChatPrefs.unreadChats(context, chatList, uid)
                    }

                    Scaffold(
                        containerColor = Color.Transparent,
                        bottomBar = {
                            // A conversation uses the whole screen for messages and the keyboard, like other messengers.
                            if (subRoute?.startsWith("chat:") != true) GlassTabBar(
                                tabs = PrimaryTab.entries.map { GlassTab(it.label, it.icon, if (it == PrimaryTab.CHATS) chatBadge else 0) },
                                selectedIndex = selectedTab.ordinal,
                                onSelect = { i ->
                                    selectedTab = PrimaryTab.entries[i]
                                    subRoute = null
                                    backStack.clear()
                                }
                            )
                        }
                    ) { innerPadding ->
                        Box(modifier = Modifier.fillMaxSize().padding(innerPadding).consumeWindowInsets(innerPadding)) {
                            val currentFamily = family
                            // Each page gets its own large-title state; scrolling any list on it shrinks the title.
                            val titleCollapse = remember(selectedTab, subRoute) { com.familyhub.app.core.theme.TitleCollapse() }
                            androidx.compose.runtime.CompositionLocalProvider(com.familyhub.app.core.theme.LocalTitleCollapse provides titleCollapse) {
                            // Screens cross-fade and settle into place when switching tabs or opening a module.
                            AnimatedContent(
                                targetState = selectedTab to subRoute,
                                modifier = Modifier.fillMaxSize().nestedScroll(titleCollapse.connection),
                                transitionSpec = {
                                    (fadeIn(tween(280)) + scaleIn(tween(280), initialScale = 0.97f)) togetherWith
                                        fadeOut(tween(160))
                                },
                                label = "screen"
                            ) { (shownTab, route) ->
                            // "finance_all" is the full Finance list (bills, EMIs, transfers and more) behind the new dashboard.
                            val moduleDef = route?.let { if (it == "finance_all") FinanceModule else ModuleByRoute[it] }
                            val openPageAi: (String) -> Unit = { title -> askAi(title, "") }
                            val pageAi: ((String) -> Unit)? = if (route?.startsWith("assistant") == true) null else openPageAi
                            androidx.compose.runtime.CompositionLocalProvider(com.familyhub.app.ui.ai.LocalPageAi provides pageAi) {

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
                                AlertsScreen(alerts = alerts, onBack = goBack, onOpen = navigate)
                            } else if (route == "settings") {
                                SettingsScreen(
                                    family = currentFamily,
                                    user = profile,
                                    isOwner = isOwner,
                                    onBack = goBack,
                                    onSignOut = signOut,
                                    onOpen = navigate
                                )
                            } else if (isExtraRoute(route) && route != null) {
                                ExtraRoute(route = route, env = envFor(currentFamily))
                            } else if (moduleDef != null) {
                                val financeOverview: @Composable () -> Unit = { com.familyhub.app.ui.money.FinanceOverview(envFor(currentFamily)) }
                                key(moduleDef.route) {
                                    ModuleScreen(
                                        overview = if (moduleDef.kind == "finance") financeOverview else null,
                                        def = moduleDef,
                                        familyId = activeFamilyId,
                                        uid = uid,
                                        userName = profile.name,
                                        memberNames = memberNames,
                                        monthlyBudget = currentFamily.monthlyBudget,
                                        isOwner = isOwner,
                                        repo = recordRepository,
                                        onBack = goBack,
                                        onSaveBudget = saveBudget,
                                        onOpen = navigate,
                                        extraOptions = moneyOptions,
                                        families = familyList,
                                        onAskAi = { t -> askAi(moduleDef.title, t) }
                                    )
                                }
                            } else {
                                when (shownTab) {
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
                                                onQuickNavigate = navigate,
                                                aiCard = if (GeminiClient.isConfigured()) {
                                                    { AiBriefCard(envFor(currentFamily)) }
                                                } else {
                                                    null
                                                },
                                                records = recordData,
                                                homeOrder = userDoc.stringList("homeOrder"),
                                                homeHidden = userDoc.stringList("homeHidden").toSet(),
                                                duesCard = { com.familyhub.app.ui.money.HomeDues(envFor(currentFamily)) },
                                                todayCard = { com.familyhub.app.ui.screens.TodayCard(envFor(currentFamily)) },
                                                glanceCard = { fv ->
                                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                                        // New chat messages, from the same count as the Chats tab (no extra reads).
                                                        if (chatBadge > 0) com.familyhub.app.core.theme.GlassCard(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp),
                                                            onClick = { navigate("chats") }
                                                        ) {
                                                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                                                Icon(Icons.Rounded.ChatBubbleOutline, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                                                Spacer(Modifier.width(10.dp))
                                                                Text(
                                                                    if (chatBadge == 1) "1 chat has new messages" else "$chatBadge chats have new messages",
                                                                    style = MaterialTheme.typography.titleSmall,
                                                                    modifier = Modifier.weight(1f)
                                                                )
                                                                com.familyhub.app.core.theme.CountBadge(chatBadge)
                                                            }
                                                        }
                                                        com.familyhub.app.ui.money.HomeGlance(envFor(currentFamily), fv)
                                                    }
                                                }
                                            )
                                        }
                                    }
                                    PrimaryTab.HEALTH -> com.familyhub.app.ui.health.HealthScreen(envFor(currentFamily), showBack = false)
                                    PrimaryTab.CHATS -> com.familyhub.app.ui.chat.ChatListScreen(envFor(currentFamily)) { id -> navigate("chat:$id") }
                                    PrimaryTab.FAMILY -> FamilyScreen(
                                        family = currentFamily,
                                        members = members,
                                        currentUid = uid,
                                        isOwner = isOwner,
                                        isAdmin = isAdmin,
                                        onSetAdmin = { memberUid, admin ->
                                            scope.launch {
                                                try {
                                                    recordRepository.setMemberAdmin(activeFamilyId, memberUid, admin)
                                                } catch (e: Exception) {
                                                    Toast.makeText(context, "Could not change: ${e.message}", Toast.LENGTH_LONG).show()
                                                }
                                            }
                                        },
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
                                    PrimaryTab.FINANCE -> com.familyhub.app.ui.money.FinanceDashboard(envFor(currentFamily))
                                    PrimaryTab.MORE -> MoreHubGrid(
                                        onNavigate = navigate,
                                        order = userDoc.stringList("moduleOrder"),
                                        hidden = userDoc.stringList("hiddenModules").toSet(),
                                        onEdit = { subRoute = "arrange" }
                                    )
                                }
                            }
                            }
                            }
                            }
                        }

                    }
                    // Voice and video calls (Beta): they ring and run above every screen while the app is open.
                    LaunchedEffect(activeFamilyId, uid, myStatus, profile.name) {
                        if (activeFamilyId.isNotBlank() && uid.isNotBlank() && myStatus == "ACTIVE")
                            com.familyhub.app.calling.CallManager.attach(context, activeFamilyId, uid, profile.name.ifBlank { "Family member" })
                        else com.familyhub.app.calling.CallManager.detach()
                    }
                    DisposableEffect(uid) { onDispose { com.familyhub.app.calling.CallManager.detach() } }
                    com.familyhub.app.ui.calls.CallOverlay(family?.let { envFor(it) })
                }

                // Fingerprint / screen-lock gate, if the person turned on the app lock.
                if (firebaseUser != null && !unlocked) {
                    LockScreen(onUnlock = {
                        AppLock.authenticate(
                            context, "Unlock Family Hub", "Confirm it's you",
                            onSuccess = { unlocked = true },
                            onFail = { msg -> Toast.makeText(context, msg, Toast.LENGTH_SHORT).show() }
                        )
                    })
                }

                // Opening animation shown once when the app starts.
                AnimatedVisibility(
                    visible = showIntro,
                    enter = EnterTransition.None,
                    exit = fadeOut(tween(450)) + scaleOut(tween(450), targetScale = 1.08f)
                ) {
                    EntryIntro(onFinished = { showIntro = false })
                }

                // If the app closed unexpectedly last time, show what went wrong so it can be shared.
                val crashText = lastCrash
                if (crashText != null) {
                    LastCrashDialog(
                        text = crashText,
                        onShare = {
                            try {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_SUBJECT, "Family Hub crash report")
                                    putExtra(Intent.EXTRA_TEXT, crashText)
                                }
                                context.startActivity(Intent.createChooser(send, "Share crash report"))
                            } catch (e: Exception) {
                                Toast.makeText(context, "Could not open sharing.", Toast.LENGTH_LONG).show()
                            }
                        },
                        onClose = {
                            CrashLog.clear(context)
                            lastCrash = null
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun LastCrashDialog(text: String, onShare: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Family Hub closed unexpectedly last time") },
        text = {
            Column(modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                Text(
                    text = "Please share this report so the problem can be fixed.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(text = text.take(4000), style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = onShare) { Text("Share report") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Close") } }
    )
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
        val scanContext = androidx.compose.ui.platform.LocalContext.current
        TextButton(
            enabled = !setupBusy,
            onClick = {
                com.familyhub.app.ui.common.InviteQr.scan(
                    scanContext,
                    onCode = { inviteCode = it; setupMessage = null },
                    onError = { setupMessage = it }
                )
            }
        ) { Text("Scan the family's QR code") }
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
