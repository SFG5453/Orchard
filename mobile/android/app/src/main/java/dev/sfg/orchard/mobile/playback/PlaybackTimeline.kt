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

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi

internal const val MIX_SOURCE_START = "orchard.mix.sourceStartMs"
internal const val MIX_SOURCE_DURATION = "orchard.mix.sourceDurationMs"
internal const val MIX_SOURCE_RATE = "orchard.mix.sourceRate"

/** Translate decoder clocks into full-song clocks without replacing the playing media source. */
internal data class SourcePlaybackClock(val startMs: Long = 0, val rate: Double = 1.0) {
    fun sourcePosition(positionMs: Long): Long = startMs + (positionMs.coerceAtLeast(0) * rate).toLong()
    fun playerPosition(positionMs: Long): Long = ((positionMs - startMs) / rate).toLong().coerceAtLeast(0)
}

@UnstableApi
internal fun Player.sourceClock(): SourcePlaybackClock {
    val item = currentMediaItem ?: return SourcePlaybackClock()
    val extras = item.mediaMetadata.extras
    return if (extras?.containsKey(MIX_SOURCE_START) == true) {
        SourcePlaybackClock(extras.getLong(MIX_SOURCE_START), extras.getDouble(MIX_SOURCE_RATE, 1.0))
    } else SourcePlaybackClock(item.clippingConfiguration.startPositionMs)
}

@UnstableApi
internal fun Player.isRenderedMix(): Boolean = currentMediaItem?.mediaMetadata?.extras?.containsKey(MIX_SOURCE_START) == true

@UnstableApi
internal fun Player.sourcePositionMs(): Long = sourceClock().sourcePosition(currentPosition)

@UnstableApi
internal fun Player.sourceDurationMs(): Long {
    val item = currentMediaItem ?: return 0
    if (isRenderedMix()) return item.mediaMetadata.extras!!.getLong(MIX_SOURCE_DURATION)
    return if (duration != C.TIME_UNSET && duration > 0) duration + item.clippingConfiguration.startPositionMs
        else MediaItemMapper.toTrack(item).durationMs
}
