package dev.lastplace.app

import android.app.Application
import android.content.Context
import dev.lastplace.app.data.ParkingRepository
import dev.lastplace.app.data.StreetRepository
import dev.lastplace.app.data.db.ParkingDatabase
import dev.lastplace.app.data.settings.SettingsRepository
import dev.lastplace.app.domain.DeadlineCalculator
import dev.lastplace.app.domain.ScheduleEngine
import dev.lastplace.app.domain.StreetMatcher
import dev.lastplace.app.location.LocationProvider
import dev.lastplace.app.osm.OsmService
import dev.lastplace.app.parking.ParkingService
import dev.lastplace.app.reminders.Notifications
import dev.lastplace.app.reminders.ReminderScheduler
import dev.lastplace.app.reminders.ReminderCoordinator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Manual dependency container. Small enough that Hilt isn't worth the build cost yet;
 * swap in Hilt later if the graph grows.
 */
class AppContainer(context: Context) {
    private val database = ParkingDatabase.get(context)

    val scheduleEngine = ScheduleEngine()
    val deadlineCalculator = DeadlineCalculator(scheduleEngine)
    val streetMatcher = StreetMatcher()

    val settingsRepository = SettingsRepository(context)
    val streetRepository = StreetRepository(database.streetDao(), settingsRepository)
    val parkingRepository = ParkingRepository(database.parkingSessionDao())

    val osmService = OsmService()
    val locationProvider = LocationProvider(context)

    val reminderScheduler = ReminderScheduler(context)
    val reminderCoordinator = ReminderCoordinator(
        database.reminderDao(), parkingRepository, streetRepository, settingsRepository, reminderScheduler, scheduleEngine,
    )
    val parkingService = ParkingService(
        streetRepository = streetRepository,
        parkingRepository = parkingRepository,
        deadlineCalculator = deadlineCalculator,
        reminderCoordinator = reminderCoordinator,
        reminderScheduler = reminderScheduler,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun observeReminderChanges() {
        scope.launch {
            combine(
                streetRepository.observeStreetsWithRules(), parkingRepository.observeActiveSession(),
                settingsRepository.settings, reminderCoordinator.plans,
            ) { _, _, _, _ -> Unit }.collect { reminderCoordinator.refresh() }
        }
    }
}

class ParkingApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
        container.observeReminderChanges()
    }
}
