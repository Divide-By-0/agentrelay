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

@Entity(tableName = "agent_trace_events")
data class AgentTraceEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val taskDescription: String = "",
    val eventType: String = "",   // PLANNING, STEP_EXECUTED, STEP_FAILED, ERROR, TASK_START, TASK_END
    val action: String? = null,   // SemanticAction name
    val elementId: String? = null,
    val description: String = "",
    val reasoning: String? = null, // LLM reasoning text
    val confidence: String? = null, // high/medium/low
    val currentApp: String? = null,
    val currentAppPackage: String? = null,
    val iteration: Int = 0,
    val stepIndex: Int = 0,
    val success: Boolean = true,
    val failureReason: String? = null,
    val planSteps: String? = null  // compact summary of planned steps
)

@Entity(tableName = "user_clarifications")
data class UserClarification(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val taskDescription: String = "",
    val iteration: Int = 0,
    val defaultPath: String = "",
    val alternativePath: String = "",
    val userChose: String = "",  // "default", "alternative", "timeout"
    val confidence: String = "",
    val elementMapSnapshot: String? = null
)

@Dao
interface UserClarificationDao {
    @Insert
    suspend fun insert(clarification: UserClarification): Long

    @Query("SELECT * FROM user_clarifications ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<UserClarification>

    @Query("SELECT * FROM user_clarifications ORDER BY timestamp ASC")
    suspend fun exportAll(): List<UserClarification>

    @Query("SELECT COUNT(*) FROM user_clarifications")
    suspend fun getCount(): Int
}

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

    @Query("DELETE FROM user_interventions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM user_interventions ORDER BY timestamp ASC")
    suspend fun exportAll(): List<UserIntervention>
}

@Dao
interface AgentTraceDao {
    @Insert
    suspend fun insert(event: AgentTraceEvent): Long

    @Query("SELECT * FROM agent_trace_events ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 200): List<AgentTraceEvent>

    @Query("SELECT COUNT(*) FROM agent_trace_events")
    suspend fun getCount(): Int

    @Query("DELETE FROM agent_trace_events WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("SELECT * FROM agent_trace_events ORDER BY timestamp ASC")
    suspend fun exportAll(): List<AgentTraceEvent>

    @Query("DELETE FROM agent_trace_events")
    suspend fun deleteAll()
}

@Database(
    entities = [UserIntervention::class, AgentTraceEvent::class, UserClarification::class],
    version = 3,
    exportSchema = false
)
abstract class InterventionDatabase : RoomDatabase() {
    abstract fun interventionDao(): InterventionDao
    abstract fun agentTraceDao(): AgentTraceDao
    abstract fun userClarificationDao(): UserClarificationDao

    companion object {
        @Volatile
        private var INSTANCE: InterventionDatabase? = null

        fun getInstance(context: Context): InterventionDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    InterventionDatabase::class.java,
                    "intervention_database"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}
