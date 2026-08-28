/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.sfg.orchard.mobile.model

enum class RepeatMode { OFF, ALL, ONE }

enum class PlaybackStatus { IDLE, LOADING, READY, BUFFERING, PLAYING, PAUSED, ENDED, ERROR }

/** Immutable UI projection of the authoritative local or remote player. */
data class PlaybackSnapshot(
    val status: PlaybackStatus = PlaybackStatus.IDLE,
    val currentTrack: Track? = null,
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val bufferedPositionMs: Long = 0,
    val isPlaying: Boolean = false,
    val volume: Float = 1.0f,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val contextTitle: String = "",
    val errorMessage: String = "",
) {
    val history: List<Track>
        get() = if (currentIndex > 0) queue.take(currentIndex) else emptyList()

    val upcoming: List<Track>
        get() = if (currentIndex in queue.indices) queue.drop(currentIndex + 1) else queue
}

/**
 * The transition Smart Crossfade has planned out of the current track, for the scrubber to draw.
 *
 * Published while the plan is still in the future as well as during it, because the point of the
 * marker is to show *where* the mix will happen: that the analysis put it on a downbeat, and that
 * it ends where the music does rather than where the file does.
 */
data class TransitionMarker(
    val trackId: String,
    val startMs: Long,
    val endMs: Long,
    /** Lowercase style name, so the marker can distinguish a beat-matched blend from a plain fade. */
    val style: String,
    /** The track being mixed in, so presentation follows the actual next player even after queue edits. */
    val incomingTrackId: String = "",
    /** Where the incoming player was cued, on that track's own media timeline. */
    val incomingCueMs: Long = 0,
    /** Media-time rate of the incoming player during a live (non-rendered) overlap. */
    val incomingPlaybackRate: Double = 1.0,
    /**
     * First point where the incoming track is louder than the outgoing one across every band the
     * mixer controls. This can be later than the main fader crossover because DJ mixes retain the
     * outgoing bass until its separate handoff.
     */
    val audibleHandoffProgress: Float = 0.5f,
    /** Duration of the rendered mix item, or zero when the transition uses the two live players. */
    val renderedDurationMs: Long = 0,
)

enum class DeviceAvailability { ONLINE, OFFLINE, UNAVAILABLE }

enum class DeviceType { PHONE, COMPUTER, SPEAKER, TV, UNKNOWN }

data class PlaybackDevice(
    val id: String,
    val name: String,
    val type: DeviceType,
    val availability: DeviceAvailability,
    val isLocal: Boolean = false,
    val isActive: Boolean = false,
    val customName: String = "",
    val serverUrl: String = "",
    val lastSeenAt: Long = 0L,
) {
    val displayName: String
        get() = customName.ifBlank { name }
}

sealed interface PlaybackTarget {
    data object LocalPhone : PlaybackTarget
    data class Remote(val deviceId: String) : PlaybackTarget
}

data class PlaybackTargetState(
    val selected: PlaybackTarget = PlaybackTarget.LocalPhone,
    val devices: List<PlaybackDevice> = emptyList(),
    val isTransferring: Boolean = false,
    val message: String = "",
)
