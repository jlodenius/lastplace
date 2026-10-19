package dev.lastplace.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface StreetDao {

    @Transaction
    @Query("SELECT * FROM streets ORDER BY name")
    fun observeStreetsWithRules(): Flow<List<StreetWithRules>>

    @Transaction
    @Query("SELECT * FROM streets WHERE id = :id")
    suspend fun getStreetWithRules(id: Long): StreetWithRules?

    @Transaction
    @Query("SELECT * FROM streets ORDER BY name")
    suspend fun getStreetsWithRules(): List<StreetWithRules>

    @Query("UPDATE streets SET isFavourite = NOT isFavourite WHERE id = :id")
    suspend fun toggleFavourite(id: Long)

    @Transaction
    suspend fun insertStreetWithRules(street: Street, rules: List<CleaningRule>) {
        val streetId = insertStreet(street)
        rules.forEach { insertRule(it.copy(streetId = streetId)) }
    }

    @Transaction
    suspend fun updateStreetWithRules(street: Street, rules: List<CleaningRule>) {
        val existing = getStreetWithRules(street.id) ?: return
        updateStreet(street.copy(isFavourite = existing.street.isFavourite))
        deleteRulesForStreet(street.id)
        rules.forEach { insertRule(it.copy(streetId = street.id)) }
    }

    @Insert
    suspend fun insertStreet(street: Street): Long

    @Update
    suspend fun updateStreet(street: Street)

    @Delete
    suspend fun deleteStreet(street: Street)

    @Insert
    suspend fun insertRule(rule: CleaningRule): Long

    @Query("DELETE FROM cleaning_rules WHERE streetId = :streetId")
    suspend fun deleteRulesForStreet(streetId: Long)
}

@Dao
interface ParkingSessionDao {

    @Query("SELECT * FROM parking_sessions WHERE endedAt IS NULL ORDER BY parkedAt DESC LIMIT 1")
    fun observeActiveSession(): Flow<ParkingSession?>

    @Query("SELECT * FROM parking_sessions WHERE endedAt IS NULL ORDER BY parkedAt DESC LIMIT 1")
    suspend fun getActiveSession(): ParkingSession?

    @Insert
    suspend fun insert(session: ParkingSession): Long

    @Query("UPDATE parking_sessions SET deadline = :deadline WHERE id = :id AND endedAt IS NULL")
    suspend fun updateDeadline(id: Long, deadline: Long?)

    @Query("UPDATE parking_sessions SET endedAt = :endedAt WHERE id = :id")
    suspend fun endSession(id: Long, endedAt: Long)
}
