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

@Database(
    entities = [Street::class, CleaningRule::class, ParkingSession::class],
    version = 2,
    exportSchema = false,
)
abstract class ParkingDatabase : RoomDatabase() {

    abstract fun streetDao(): StreetDao
    abstract fun parkingSessionDao(): ParkingSessionDao

    companion object {
        @Volatile
        private var instance: ParkingDatabase? = null

        fun get(context: Context): ParkingDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    ParkingDatabase::class.java,
                    "parking.db",
                ).addMigrations(MIGRATION_1_2).build().also { instance = it }
            }
    }
}
