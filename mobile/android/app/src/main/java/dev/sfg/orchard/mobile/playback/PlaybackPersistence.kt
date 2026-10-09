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

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.sfg.orchard.mobile.model.RepeatMode

/** The saved queue as media items; only the current one keeps its music-video source. */
internal fun RestoredPlayback.mediaItems(): List<MediaItem> = queue.mapIndexed { index, track ->
    MediaItemMapper.toMediaItem(track, currentVideoId.takeIf { index == currentIndex }.orEmpty())
}

/** Loads a saved session into [player] without starting it unless it was playing. */
@UnstableApi
internal fun RestoredPlayback.restoreInto(player: ExoPlayer) {
    if (queue.isEmpty()) return
    player.setMediaItems(mediaItems(), currentIndex.coerceIn(0, queue.lastIndex), positionMs)
    player.setPlaylistMetadata(MediaMetadata.Builder().setTitle(contextTitle).build())
    // By hand: this runs before the listener that would catch the timeline change, and a
    // restored queue with shuffle on is exactly the case that needs it.
    player.keepQueueOrderUnshuffled()
    player.shuffleModeEnabled = shuffle
    player.repeatMode = repeatMode.toPlayerMode()
    player.playWhenReady = playWhenReady
}

/** What [source] would restore to, read on the main thread. */
@UnstableApi
internal fun savedPlayback(source: Player, unshuffledOrder: List<String>) = RestoredPlayback(
    queue = (0 until source.mediaItemCount).map { MediaItemMapper.toTrack(source.getMediaItemAt(it)) },
    currentIndex = source.currentMediaItemIndex,
    positionMs = source.sourcePositionMs(),
    shuffle = source.shuffleModeEnabled,
    repeatMode = source.repeatMode.toRepeatMode(),
    contextTitle = source.playlistMetadata.title?.toString().orEmpty(),
    playWhenReady = source.playWhenReady,
    unshuffledOrder = unshuffledOrder,
    currentVideoId = source.currentMediaItem
        ?.localConfiguration?.uri
        ?.takeIf(MediaItemMapper::isVideoUri)
        ?.let(MediaItemMapper::sourceId)
        .orEmpty(),
)

private fun Int.toRepeatMode(): RepeatMode = when (this) {
    Player.REPEAT_MODE_ONE -> RepeatMode.ONE
    Player.REPEAT_MODE_ALL -> RepeatMode.ALL
    else -> RepeatMode.OFF
}

private fun RepeatMode.toPlayerMode(): Int = when (this) {
    RepeatMode.ONE -> Player.REPEAT_MODE_ONE
    RepeatMode.ALL -> Player.REPEAT_MODE_ALL
    RepeatMode.OFF -> Player.REPEAT_MODE_OFF
}

/** Shuffles everything after the current item in place; Orchard's shuffle is a reorder. */
internal fun Player.shuffleUpcoming() {
    val from = (currentMediaItemIndex + 1).coerceAtLeast(0)
    if (from >= mediaItemCount - 1) return
    val upcoming = (from until mediaItemCount).map(::getMediaItemAt)
    val total = mediaItemCount
    removeMediaItems(from, total)
    addMediaItems(from, FisherYates.shuffle(upcoming))
}

/** Undoes [shuffleUpcoming] from the order saved when shuffle went on, so the toggle is two-way. */
internal fun Player.restoreUpcoming(unshuffledOrder: List<String>) {
    if (unshuffledOrder.isEmpty()) return
    val from = (currentMediaItemIndex + 1).coerceAtLeast(0)
    if (from >= mediaItemCount - 1) return
    val upcoming = (from until mediaItemCount).map(::getMediaItemAt)
    val restored = QueueEditor.restoreOrder(upcoming, unshuffledOrder, MediaItem::mediaId)
    if (restored.map(MediaItem::mediaId) == upcoming.map(MediaItem::mediaId)) return
    val total = mediaItemCount
    removeMediaItems(from, total)
    addMediaItems(from, restored)
}

internal fun Player.queueMediaIds(): List<String> = (0 until mediaItemCount).map { getMediaItemAt(it).mediaId }
