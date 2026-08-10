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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import dev.sfg.orchard.mobile.model.LibrarySnapshot
import dev.sfg.orchard.mobile.model.OrchardSettings
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.PlaybackTargetState
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.components.PlaylistPickerSheet
import dev.sfg.orchard.mobile.ui.navigation.Routes
import dev.sfg.orchard.mobile.ui.screens.NowPlayingScreen

/**
 * The full player, presented over the app rather than routed to.
 *
 * Keeping it out of the [androidx.navigation.compose.NavHost] is what lets the screen
 * behind it stay composed: the pull-down uncovers the real page and the real pill instead
 * of an empty back stack. [collapseBounds] is where that pill actually sits, so the
 * dismiss can shrink into it.
 */
@Composable
fun NowPlayingOverlay(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    collapseBounds: Rect?,
    collapseArtworkBounds: Rect?,
    restingCoverBounds: Rect?,
    onRestingCoverBounds: (Rect) -> Unit,
    nav: NavHostController,
    viewModel: OrchardViewModel,
    playback: PlaybackSnapshot,
    targets: PlaybackTargetState,
    library: LibrarySnapshot,
    settings: OrchardSettings,
    modifier: Modifier = Modifier,
) {
    // Nothing to show once playback is cleared, and no reason to hold the player's
    // state machine alive while it is closed.
    if (!open || playback.currentTrack == null) return

    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val transition by viewModel.transitionMarker.collectAsStateWithLifecycle()
    val activeBitrate by viewModel.activeBitrate.collectAsStateWithLifecycle()
    val autoplayLoading by viewModel.autoplayLoading.collectAsStateWithLifecycle()
    val autoplayError by viewModel.autoplayError.collectAsStateWithLifecycle()
    val connectProtocolVersion by viewModel.connectProtocolVersion.collectAsStateWithLifecycle()
    val connectRemoteVolume by viewModel.connectRemoteVolume.collectAsStateWithLifecycle()
    val downloadedTrackIds by viewModel.downloadedTrackIds.collectAsStateWithLifecycle()
    var playlistPickerTrack by remember { mutableStateOf<Track?>(null) }

    val liked = playback.currentTrack?.let { track -> library.likedTracks.any { it.id == track.id } } == true

    NowPlayingScreen(
        modifier = modifier,
        collapseBounds = collapseBounds,
        collapseArtworkBounds = collapseArtworkBounds,
        restingCoverBounds = restingCoverBounds,
        onRestingCoverBounds = onRestingCoverBounds,
        autoplayEnabled = settings.autoplayEnabled,
        autoplayLoading = autoplayLoading,
        autoplayError = autoplayError,
        onAutoplayEnabled = viewModel::setAutoplayEnabled,
        playback = playback,
        transition = transition,
        targets = targets,
        lyrics = lyrics,
        animatedArtworkEnabled = settings.animatedArtwork,
        showBitrate = settings.showBitrate,
        bitrateKbps = activeBitrate,
        liked = liked,
        protocolVersion = connectProtocolVersion,
        remoteVolume = connectRemoteVolume,
        onRemoteVolumeChange = viewModel::setRemoteVolume,
        onBack = { onOpenChange(false) },
        onToggle = viewModel::togglePlayback,
        onPrevious = viewModel::previous,
        onNext = viewModel::next,
        onSeek = viewModel::seek,
        onShuffle = viewModel::toggleShuffle,
        onRepeat = viewModel::cycleRepeat,
        onLiked = { playback.currentTrack?.let(viewModel::toggleLiked) },
        onDevices = {
            onOpenChange(false)
            nav.navigate(Routes.DEVICES)
        },
        onPlayQueueIndex = viewModel::playQueueIndex,
        onRemoveQueueIndex = viewModel::removeQueueIndex,
        onMoveQueueItem = viewModel::moveQueueItem,
        onClearUpcoming = viewModel::clearUpcoming,
        downloadedTrackIds = downloadedTrackIds,
        onDownloadTrack = viewModel::downloadTrack,
        onRemoveDownloadTrack = viewModel::removeDownload,
        onAddToPlaylist = { playlistPickerTrack = it },
        onShare = { playback.currentTrack?.let(viewModel::shareTrack) },
        // Closes the player so the collection is not buried underneath it.
        onOpenCollection = { id ->
            onOpenChange(false)
            viewModel.openDetail(id)
            nav.navigate(Routes.detail(id))
        },
    )

    playlistPickerTrack?.let { track ->
        PlaylistPickerSheet(
            track = track,
            playlists = library.savedPlaylists,
            onDismiss = { playlistPickerTrack = null },
            onSelect = { playlist ->
                playlistPickerTrack = null
                viewModel.addTrackToPlaylist(playlist.id, track)
            },
        )
    }
}
