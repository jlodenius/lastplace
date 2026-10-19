package dev.lastplace.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import dev.lastplace.app.domain.ReminderSpec
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "planned_moves")
data class PlannedMove(
    @PrimaryKey val sessionId: Long,
    val id: String,
    val streetId: Long,
    val streetName: String,
    val moveAt: Long,
    val justCleaned: Boolean,
    val rulesSignature: String,
    val invalidReason: String? = null,
)

@Entity(tableName = "scheduled_reminders")
data class ScheduledReminder(
    @PrimaryKey val key: String,
    @Embedded val spec: ReminderSpec,
    @ColumnInfo(defaultValue = "0") val delivered: Boolean = false,
)

@Dao
interface ReminderDao {
    @Query("SELECT * FROM planned_moves")
    fun observePlans(): Flow<List<PlannedMove>>

    @Query("SELECT * FROM planned_moves WHERE sessionId = :sessionId")
    suspend fun getPlan(sessionId: Long): PlannedMove?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun savePlan(plan: PlannedMove)

    @Query("DELETE FROM planned_moves WHERE sessionId = :sessionId AND id = :planId")
    suspend fun deletePlan(sessionId: Long, planId: String)

    @Query("DELETE FROM planned_moves WHERE sessionId != :sessionId")
    suspend fun deleteOtherPlans(sessionId: Long)

    @Query("SELECT * FROM scheduled_reminders")
    suspend fun getReminders(): List<ScheduledReminder>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveReminder(reminder: ScheduledReminder)

    @Query("DELETE FROM scheduled_reminders WHERE `key` = :key")
    suspend fun deleteReminder(key: String)
}

fun StreetWithRules.rulesSignature(): String = rules.map {
    "${it.dayOfWeek}:${it.startMinuteOfDay}:${it.endMinuteOfDay}"
}.sorted().joinToString(";")
