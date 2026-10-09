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
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.sfg.orchard.mobile.OrchardGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal fun observeAudioVariants(graph: OrchardGraph, scope: CoroutineScope, onChange: () -> Unit) {
    scope.launch {
        combine(graph.settings.settings, graph.qobuz.status, graph.connect.state) { _, _, _ ->
            graph.streamVariant()
        }.distinctUntilChanged().collect {
            withContext(Dispatchers.Main.immediate) { onChange() }
        }
    }
}

/** Reopens the current audio item under the new provider cache identity. */
@UnstableApi
internal fun reloadAudioForVariant(
    player: ExoPlayer,
    crossfade: CrossfadeEngine,
    cache: StreamCache,
    streams: PlayerStreams,
    prefetch: (Player) -> Unit,
) {
    crossfade.abort()
    cache.retainOnly(emptyList())
    streams.clear()
    val item = player.currentMediaItem ?: return
    val uri = item.localConfiguration?.uri ?: return
    if (!MediaItemMapper.isOrchardUri(uri) || MediaItemMapper.isVideoUri(uri)) return
    val index = player.currentMediaItemIndex
    val position = player.currentPosition.coerceAtLeast(0)
    val resume = player.playWhenReady
    player.stop()
    player.seekTo(index, position)
    player.prepare()
    player.playWhenReady = resume
    prefetch(player)
}
