package com.familyhub.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.familyhub.app.core.theme.FamilyHubTheme
import com.familyhub.app.data.model.*
import com.familyhub.app.data.remote.AuthRepository
import com.familyhub.app.data.remote.FamilyRepository
import com.familyhub.app.ui.auth.LoginScreen
import com.familyhub.app.ui.navigation.MoreHubGrid
import com.familyhub.app.ui.navigation.PrimaryTab
import com.familyhub.app.ui.screens.HomeDashboardScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var authRepository: AuthRepository
    @Inject lateinit var familyRepository: FamilyRepository

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

                    // The profile document may be missing (e.g. the app was closed right after the first sign-in).
                    LaunchedEffect(uid) { familyRepository.ensureUserProfile(fbUser) }

                    // Flows are remembered per key so they are not recreated on every recomposition.
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

                    val currentMember = members.firstOrNull { it.uid == uid }
                        ?: FamilyMember(uid = uid, name = profile.name, role = FamilyRole.ADULT)
                    val isAdmin = currentMember.role == FamilyRole.OWNER || family?.ownerUid == uid
                    val documentsFlow = remember(activeFamilyId, uid, isAdmin) {
                        familyRepository.observeDocuments(activeFamilyId, uid, isAdmin)
                    }
                    val documents by documentsFlow.collectAsState(initial = emptyList())

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
                            if (activeFamilyId.isBlank() || currentFamily == null) {
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

                                    if (setupMessage != null) {
                                        Text(
                                            text = setupMessage!!,
                                            color = MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }

                                    TextButton(onClick = { scope.launch { authRepository.signOutAndSwitchAccount(context) } }) {
                                        Text("Sign out / switch Google account")
                                    }
                                }
                            } else if (subRoute != null) {
                                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("Module: ${subRoute!!.replace("_", " ").uppercase()}", style = MaterialTheme.typography.titleLarge)
                                    Text("Connected to ${currentFamily.name} • Invite Code: ${currentFamily.inviteCode}")
                                    Button(onClick = { subRoute = null }) { Text("Back") }
                                }
                            } else {
                                when (selectedTab) {
                                    PrimaryTab.HOME -> HomeDashboardScreen(
                                        family = currentFamily,
                                        currentUser = profile,
                                        currentMember = currentMember,
                                        members = members,
                                        events = events,
                                        transactions = transactions,
                                        documents = documents,
                                        tasks = tasks,
                                        trips = trips,
                                        onQuickNavigate = { route -> subRoute = route }
                                    )
                                    PrimaryTab.MORE -> MoreHubGrid(onNavigate = { route -> subRoute = route })
                                    else -> Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Text("${selectedTab.label} — ${currentFamily.name}", style = MaterialTheme.typography.headlineSmall)
                                        Text("Active Family ID: ${currentFamily.id}")
                                        Text("Invite Code: ${currentFamily.inviteCode}")
                                        OutlinedButton(onClick = { scope.launch { authRepository.signOutAndSwitchAccount(context) } }) {
                                            Text("Switch Google Account / Sign Out")
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
