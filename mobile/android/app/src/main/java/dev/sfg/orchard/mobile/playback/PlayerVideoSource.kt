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

import androidx.media3.common.Player

/**
 * Swaps the current item between album audio and its music video, keeping the playhead. The same
 * video at another height reloads in place; that is how quality changes. [beforeSwap] runs once
 * the swap is certain.
 */
internal fun Player.switchVideoSource(expectedTrackId: String, videoId: String, maxHeight: Int?, beforeSwap: () -> Unit) {
    val index = currentMediaItemIndex
    if (index !in 0 until mediaItemCount) return
    val current = getMediaItemAt(index)
    if (current.mediaId != expectedTrackId) return
    val currentUri = current.localConfiguration?.uri
    val isVideo = MediaItemMapper.isVideoUri(currentUri)
    if (videoId.isBlank() && !isVideo) return
    if (isVideo && currentUri != null && MediaItemMapper.sourceId(currentUri) == videoId &&
        (maxHeight == null || MediaItemMapper.videoHeight(currentUri) == maxHeight)
    ) return

    val sourcePosition = sourcePositionMs().coerceAtLeast(0)
    val audioDuration = MediaItemMapper.toTrack(current).durationMs
    val position = if (videoId.isBlank() && audioDuration > 0) sourcePosition.coerceAtMost(audioDuration - 1)
        else sourcePosition
    val resume = playWhenReady
    val updated = if (videoId.isBlank()) MediaItemMapper.asAudio(current)
    else MediaItemMapper.asVideo(MediaItemMapper.withMusicVideoId(current, videoId), videoId, maxHeight)

    beforeSwap()
    // Replacing a current item can keep its old decoder alive until the new timeline is
    // prepared. Stop it first so audio and video never run side by side after a toggle.
    stop()
    replaceMediaItem(index, updated)
    seekTo(index, position)
    prepare()
    playWhenReady = resume
}
