package dev.lastplace.app.data

import dev.lastplace.app.data.db.ParkingSession
import dev.lastplace.app.data.db.ParkingSessionDao
import dev.lastplace.app.data.db.Street
import dev.lastplace.app.data.db.CleaningRule
import dev.lastplace.app.data.db.StreetDao
import dev.lastplace.app.data.db.StreetWithRules
import kotlinx.coroutines.flow.Flow
import java.time.Instant

enum class ParkSource { AUTO, MANUAL }

class StreetRepository(private val dao: StreetDao) {

    fun observeStreetsWithRules(): Flow<List<StreetWithRules>> = dao.observeStreetsWithRules()

    suspend fun getStreetWithRules(id: Long): StreetWithRules? = dao.getStreetWithRules(id)

    /** Inserts a street and its rules; the rules are re-pointed to the new street id. */
    suspend fun addStreet(street: Street, rules: List<CleaningRule>) {
        val streetId = dao.insertStreet(street)
        rules.forEach { dao.insertRule(it.copy(streetId = streetId)) }
    }

    /** Updates a street and replaces all of its cleaning rules. */
    suspend fun updateStreet(street: Street, rules: List<CleaningRule>) {
        dao.updateStreet(street)
        dao.deleteRulesForStreet(street.id)
        rules.forEach { dao.insertRule(it.copy(streetId = street.id)) }
    }

    suspend fun deleteStreet(street: Street) = dao.deleteStreet(street)
}

class ParkingRepository(private val dao: ParkingSessionDao) {

    fun observeActiveSession(): Flow<ParkingSession?> = dao.observeActiveSession()

    suspend fun getActiveSession(): ParkingSession? = dao.getActiveSession()

    suspend fun startSession(
        streetId: Long,
        parkedAt: Instant,
        deadline: Instant?,
        source: ParkSource,
    ): Long = dao.insert(
        ParkingSession(
            streetId = streetId,
            parkedAt = parkedAt.toEpochMilli(),
            deadline = deadline?.toEpochMilli(),
            source = source.name,
        ),
    )

    suspend fun endSession(id: Long, endedAt: Instant) = dao.endSession(id, endedAt.toEpochMilli())
}
