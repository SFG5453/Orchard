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

package dev.sfg.orchard.mobile.ui.screens

import android.graphics.Bitmap

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.media3.common.Player
import dev.sfg.orchard.mobile.app.MusicVideoState
import dev.sfg.orchard.mobile.model.LoadState
import dev.sfg.orchard.mobile.model.LyricLine
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.PlaybackTarget
import dev.sfg.orchard.mobile.model.PlaybackTargetState
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.model.TransitionMarker
import dev.sfg.orchard.mobile.ui.foldable.isFoldableActive
import dev.sfg.orchard.mobile.ui.foldable.rememberTabletopHinge
import dev.sfg.orchard.mobile.ui.theme.CanopyColors

/** Full player: backdrop, then the phone, tablet or foldable body, plus the collapse flight. */
@Composable
fun NowPlayingScreen(
    playback: PlaybackSnapshot,
    targets: PlaybackTargetState,
    lyrics: LoadState<List<LyricLine>>,
    animatedArtworkEnabled: Boolean,
    animatedBackgroundEnabled: Boolean,
    modifier: Modifier = Modifier,
    gesturesEnabled: Boolean = false,
    liked: Boolean,
    /** The pill's bounds in root coordinates; the player collapses into it. */
    collapseBounds: Rect? = null,
    /** The pill's artwork thumbnail, which the player's cover flies into. */
    collapseArtworkBounds: Rect? = null,
    /**
     * The cover's resting bounds — the other end of the flight. Hoisted because it has to
     * outlive the player: measuring it needs the player on screen, but the opening flight
     * needs it before the player has been laid out.
     */
    restingCoverBounds: Rect? = null,
    onRestingCoverBounds: (Rect) -> Unit = {},
    transition: dev.sfg.orchard.mobile.model.TransitionMarker? = null,
    /** Raw overlap progress, including the part after visible identity moves to the incoming song. */
    mixProgress: Float? = null,
    showBitrate: Boolean = false,
    bitrateKbps: Int = 0,
    isQobuz: Boolean = false,
    remoteVolume: Float = 1f,
    onRemoteVolumeChange: (Float) -> Unit = {},
    onBack: () -> Unit,
    onToggle: () -> Unit,
    onPrevious: () -> Unit,
    /** A swipe back always changes track; the button restarts a song past its opening. */
    onSkipBack: () -> Unit = onPrevious,
    onNext: () -> Unit,
    onSeek: (Long) -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onLiked: () -> Unit,
    onDevices: () -> Unit,
    onPlayQueueIndex: (Int) -> Unit,
    onRemoveQueueIndex: (Int) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    onClearUpcoming: () -> Unit,
    downloadedTrackIds: Set<String> = emptySet(),
    onDownloadTrack: ((Track) -> Unit)? = null,
    onRemoveDownloadTrack: ((String) -> Unit)? = null,
    onAddToPlaylist: ((Track) -> Unit)? = null,
    onShare: (() -> Unit)? = null,
    onOpenCollection: ((String) -> Unit)? = null,
    autoplayEnabled: Boolean = true,
    autoplayLoading: Boolean = false,
    autoplayError: String = "",
    onAutoplayEnabled: ((Boolean) -> Unit)? = null,
    sleepTimerRemainingSeconds: Long = 0L,
    sleepTimerEndOfTrack: Boolean = false,
    onStartSleepTimer: (Int) -> Unit = {},
    onStartSleepTimerAtEndOfTrack: () -> Unit = {},
    onCancelSleepTimer: () -> Unit = {},
    smartCrossfade: Boolean = false,
    onBestMixUpcoming: ((onProgress: (String) -> Unit, onComplete: () -> Unit) -> Unit)? = null,
    musicVideo: MusicVideoState = MusicVideoState(),
    videoPlayer: Player? = null,
    onToggleMusicVideo: () -> Unit = {},
    onVideoQuality: (Int) -> Unit = {},
) {
    // Lyrics and the queue are modes of the player, not destinations, so their state lives here.
    // Only one can hold the panel at a time.
    var panel by remember { mutableStateOf(PlayerPanel.NONE) }
    var sleepTimerDialogOpen by remember { mutableStateOf(false) }
    val lyricsOpen = panel == PlayerPanel.LYRICS
    val queueOpen = panel == PlayerPanel.QUEUE
    val onQueue = { panel = if (queueOpen) PlayerPanel.NONE else PlayerPanel.QUEUE }
    val tabletop = rememberTabletopHinge()

    // Back closes an open panel before it closes the player.
    BackHandler(enabled = panel != PlayerPanel.NONE) { panel = PlayerPanel.NONE }
    val track = playback.currentTrack
    if (track == null) {
        NothingPlaying(onBack)
        return
    }

    val onOpenArtist = rememberArtistOpen(track, onOpenCollection)

    val localControls = targets.selected is PlaybackTarget.LocalPhone
    // A Connect target accepts every transport and queue command.
    val canControl = true
    val activeMixProgress =
        mixProgress ?: 0f
    val incomingTrack = remember(playback.queue, transition?.incomingTrackId) {
        val id = transition?.incomingTrackId
        if (id.isNullOrBlank()) null else playback.queue.firstOrNull { it.id == id }
    }
    val outgoingTrack = remember(playback.queue, transition?.trackId, track) {
        val id = transition?.trackId
        if (id.isNullOrBlank()) track else playback.queue.firstOrNull { it.id == id } ?: track
    }
    val incomingPalette = incomingTrack?.let { rememberFullBleedPalette(it) }

    // Two panes need room for a square cover and a readable column beside it.
    // Below this a tablet in portrait, or a large phone in landscape, is better
    // served by the stacked layout it already has.
    val isFoldable = isFoldableActive()
    val wideLayout = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width.toDp() >= 840.dp
    }
    // Tabletop frames ordinary covers in the upright half; Apple's tall art bleeds as on a phone.
    val framedCover = wideLayout || (tabletop != null && !hasTabletopTallArt(track, animatedArtworkEnabled))

    val collapse = rememberPlayerCollapse(onBack)
    // Collapse progress is read only in layers and callbacks; reading it here would recompose
    // the whole player on every frame of the open and dismiss animations.
    val settledOpen = { collapse.progress == 0f }
    val dragHandle = Modifier.playerDragHandle(collapse)
    val swipe = rememberArtworkSwipe(track.id)
    // Back mirrors the drag rather than cutting straight to the previous screen.
    BackHandler(enabled = panel == PlayerPanel.NONE) { collapse.dismiss() }
    var playerSize by remember { mutableStateOf(IntSize.Zero) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged {
                playerSize = it
                collapse.collapseSpan = it.height.toFloat().coerceAtLeast(1f)
            }
            // Being hit-testable is what blocks the page below; events are observed, never
            // consumed, so the player's own drags and taps behave as before.
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .collapsingBody(collapse, { playerSize })
                .background(CanopyColors.PlayerBackdrop),
        ) {
            // One sample feeds the backdrop and the lyrics, so sung words carry the same
            // colour the artwork bleeds into rather than a second, squarer sample of the cover.
            val verticalVideo = track.animatedArtworkVerticalUrl.ifBlank { track.animatedArtworkUrl }
            val hasRichArtwork = animatedArtworkEnabled && verticalVideo.isNotBlank()
            var videoFrame by remember(verticalVideo) { mutableStateOf<Bitmap?>(null) }
            var artworkSeam by remember { mutableStateOf<Float?>(null) }
            var artworkRoom by remember { mutableStateOf<Float?>(null) }
            val palette = rememberFullBleedPalette(track, videoFrame)
            val lyricAccent = palette.accent.lyricAccent()

            PlayerBackdropLayer(
                track = track,
                isPlaying = playback.isPlaying,
                panel = panel,
                wideLayout = framedCover,
                isFoldable = isFoldable,
                palette = palette,
                incomingPalette = incomingPalette,
                incomingArtworkUrl = incomingTrack?.artworkUrl,
                animatedArtworkEnabled = animatedArtworkEnabled,
                animatedBackgroundEnabled = animatedBackgroundEnabled,
                gesturesEnabled = gesturesEnabled,
                transitionProgress = activeMixProgress,
                onNext = onNext,
                onPrevious = onSkipBack,
                onLiked = onLiked,
                onVideoFrame = { videoFrame = it },
                onArtworkBounds = { if (!framedCover && !isFoldable && hasRichArtwork && settledOpen()) onRestingCoverBounds(it) },
                swipe = swipe.takeIf { !framedCover && !isFoldable },
                onArtworkSeam = { artworkSeam = it },
                // The player fills the window, so the hinge's window px are the room above it.
                artworkRoom = tabletop?.top ?: artworkRoom,
            )

            if (tabletop != null) {
                TabletopPlayerBody(
                    hinge = tabletop,
                    track = track,
                    playback = playback,
                    targets = targets,
                    lyrics = lyrics,
                    lyricAccent = lyricAccent,
                    incomingTrack = incomingTrack,
                    outgoingTrack = outgoingTrack,
                    onCoverBounds = { if (settledOpen()) onRestingCoverBounds(it) },
                    transition = transition,
                    mixProgress = activeMixProgress,
                    canControl = canControl,
                    localControls = localControls,
                    liked = liked,
                    panel = panel,
                    animatedArtworkEnabled = animatedArtworkEnabled,
                    showBitrate = showBitrate,
                    bitrateKbps = bitrateKbps,
                    isQobuz = isQobuz,
                    remoteVolume = remoteVolume,
                    modifier = dragHandle,
                    onRemoteVolumeChange = onRemoteVolumeChange,
                    onBack = onBack,
                    onSeek = onSeek,
                    onToggle = onToggle,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onShuffle = onShuffle,
                    onRepeat = onRepeat,
                    onLiked = onLiked,
                    onDevices = onDevices,
                    onPlayQueueIndex = onPlayQueueIndex,
                    onRemoveQueueIndex = onRemoveQueueIndex,
                    onMoveQueueItem = onMoveQueueItem,
                    onClearUpcoming = onClearUpcoming,
                    downloadedTrackIds = downloadedTrackIds,
                    onDownloadTrack = onDownloadTrack,
                    onRemoveDownloadTrack = onRemoveDownloadTrack,
                    onAddToPlaylist = onAddToPlaylist,
                    onShare = onShare,
                    onOpenCollection = onOpenCollection,
                    onOpenArtist = onOpenArtist,
                    onWatchVideo = onToggleMusicVideo.takeIf { musicVideo.available },
                    onLyricsPanel = { panel = if (lyricsOpen) PlayerPanel.NONE else PlayerPanel.LYRICS },
                    onQueuePanel = onQueue,
                    sleepTimerRemainingSeconds = sleepTimerRemainingSeconds,
                    sleepTimerEndOfTrack = sleepTimerEndOfTrack,
                    onSleepTimer = { sleepTimerDialogOpen = true },
                    autoplayEnabled = autoplayEnabled,
                    autoplayLoading = autoplayLoading,
                    autoplayError = autoplayError,
                    onAutoplayEnabled = onAutoplayEnabled,
                    smartCrossfade = smartCrossfade,
                    onBestMixUpcoming = onBestMixUpcoming,
                )
                return@Box
            }

            // A tablet has room to stop trading one thing for another: the cover
            // keeps its own frame on the left while lyrics or the queue occupy the
            // right, instead of displacing the artwork the way the phone must.
            if (wideLayout || isFoldable) {
                TabletPlayerBody(
                    track = track,
                    playback = playback,
                    targets = targets,
                    lyrics = lyrics,
                    lyricAccent = lyricAccent,
                    onCoverBounds = { if (settledOpen()) onRestingCoverBounds(it) },
                    animatedArtworkEnabled = animatedArtworkEnabled,
                    liked = liked,
                    panel = panel,
                    canControl = canControl,
                    localControls = localControls,
                    transition = transition,
                    mixProgress = activeMixProgress,
                    showBitrate = showBitrate,
                    bitrateKbps = bitrateKbps,
                    isQobuz = isQobuz,
                    remoteVolume = remoteVolume,
                    modifier = dragHandle,
                    onRemoteVolumeChange = onRemoteVolumeChange,
                    onBack = onBack,
                    onSeek = onSeek,
                    onToggle = onToggle,
                    onPrevious = onPrevious,
                    onNext = onNext,
                    onShuffle = onShuffle,
                    onRepeat = onRepeat,
                    onLiked = onLiked,
                    onDevices = onDevices,
                    onPlayQueueIndex = onPlayQueueIndex,
                    onRemoveQueueIndex = onRemoveQueueIndex,
                    onMoveQueueItem = onMoveQueueItem,
                    onClearUpcoming = onClearUpcoming,
                    downloadedTrackIds = downloadedTrackIds,
                    onDownloadTrack = onDownloadTrack,
                    onRemoveDownloadTrack = onRemoveDownloadTrack,
                    onAddToPlaylist = onAddToPlaylist,
                    onShare = onShare,
                    onOpenCollection = onOpenCollection,
                    onOpenArtist = onOpenArtist,
                    onWatchVideo = onToggleMusicVideo.takeIf { musicVideo.available },
                    onLyricsPanel = { panel = if (lyricsOpen) PlayerPanel.NONE else PlayerPanel.LYRICS },
                    onQueuePanel = onQueue,
                    sleepTimerRemainingSeconds = sleepTimerRemainingSeconds,
                    sleepTimerEndOfTrack = sleepTimerEndOfTrack,
                    onSleepTimer = { sleepTimerDialogOpen = true },
                    autoplayEnabled = autoplayEnabled,
                    autoplayLoading = autoplayLoading,
                    autoplayError = autoplayError,
                    onAutoplayEnabled = onAutoplayEnabled,
                    smartCrossfade = smartCrossfade,
                    onBestMixUpcoming = onBestMixUpcoming,
                    crease = isFoldable && !wideLayout,
                )
                return@Box
            }

            PhonePlayerBody(
                track = track,
                playback = playback,
                targets = targets,
                lyrics = lyrics,
                lyricAccent = lyricAccent,
                hasRichArtwork = hasRichArtwork,
                incomingTrack = incomingTrack,
                outgoingTrack = outgoingTrack,
                onCoverBounds = { if (settledOpen()) onRestingCoverBounds(it) },
                transition = transition,
                mixProgress = activeMixProgress,
                canControl = canControl,
                localControls = localControls,
                liked = liked,
                panel = panel,
                swipe = swipe,
                gesturesEnabled = gesturesEnabled,
                showBitrate = showBitrate,
                bitrateKbps = bitrateKbps,
                isQobuz = isQobuz,
                remoteVolume = remoteVolume,
                modifier = dragHandle,
                onRemoteVolumeChange = onRemoteVolumeChange,
                onBack = onBack,
                onSeek = onSeek,
                onToggle = onToggle,
                onPrevious = onPrevious,
                onSkipBack = onSkipBack,
                onNext = onNext,
                onShuffle = onShuffle,
                onRepeat = onRepeat,
                onLiked = onLiked,
                onDevices = onDevices,
                onPlayQueueIndex = onPlayQueueIndex,
                onRemoveQueueIndex = onRemoveQueueIndex,
                onMoveQueueItem = onMoveQueueItem,
                onClearUpcoming = onClearUpcoming,
                downloadedTrackIds = downloadedTrackIds,
                onDownloadTrack = onDownloadTrack,
                onRemoveDownloadTrack = onRemoveDownloadTrack,
                onAddToPlaylist = onAddToPlaylist,
                onShare = onShare,
                onOpenCollection = onOpenCollection,
                onOpenArtist = onOpenArtist,
                onLyricsPanel = { panel = if (lyricsOpen) PlayerPanel.NONE else PlayerPanel.LYRICS },
                onQueuePanel = onQueue,
                sleepTimerRemainingSeconds = sleepTimerRemainingSeconds,
                sleepTimerEndOfTrack = sleepTimerEndOfTrack,
                onSleepTimer = { sleepTimerDialogOpen = true },
                autoplayEnabled = autoplayEnabled,
                autoplayLoading = autoplayLoading,
                autoplayError = autoplayError,
                onAutoplayEnabled = onAutoplayEnabled,
                smartCrossfade = smartCrossfade,
                onBestMixUpcoming = onBestMixUpcoming,
                artworkSeam = artworkSeam,
                onArtworkRoom = { artworkRoom = it },
                onWatchVideo = onToggleMusicVideo.takeIf { musicVideo.available },
            )
        }

        MusicVideoLayer(
            musicVideo = musicVideo,
            player = videoPlayer,
            track = track,
            playback = playback,
            liked = liked,
            localControls = localControls,
            actions = MusicVideoActions(
                onBack, onToggleMusicVideo, onToggle, onPrevious, onNext,
                onSeek, onShuffle, onRepeat, onLiked, onDevices, onVideoQuality,
            ),
        )
    }

    if (sleepTimerDialogOpen) {
        SleepTimerDialog(
            remainingSeconds = sleepTimerRemainingSeconds,
            endOfTrack = sleepTimerEndOfTrack,
            onStart = onStartSleepTimer,
            onStartAtEndOfTrack = onStartSleepTimerAtEndOfTrack,
            onCancel = onCancelSleepTimer,
            onDismiss = { sleepTimerDialogOpen = false },
        )
    }
}
