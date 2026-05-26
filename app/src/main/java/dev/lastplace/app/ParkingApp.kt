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

/**
 * Manual dependency container. Small enough that Hilt isn't worth the build cost yet;
 * swap in Hilt later if the graph grows.
 */
class AppContainer(context: Context) {
    private val database = ParkingDatabase.get(context)

    val scheduleEngine = ScheduleEngine()
    val deadlineCalculator = DeadlineCalculator(scheduleEngine)
    val streetMatcher = StreetMatcher()

    val streetRepository = StreetRepository(database.streetDao())
    val parkingRepository = ParkingRepository(database.parkingSessionDao())
    val settingsRepository = SettingsRepository(context)

    val osmService = OsmService()
    val locationProvider = LocationProvider(context)

    val reminderScheduler = ReminderScheduler(context)
    val parkingService = ParkingService(
        streetRepository = streetRepository,
        parkingRepository = parkingRepository,
        settingsRepository = settingsRepository,
        deadlineCalculator = deadlineCalculator,
        reminderScheduler = reminderScheduler,
    )
}

class ParkingApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Notifications.createChannels(this)
    }
}
