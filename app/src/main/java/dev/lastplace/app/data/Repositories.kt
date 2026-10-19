package dev.lastplace.app.data

import dev.lastplace.app.data.db.ParkingSession
import dev.lastplace.app.data.db.ParkingSessionDao
import dev.lastplace.app.data.db.Street
import dev.lastplace.app.data.db.CleaningRule
import dev.lastplace.app.data.db.StreetDao
import dev.lastplace.app.data.db.StreetWithRules
import dev.lastplace.app.data.db.toWindow
import dev.lastplace.app.data.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import java.time.Instant

enum class ParkSource { AUTO, MANUAL }

class StreetRepository(
    private val dao: StreetDao,
    private val settingsRepository: SettingsRepository,
) {

    fun observeStreetsWithRules(): Flow<List<StreetWithRules>> = dao.observeStreetsWithRules()

    suspend fun getStreetWithRules(id: Long): StreetWithRules? = dao.getStreetWithRules(id)

    /** Inserts a street and its rules; the rules are re-pointed to the new street id. */
    suspend fun addStreet(street: Street, rules: List<CleaningRule>) {
        dao.insertStreetWithRules(street, rules)
    }

    /** Updates a street and replaces all of its cleaning rules. */
    suspend fun updateStreet(street: Street, rules: List<CleaningRule>) {
        dao.updateStreetWithRules(street, rules)
        pruneReparkingTimes()
    }

    suspend fun deleteStreet(street: Street) {
        dao.deleteStreet(street)
        pruneReparkingTimes()
    }

    suspend fun toggleFavourite(id: Long) = dao.toggleFavourite(id)

    private suspend fun pruneReparkingTimes() {
        val times = dao.getStreetsWithRules().flatMap { street -> street.rules.map { it.toWindow().end } }.toSet()
        settingsRepository.retainReparkingTimes(times)
    }
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
