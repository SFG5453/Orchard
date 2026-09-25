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

package dev.sfg.orchard.mobile.playback

import android.content.Context
import android.util.Log
import androidx.media3.cast.RemoteCastPlayer
import androidx.media3.cast.SessionAvailabilityListener
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

/** Owns the local/receiver handoff while keeping one authoritative Media3 queue. */
@UnstableApi
class ChromecastPlayback(
    context: Context,
    private val converter: ChromecastMediaItemConverter,
    private val localPlayer: () -> Player,
    private val onCastStarted: (Player) -> Unit,
    private val onCastEnded: (Player) -> Unit,
    private val onError: (String) -> Unit,
) : AutoCloseable {
    val player: RemoteCastPlayer =
        RemoteCastPlayer.Builder(context).setMediaItemConverter(converter).build()

    var isActive: Boolean = false
        private set

    private var lastRemoteState = TransferState.EMPTY
    private var lastPhoneState = TransferState.EMPTY

    private val remoteListener =
        object : Player.Listener {
            override fun onEvents(player: Player, events: Player.Events) {
                if (player.mediaItemCount > 0) lastRemoteState = TransferState.capture(player)
            }
        }

    private val sessionListener =
        object : SessionAvailabilityListener {
            override fun onCastSessionAvailable() = transferToReceiver()

            override fun onCastSessionUnavailable() = transferToPhone()
        }

    init {
        player.addListener(remoteListener)
    }

    /** Starts observing Cast only after the service has stored this owner. */
    fun start() {
        player.setSessionAvailabilityListener(sessionListener)
        if (player.isCastSessionAvailable) transferToReceiver()
    }

    fun authoritativePlayer(): Player = if (isActive) player else localPlayer()

    private fun transferToReceiver() {
        if (isActive) return
        val local = localPlayer()
        val state = TransferState.capture(local)
        lastPhoneState = state
        runCatching {
                local.pause()
                if (state.items.isEmpty()) {
                    player.clearMediaItems()
                } else {
                    player.setMediaItems(state.items, state.index, state.positionMs)
                }
                player.setPlaylistMetadata(state.playlistMetadata)
                player.repeatMode = state.repeatMode
                player.shuffleModeEnabled = state.shuffle
                lastRemoteState = state
                isActive = true
                onCastStarted(player)
                if (state.items.isNotEmpty()) player.prepare()
                player.playWhenReady = state.playWhenReady
            }
            .onFailure { error ->
                Log.w(TAG, "Could not transfer playback to Cast", error)
                isActive = false
                local.playWhenReady = state.playWhenReady
                onError(error.message ?: "Could not start Chromecast playback")
            }
    }

    private fun transferToPhone() {
        if (!isActive) return
        val remoteState =
            if (player.mediaItemCount > 0) TransferState.capture(player) else lastRemoteState
        val phoneItems = restorePhoneQueue(remoteState.items)
        val state = remoteState.copy(
            items = phoneItems,
            index = if (phoneItems.isEmpty()) 0 else remoteState.index.coerceIn(phoneItems.indices),
        )
        val local = localPlayer()
        isActive = false
        runCatching {
                local.stop()
                if (state.items.isEmpty()) {
                    local.clearMediaItems()
                } else {
                    local.setMediaItems(state.items, state.index, state.positionMs)
                }
                local.setPlaylistMetadata(state.playlistMetadata)
                local.repeatMode = state.repeatMode
                local.shuffleModeEnabled = state.shuffle
                if (state.items.isNotEmpty()) local.prepare()
                onCastEnded(local)
                local.playWhenReady = state.playWhenReady
            }
            .onFailure { error ->
                Log.w(TAG, "Could not return Cast playback to phone", error)
                onCastEnded(local)
                onError(error.message ?: "Could not return playback to this phone")
            }
    }

    private fun restorePhoneQueue(remoteItems: List<MediaItem>): List<MediaItem> {
        if (remoteItems.isEmpty()) return lastPhoneState.items
        val originalItems = lastPhoneState.items
        val sameQueueSize = remoteItems.size == originalItems.size
        val restored = remoteItems.mapIndexed { index, remoteItem ->
            converter.originalFor(remoteItem.mediaId)
                ?: remoteItem.takeIf { item ->
                    item.localConfiguration?.uri?.let(MediaItemMapper::isOrchardUri) == true
                }
                // Media3 sometimes exposes MediaItem.EMPTY for queue entries that Cast
                // did not include in its latest status. Their order remains usable.
                ?: originalItems.getOrNull(index).takeIf { sameQueueSize }
        }
        if (restored.any { it == null }) {
            Log.w(TAG, "Cast queue had entries without local media; restoring the phone queue")
            return originalItems
        }
        return restored.filterNotNull()
    }

    override fun close() {
        player.setSessionAvailabilityListener(null)
        player.removeListener(remoteListener)
        isActive = false
        player.release()
    }

    private data class TransferState(
        val items: List<MediaItem>,
        val index: Int,
        val positionMs: Long,
        val playWhenReady: Boolean,
        val repeatMode: Int,
        val shuffle: Boolean,
        val playlistMetadata: MediaMetadata,
    ) {
        companion object {
            val EMPTY =
                TransferState(
                    items = emptyList(),
                    index = 0,
                    positionMs = 0,
                    playWhenReady = false,
                    repeatMode = Player.REPEAT_MODE_OFF,
                    shuffle = false,
                    playlistMetadata = MediaMetadata.EMPTY,
                )

            fun capture(player: Player): TransferState {
                val items = (0 until player.mediaItemCount).map(player::getMediaItemAt)
                return TransferState(
                    items = items,
                    index =
                        if (items.isEmpty()) 0
                        else {
                            player.currentMediaItemIndex
                                .takeUnless { it == C.INDEX_UNSET }
                                ?.coerceIn(items.indices) ?: 0
                        },
                    positionMs = player.currentPosition.coerceAtLeast(0),
                    playWhenReady = player.playWhenReady,
                    repeatMode = player.repeatMode,
                    shuffle = player.shuffleModeEnabled,
                    playlistMetadata = player.playlistMetadata,
                )
            }
        }
    }

    private companion object {
        const val TAG = "OrchardCast"
    }
}
