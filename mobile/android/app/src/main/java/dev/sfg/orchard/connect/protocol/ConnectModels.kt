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

package dev.sfg.orchard.connect.protocol

import org.json.JSONObject

/** Pairing information parsed from either an app link or a desktop HTTP URL. */
data class PairingInput(val serverUrl: String = "", val token: String = "")

/** Stable, UI-independent view of a track sent by Orchard desktop. */
data class ConnectTrack(
    val id: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val thumbnail: String = "",
    val artwork: String = "",
    val animatedArtwork: String = "",
    val animatedArtworkVertical: String = ""
)

/** Playback values are seconds and a normalized 0..1 volume. */
data class ConnectPlayback(
    val isPlaying: Boolean = false,
    val buffering: Boolean = false,
    val currentTime: Double = 0.0,
    val duration: Double = 0.0,
    val volume: Double = 0.0,
    val shuffle: Boolean = false,
    val repeatMode: String = "off"
)

data class ConnectLyricWord(
    val text: String = "",
    val startTime: Double = 0.0,
    val endTime: Double = 0.0
)

data class ConnectLyricLine(
    val text: String = "",
    val startTime: Double? = null,
    val endTime: Double? = null,
    val words: List<ConnectLyricWord> = emptyList()
)

data class ConnectLyrics(
    val status: String = "idle",
    val mode: String = "",
    val lines: List<ConnectLyricLine> = emptyList()
)

data class ConnectAudioPreset(val value: String = "", val label: String = "")

data class ConnectAudioEngine(
    val manualEqEnabled: Boolean = false,
    val autoEqEnabled: Boolean = false,
    val activePreset: String = "flat",
    val presets: List<ConnectAudioPreset> = emptyList()
)

/**
 * Typed snapshot received from `connect:state`.
 *
 * [rawPayload] is retained as a defensive copy only for the legacy test UI and
 * forward-compatible fields. Business logic should consume the typed fields.
 */
data class ConnectSnapshot(
    val status: String = "idle",
    val protocolVersion: Int = 1,
    val track: ConnectTrack? = null,
    val playback: ConnectPlayback = ConnectPlayback(),
    val lyrics: ConnectLyrics = ConnectLyrics(),
    val queue: List<ConnectTrack> = emptyList(),
    val audioEngine: ConnectAudioEngine = ConnectAudioEngine(),
    val rawPayload: JSONObject = JSONObject()
)

/**
 * Search/library item with an opaque desktop playback payload.
 *
 * Orchard desktop may add normalized track fields over time. [playbackPayload]
 * preserves that payload verbatim when it is sent back with `play-track`.
 */
data class ConnectRemoteItem(
    val track: ConnectTrack,
    val playbackPayload: JSONObject,
    val rawPayload: JSONObject
)

enum class HelloStatus { APPROVED, PENDING, EXPIRED, UNKNOWN }

data class HelloResult(
    val status: HelloStatus,
    val state: ConnectSnapshot? = null,
    val protocolVersion: Int = 1
)

data class ConnectResults(
    val requestId: String,
    val items: List<ConnectRemoteItem>
)

/** Connection states are delivered on the caller-provided callback executor. */
enum class ConnectClientStatus {
    DISCONNECTED,
    CONNECTING,
    AWAITING_APPROVAL,
    APPROVED,
    PAIRING_EXPIRED,
    REJECTED,
    REVOKED
}

data class ConnectClientError(
    val operation: String,
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause)

/** Typed command family accepted by the current desktop command dispatcher. */
sealed interface ConnectCommand {
    data object TogglePlayback : ConnectCommand
    data object Play : ConnectCommand
    data object Pause : ConnectCommand
    data object Next : ConnectCommand
    data object Previous : ConnectCommand
    data class Volume(val value: Double) : ConnectCommand
    data class Seek(val seconds: Double) : ConnectCommand
    data class AudioEnginePreset(val value: String) : ConnectCommand
    data class AutoEq(val enabled: Boolean) : ConnectCommand
    data class ManualEq(val enabled: Boolean) : ConnectCommand
    data class PlayQueueIndex(val index: Int) : ConnectCommand
    data class RemoveQueueIndex(val index: Int) : ConnectCommand
    data class MoveQueueIndex(val from: Int, val to: Int) : ConnectCommand
    data object ClearUpcoming : ConnectCommand
    data class PlayNext(val track: JSONObject) : ConnectCommand
    data class AddToQueue(val track: JSONObject) : ConnectCommand
    data object ToggleShuffle : ConnectCommand
    data object CycleRepeat : ConnectCommand
    data class PlayTrack(val item: ConnectRemoteItem) : ConnectCommand
}
