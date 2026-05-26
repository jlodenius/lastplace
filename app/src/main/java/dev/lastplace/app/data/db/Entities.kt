package dev.lastplace.app.data.db

import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Relation
import dev.lastplace.app.domain.model.CleaningWindow
import dev.lastplace.app.domain.model.GeoStreet
import java.time.DayOfWeek
import java.time.LocalTime

@Entity(tableName = "streets")
data class Street(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    override val lat: Double,
    override val lng: Double,
    override val matchRadiusMeters: Int = 40,
    val notes: String? = null,
    /** OSM polyline(s), encoded by [GeometryCodec]; null when only a point is known. */
    val geometry: String? = null,
) : GeoStreet

@Entity(
    tableName = "cleaning_rules",
    foreignKeys = [
        ForeignKey(
            entity = Street::class,
            parentColumns = ["id"],
            childColumns = ["streetId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("streetId")],
)
data class CleaningRule(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val streetId: Long,
    val dayOfWeek: Int,        // 1 = Monday … 7 = Sunday (java.time.DayOfWeek)
    val startMinuteOfDay: Int, // e.g. 08:00 -> 480
    val endMinuteOfDay: Int,   // e.g. 14:00 -> 840
)

/** Maps a persisted rule to the domain value type used by [dev.lastplace.app.domain]. */
fun CleaningRule.toWindow(): CleaningWindow = CleaningWindow(
    dayOfWeek = DayOfWeek.of(dayOfWeek),
    start = LocalTime.ofSecondOfDay(startMinuteOfDay * 60L),
    end = LocalTime.ofSecondOfDay(endMinuteOfDay * 60L),
)

@Entity(tableName = "parking_sessions")
data class ParkingSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val streetId: Long,
    val parkedAt: Long,      // epoch millis
    val deadline: Long?,     // epoch millis, null if the street has no cleaning rules
    val endedAt: Long? = null, // null = currently parked
    val source: String,      // ParkSource.name
)

/** A street together with its cleaning rules, loaded in one query. */
data class StreetWithRules(
    @Embedded val street: Street,
    @Relation(parentColumn = "id", entityColumn = "streetId")
    val rules: List<CleaningRule>,
)
