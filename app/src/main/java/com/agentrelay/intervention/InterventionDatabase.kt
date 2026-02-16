package com.agentrelay.intervention

import android.content.Context
import androidx.room.*

@Entity(tableName = "user_interventions")
data class UserIntervention(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val taskDescription: String = "",
    val currentApp: String = "",
    val currentAppPackage: String = "",

    // Planned action (from agent)
    val plannedAction: String = "",        // SemanticAction name
    val plannedElementId: String? = null,
    val plannedElementText: String? = null,
    val plannedX: Int? = null,
    val plannedY: Int? = null,
    val plannedText: String? = null,       // for TYPE actions
    val plannedDescription: String = "",

    // Actual user action (from accessibility event)
    val actualEventType: Int = 0,
    val actualClassName: String? = null,
    val actualPackage: String? = null,
    val actualText: String? = null,
    val actualX: Int? = null,
    val actualY: Int? = null,

    // Match result
    val matchType: String = "INTERVENTION",   // "CONFIRMED" or "INTERVENTION"
    val matchConfidence: Float = 0f,           // 0.0 - 1.0

    // Context snapshot (compact JSON of visible element IDs + texts)
    val elementMapSnapshot: String? = null
)

@Dao
interface InterventionDao {
    @Insert
    suspend fun insert(intervention: UserIntervention): Long

    @Query("SELECT * FROM user_interventions ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<UserIntervention>

    @Query("SELECT * FROM user_interventions WHERE matchType = :type ORDER BY timestamp DESC")
    suspend fun getByType(type: String): List<UserIntervention>

    @Query("SELECT COUNT(*) FROM user_interventions")
    suspend fun getCount(): Int

    @Query("SELECT * FROM user_interventions ORDER BY timestamp ASC")
    suspend fun exportAll(): List<UserIntervention>
}

@Database(entities = [UserIntervention::class], version = 1, exportSchema = false)
abstract class InterventionDatabase : RoomDatabase() {
    abstract fun interventionDao(): InterventionDao

    companion object {
        @Volatile
        private var INSTANCE: InterventionDatabase? = null

        fun getInstance(context: Context): InterventionDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    InterventionDatabase::class.java,
                    "intervention_database"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
