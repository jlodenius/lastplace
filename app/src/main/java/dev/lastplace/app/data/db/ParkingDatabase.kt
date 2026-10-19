package dev.lastplace.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

private val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE streets ADD COLUMN geometry TEXT")
    }
}

private val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE streets ADD COLUMN isFavourite INTEGER NOT NULL DEFAULT 0")
    }
}

private val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS planned_moves (
                sessionId INTEGER NOT NULL PRIMARY KEY, id TEXT NOT NULL,
                streetId INTEGER NOT NULL, streetName TEXT NOT NULL, moveAt INTEGER NOT NULL,
                justCleaned INTEGER NOT NULL, rulesSignature TEXT NOT NULL, invalidReason TEXT
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS scheduled_reminders (
                `key` TEXT NOT NULL PRIMARY KEY, sessionId INTEGER NOT NULL, kind TEXT NOT NULL,
                targetAt INTEGER NOT NULL, fireAt INTEGER NOT NULL, offsetMinutes INTEGER NOT NULL,
                streetName TEXT NOT NULL, planId TEXT, delivered INTEGER NOT NULL DEFAULT 0
            )
        """.trimIndent())
    }
}

@Database(
    entities = [Street::class, CleaningRule::class, ParkingSession::class, PlannedMove::class, ScheduledReminder::class],
    version = 4,
    exportSchema = false,
)
abstract class ParkingDatabase : RoomDatabase() {

    abstract fun streetDao(): StreetDao
    abstract fun parkingSessionDao(): ParkingSessionDao
    abstract fun reminderDao(): ReminderDao

    companion object {
        @Volatile
        private var instance: ParkingDatabase? = null

        fun get(context: Context): ParkingDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ParkingDatabase::class.java,
                    "parking.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4).build().also { instance = it }
            }
    }
}
