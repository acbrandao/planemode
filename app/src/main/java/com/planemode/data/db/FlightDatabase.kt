package com.planemode.data.db

import androidx.room.*
import com.planemode.model.SampleSource
import com.planemode.model.TrackPoint

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startEpochMs: Long,
    val endEpochMs: Long? = null,
    val aircraftId: String? = null,
    val departure: String? = null,
    val arrival: String? = null,
    val simulated: Boolean = false,
    val distanceM: Double = 0.0,
    val elapsedMs: Long = 0,
    val gpsValidMs: Long = 0,
    val estimatedMs: Long = 0,
    val noPositionMs: Long = 0,
    val phase: String = "ARMED",
    val maxAltitudeM: Double = 0.0,
    val maxSpeedMps: Double = 0.0,
    val lastUpdateEpochMs: Long = startEpochMs,
)

@Entity(
    tableName = "samples",
    indices = [Index("sessionId")],
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)],
)
data class SampleEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val epochMs: Long,
    val monoMs: Long,
    val lat: Double,
    val lon: Double,
    val altitudeM: Double?,
    val speedMps: Double?,
    val estimated: Boolean,
    val hAccM: Double?,
    val distanceM: Double,
) {
    fun toPoint() = TrackPoint(epochMs, monoMs, lat, lon, altitudeM, speedMps, if (estimated) SampleSource.ESTIMATED else SampleSource.GPS, hAccM, distanceM)
    companion object {
        fun from(sessionId: Long, p: TrackPoint) = SampleEntity(0, sessionId, p.epochMs, p.monoMs, p.lat, p.lon, p.altitudeM, p.speedMps,
            p.source == SampleSource.ESTIMATED, p.hAccM, p.distanceM)
    }
}

@Entity(tableName = "events", indices = [Index("sessionId")],
    foreignKeys = [ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE)])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val type: String,
    val epochMs: Long,
    val lat: Double?,
    val lon: Double?,
    val altitudeM: Double?,
)

@Dao
interface FlightDao {
    @Insert suspend fun insertSession(s: SessionEntity): Long
    @Update suspend fun updateSession(s: SessionEntity)
    @Query("SELECT * FROM sessions WHERE endEpochMs IS NULL ORDER BY id DESC LIMIT 1") suspend fun activeSession(): SessionEntity?
    @Query("SELECT * FROM sessions ORDER BY id DESC LIMIT 20") suspend fun recentSessions(): List<SessionEntity>
    @Insert suspend fun insertSamples(list: List<SampleEntity>)
    @Query("SELECT * FROM samples WHERE sessionId = :id ORDER BY monoMs") suspend fun samples(id: Long): List<SampleEntity>
    @Insert suspend fun insertEvent(e: EventEntity)
    @Query("SELECT * FROM events WHERE sessionId = :id ORDER BY epochMs") suspend fun events(id: Long): List<EventEntity>
    @Query("DELETE FROM sessions WHERE id NOT IN (SELECT id FROM sessions ORDER BY id DESC LIMIT :keep)") suspend fun prune(keep: Int)
}

@Database(entities = [SessionEntity::class, SampleEntity::class, EventEntity::class], version = 1, exportSchema = true)
abstract class FlightDatabase : RoomDatabase() {
    abstract fun dao(): FlightDao
}
