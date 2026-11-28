package com.familyhub.app

import android.app.Application
import com.familyhub.app.core.CrashLog
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class FamilyHubApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        FirebaseApp.initializeApp(this)
        val settings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(com.google.firebase.firestore.PersistentCacheSettings.newBuilder().build())
            .build()
        FirebaseFirestore.getInstance().firestoreSettings = settings
        // Online or offline, for the sync notice (changes made offline are kept and sent later by Firestore).
        com.familyhub.app.data.remote.SyncStatus.watch(this)
    }
}
