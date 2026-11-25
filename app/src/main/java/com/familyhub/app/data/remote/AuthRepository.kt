package com.familyhub.app.data.remote

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import com.familyhub.app.BuildConfig
import com.familyhub.app.data.model.UserProfile
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import androidx.credentials.exceptions.NoCredentialException
import androidx.credentials.exceptions.GetCredentialException
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.tasks.await
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AuthRepository @Inject constructor(
    private val auth: FirebaseAuth,
    private val firestore: FirebaseFirestore
) {
    val authStateFlow: Flow<FirebaseUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { trySend(it.currentUser) }
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    fun observeUserProfile(uid: String): Flow<UserProfile?> = callbackFlow {
        val reg = firestore.collection("users").document(uid)
            .addSnapshotListener { snap, _ ->
                trySend(snap?.toObject(UserProfile::class.java))
            }
        awaitClose { reg.remove() }
    }

    suspend fun signInWithGoogle(context: Context): Result<UserProfile> {
        return try {
            val webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID.trim()
            if (webClientId.isEmpty()) {
                return Result.failure(IllegalStateException(
                    "GOOGLE_WEB_CLIENT_ID is empty in this build. Add it under GitHub > Settings > Secrets > Actions and rebuild."))
            }
            val credentialManager = CredentialManager.create(context)
            // Explicit "Continue with Google" button flow: always shows the account chooser.
            val signInOption = GetSignInWithGoogleOption.Builder(webClientId).build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(signInOption)
                .build()

            val result = credentialManager.getCredential(context = context, request = request)
            val credential = result.credential

            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleCred = GoogleIdTokenCredential.createFrom(credential.data)
                val firebaseCred = GoogleAuthProvider.getCredential(googleCred.idToken, null)
                val authResult = auth.signInWithCredential(firebaseCred).await()
                val fbUser = authResult.user ?: return Result.failure(IllegalStateException("Missing Firebase user"))

                val existingSnap = firestore.collection("users").document(fbUser.uid).get().await()
                val existing = existingSnap.toObject(UserProfile::class.java)
                val profile = (existing ?: UserProfile(uid = fbUser.uid)).copy(
                    uid = fbUser.uid,
                    name = fbUser.displayName ?: existing?.name ?: "Family Member",
                    email = fbUser.email ?: existing?.email.orEmpty(),
                    photoUrl = fbUser.photoUrl?.toString() ?: existing?.photoUrl.orEmpty()
                )

                firestore.collection("users").document(fbUser.uid)
                    .set(profile, SetOptions.merge())
                    .await()

                Result.success(profile)
            } else {
                Result.failure(IllegalArgumentException("Unsupported credential type"))
            }
        } catch (e: GetCredentialCancellationException) {
            Result.failure(Exception("Sign-in cancelled."))
        } catch (e: NoCredentialException) {
            Result.failure(Exception(
                "No Google credential (${e.type}). Usually the APK signing SHA-1 or the Web client ID " +
                "does not match Firebase, or no Google account is added on this phone."))
        } catch (e: GetCredentialException) {
            Result.failure(Exception("${e.javaClass.simpleName} (${e.type}): ${e.message}"))
        } catch (e: Exception) {
            Result.failure(Exception("${e.javaClass.simpleName}: ${e.message}"))
        }
    }

    suspend fun signOutAndSwitchAccount(context: Context) {
        val credentialManager = CredentialManager.create(context)
        credentialManager.clearCredentialState(ClearCredentialStateRequest())
        auth.signOut()
    }

    suspend fun deleteAccountWithOwnershipCheck(activeFamilyId: String): Result<Unit> {
        val user = auth.currentUser ?: return Result.failure(Exception("Not signed in"))
        if (activeFamilyId.isNotBlank()) {
            val familySnap = firestore.collection("families").document(activeFamilyId).get().await()
            if (familySnap.getString("ownerUid") == user.uid) {
                return Result.failure(Exception("Transfer Family Ownership before deleting your account."))
            }
            firestore.collection("families").document(activeFamilyId)
                .collection("members").document(user.uid).delete().await()
        }
        firestore.collection("users").document(user.uid).delete().await()
        user.delete().await()
        return Result.success(Unit)
    }
}
