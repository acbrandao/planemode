package com.planemode.location

import com.planemode.model.GnssSnapshot
import com.planemode.model.GpsFix
import kotlinx.coroutines.flow.Flow

/** Events produced by any position source. Real and simulated sources share this contract. */
sealed interface SourceEvent {
    /** Emitted when a source (re)starts; consumers should reset short-term filters. [hard] = simulation was reset (ends a simulated flight). */
    data class Reset(val simulated: Boolean, val hard: Boolean = false) : SourceEvent
    data class Fix(val fix: GpsFix) : SourceEvent
    /** Clock heartbeat so loss detection works even when no fixes arrive. */
    data class Tick(val monoMs: Long, val epochMs: Long) : SourceEvent
    data class Satellites(val snapshot: GnssSnapshot) : SourceEvent
    data class Availability(val available: Boolean, val reason: String?) : SourceEvent
}

interface PositionSource {
    val isSimulated: Boolean
    /** Cold flow: collecting starts the source, cancelling stops it. */
    fun events(intervalMs: Long): Flow<SourceEvent>
}
