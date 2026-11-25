package com.familyhub.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
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

                if (firebaseUser == null) {
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
                    val userProfile by authRepository.observeUserProfile(firebaseUser!!.uid)
                        .collectAsState(initial = UserProfile(uid = firebaseUser!!.uid, name = firebaseUser!!.displayName ?: "Member"))

                    val activeFamilyId = userProfile?.activeFamilyId.orEmpty()
                    val family by familyRepository.observeFamily(activeFamilyId).collectAsState(initial = null)
                    val members by familyRepository.observeMembers(activeFamilyId).collectAsState(initial = emptyList())
                    val events by familyRepository.observeEvents(activeFamilyId).collectAsState(initial = emptyList())
                    val transactions by familyRepository.observeTransactions(activeFamilyId).collectAsState(initial = emptyList())
                    val tasks by familyRepository.observeTasks(activeFamilyId).collectAsState(initial = emptyList())
                    val trips by familyRepository.observeTrips(activeFamilyId).collectAsState(initial = emptyList())

                    val currentMember = members.firstOrNull { it.uid == firebaseUser!!.uid }
                        ?: FamilyMember(uid = firebaseUser!!.uid, name = userProfile?.name ?: "Member", role = FamilyRole.ADULT)
                    val isAdmin = currentMember.role == FamilyRole.OWNER || family?.ownerUid == firebaseUser!!.uid
                    val documents by familyRepository.observeDocuments(activeFamilyId, firebaseUser!!.uid, isAdmin)
                        .collectAsState(initial = emptyList())

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
                            if (activeFamilyId.isBlank() || family == null) {
                                var groupName by remember { mutableStateOf("") }
                                var inviteCode by remember { mutableStateOf("") }
                                Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("Set Up Your Family Group", style = MaterialTheme.typography.headlineSmall)
                                    OutlinedTextField(
                                        value = groupName,
                                        onValueChange = { groupName = it },
                                        label = { Text("New Family Name") },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    Button(
                                        onClick = {
                                            if (groupName.isNotBlank()) {
                                                scope.launch {
                                                    familyRepository.createFamilyGroup(userProfile!!, groupName)
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Create Family Group") }

                                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                                    OutlinedTextField(
                                        value = inviteCode,
                                        onValueChange = { inviteCode = it },
                                        label = { Text("Invite Code (e.g. FH-1A2B3C)") },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedButton(
                                        onClick = {
                                            if (inviteCode.isNotBlank()) {
                                                scope.launch {
                                                    familyRepository.joinFamilyByCode(userProfile!!, inviteCode, "Family Member")
                                                }
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("Join Existing Family") }
                                }
                            } else if (subRoute != null) {
                                Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    Text("Module: ${subRoute!!.replace("_", " ").uppercase()}", style = MaterialTheme.typography.titleLarge)
                                    Text("Connected to ${family!!.name} • Invite Code: ${family!!.inviteCode}")
                                    Button(onClick = { subRoute = null }) { Text("Back") }
                                }
                            } else {
                                when (selectedTab) {
                                    PrimaryTab.HOME -> HomeDashboardScreen(
                                        family = family!!,
                                        currentUser = userProfile!!,
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
                                        Text("${selectedTab.label} — ${family!!.name}", style = MaterialTheme.typography.headlineSmall)
                                        Text("Active Family ID: ${family!!.id}")
                                        Text("Invite Code: ${family!!.inviteCode}")
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
