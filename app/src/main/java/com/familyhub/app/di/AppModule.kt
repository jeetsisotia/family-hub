package com.familyhub.app.di

import android.content.Context
import androidx.room.Room
import com.familyhub.app.data.local.FamilyCacheDao
import com.familyhub.app.data.local.FamilyHubDatabase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideFirebaseAuth(): FirebaseAuth = FirebaseAuth.getInstance()

    @Provides
    @Singleton
    fun provideFirestore(): FirebaseFirestore = FirebaseFirestore.getInstance()

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FamilyHubDatabase =
        Room.databaseBuilder(context, FamilyHubDatabase::class.java, "family_hub_cache.db")
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    fun provideCacheDao(db: FamilyHubDatabase): FamilyCacheDao = db.cacheDao()
}
