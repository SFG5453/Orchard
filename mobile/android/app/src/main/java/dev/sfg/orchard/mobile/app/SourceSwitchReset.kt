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
package dev.sfg.orchard.mobile.app

import dev.sfg.orchard.mobile.OrchardGraph
import dev.sfg.orchard.mobile.catalog.isAppleMusicId
import dev.sfg.orchard.mobile.model.APPLE_MUSIC_SOURCE
import dev.sfg.orchard.mobile.model.PlaybackTarget
import dev.sfg.orchard.mobile.model.PlaybackTargetState
import dev.sfg.orchard.mobile.playback.LocalPlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Clears a song from the other catalog when the music source changes; files on this phone keep playing. */
internal class SourceSwitchReset(
    graph: OrchardGraph,
    private val scope: CoroutineScope,
    private val local: LocalPlaybackController,
    private val targets: StateFlow<PlaybackTargetState>,
) {
    /** Each change of the stored music source after it first loads. */
    val switches: Flow<String> = graph.settings.loadedSettings.map { it.homeSource }.distinctUntilChanged().drop(1)

    fun observe() {
        scope.launch {
            switches.collect { source ->
                if (targets.value.selected is PlaybackTarget.Remote) return@collect
                val track = local.snapshot.value.currentTrack ?: return@collect
                if (track.isLocal) return@collect
                val apple = track.playbackSource == APPLE_MUSIC_SOURCE || isAppleMusicId(track.id)
                if (apple != (source == APPLE_MUSIC_SOURCE)) local.clearQueue()
            }
        }
    }
}
