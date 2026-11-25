package com.familyhub.app.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "cached_expenses")
data class CachedExpenseEntity(
    @PrimaryKey val id: String,
    val familyId: String,
    val title: String,
    val amountInr: Double,
    val category: String,
    val memberName: String,
    val type: String,
    val dateMillis: Long,
    val pendingSync: Boolean = false
)

@Dao
interface FamilyCacheDao {
    @Query("SELECT * FROM cached_expenses WHERE familyId = :familyId ORDER BY dateMillis DESC")
    fun observeCachedExpenses(familyId: String): Flow<List<CachedExpenseEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CachedExpenseEntity>)

    @Query("SELECT * FROM cached_expenses WHERE pendingSync = 1")
    suspend fun getPendingSyncItems(): List<CachedExpenseEntity>
}

@Database(entities = [CachedExpenseEntity::class], version = 1, exportSchema = false)
abstract class FamilyHubDatabase : RoomDatabase() {
    abstract fun cacheDao(): FamilyCacheDao
}
