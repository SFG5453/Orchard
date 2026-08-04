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

import dev.sfg.orchard.connect.protocol.ConnectCommand
import dev.sfg.orchard.mobile.OrchardGraph
import dev.sfg.orchard.mobile.connect.PlaybackTargetCoordinator
import dev.sfg.orchard.mobile.model.PlaybackTarget
import dev.sfg.orchard.mobile.playback.LocalPlaybackController
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

suspend fun performTransferToRemote(
    target: PlaybackTarget.Remote,
    local: LocalPlaybackController,
    graph: OrchardGraph,
    coordinator: PlaybackTargetCoordinator,
) {
    val source = local.snapshot.value
    val beforeTransfer = graph.connect.snapshot.value
    local.pause()
    if (!graph.connect.transfer(source.currentTrack, source.positionMs)) {
        coordinator.failTransfer("Playback transfer failed. The phone remains paused.")
        return
    }
    val confirmed = withTimeoutOrNull(12_000) {
        graph.connect.snapshot.first { remote ->
            val expected = source.currentTrack
            remote != beforeTransfer && remote.currentTrack != null &&
                (expected == null || remote.currentTrack.id == expected.id ||
                remote.currentTrack.title.equals(expected.title, true))
        }
    }
    if (confirmed == null) {
        coordinator.failTransfer("The device did not confirm playback in time.")
    } else {
        coordinator.completeTransfer(target)
        confirmed.currentTrack?.let(graph.library::recordPlayed)
    }
}

suspend fun performTransferToPhone(
    local: LocalPlaybackController,
    graph: OrchardGraph,
    coordinator: PlaybackTargetCoordinator,
) {
    val remote = graph.connect.snapshot.value
    if (remote.isPlaying && !graph.connect.send(ConnectCommand.Pause)) {
        coordinator.failTransfer("The remote device could not be paused. Playback remains remote.")
        return
    }
    val pauseConfirmed = !remote.isPlaying || withTimeoutOrNull(5_000) {
        graph.connect.snapshot.first { !it.isPlaying }
    } != null
    if (!pauseConfirmed) {
        coordinator.failTransfer("The remote device did not confirm pause. Playback remains remote.")
        return
    }
    val queue = remote.queue.toMutableList().apply {
        val current = remote.currentTrack
        if (current != null && none { it.id == current.id }) add(0, current)
    }
    if (queue.isNotEmpty()) {
        val index = remote.currentTrack?.let { current -> queue.indexOfFirst { it.id == current.id } }
            ?.takeIf { it >= 0 } ?: remote.currentIndex.coerceIn(0, queue.lastIndex)
        local.replaceQueue(queue, index, remote.positionMs, play = true, contextTitle = remote.contextTitle.ifBlank { "Connected device" })
    }
    coordinator.completeTransfer(PlaybackTarget.LocalPhone)
}
