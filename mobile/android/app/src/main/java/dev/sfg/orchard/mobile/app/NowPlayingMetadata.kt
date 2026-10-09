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
import dev.sfg.orchard.mobile.artwork.TrackArtwork
import dev.sfg.orchard.mobile.model.Artist
import dev.sfg.orchard.mobile.model.LoadState
import dev.sfg.orchard.mobile.model.LyricLine
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.PlaybackTarget
import dev.sfg.orchard.mobile.model.PlaybackTargetState
import dev.sfg.orchard.mobile.playback.LocalPlaybackController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MusicVideoState(
    val trackId: String = "",
    val videoId: String = "",
    val checking: Boolean = false,
    val playing: Boolean = false,
    /** Height of the picture playing, 0 until the stream resolves. */
    val height: Int = 0,
    val heights: List<Int> = emptyList(),
    /** The saved ceiling; 0 takes the best available. */
    val maxHeight: Int = 0,
) {
    val available: Boolean get() = videoId.isNotBlank()
}

/** What the player shows beside the audio: artwork, artist credits, lyrics and the music video. */
internal class NowPlayingMetadata(
    private val graph: OrchardGraph,
    private val scope: CoroutineScope,
    private val local: LocalPlaybackController,
) {
    private val mutableArtwork = MutableStateFlow<TrackArtwork?>(null)
    val artwork: StateFlow<TrackArtwork?> = mutableArtwork.asStateFlow()
    private val mutableArtistCredits = MutableStateFlow<Pair<String, List<Artist>>?>(null)
    val artistCredits: StateFlow<Pair<String, List<Artist>>?> = mutableArtistCredits.asStateFlow()
    private val mutableLyrics = MutableStateFlow<LoadState<List<LyricLine>>>(LoadState.Idle)
    val lyrics: StateFlow<LoadState<List<LyricLine>>> = mutableLyrics.asStateFlow()
    private val mutableMusicVideo = MutableStateFlow(MusicVideoState())
    val musicVideo: StateFlow<MusicVideoState> = mutableMusicVideo.asStateFlow()

    fun observeArtwork(targetPlayback: Flow<PlaybackSnapshot>) {
        scope.launch {
            combine(
                targetPlayback.map { it.currentTrack },
                graph.downloads.downloads,
            ) { track, downloaded -> track to track?.id?.let(downloaded::get) }
                .distinctUntilChanged { old, new ->
                    old.first?.id == new.first?.id &&
                        old.second?.cachedAnimatedArtworkUrl == new.second?.cachedAnimatedArtworkUrl &&
                        old.second?.cachedAnimatedArtworkVerticalUrl == new.second?.cachedAnimatedArtworkVerticalUrl
                }
                .collectLatest { (track, downloaded) ->
                    mutableArtwork.value = when {
                        track == null -> null
                        // The user's own cover and loop, never an online lookup.
                        track.isLocal -> TrackArtwork(track.id, track.artworkUrl, track.animatedArtworkUrl)
                        downloaded != null && (
                            downloaded.cachedAnimatedArtworkUrl.isNotBlank() ||
                                downloaded.cachedAnimatedArtworkVerticalUrl.isNotBlank()
                        ) -> TrackArtwork(
                            track.id,
                            track.artworkUrl,
                            downloaded.cachedAnimatedArtworkUrl,
                            downloaded.cachedAnimatedArtworkVerticalUrl,
                        )
                        track.animatedArtworkVerticalUrl.isNotBlank() || track.animatedArtworkUrl.isNotBlank() ->
                            TrackArtwork(track.id, track.artworkUrl, track.animatedArtworkUrl, track.animatedArtworkVerticalUrl)
                        graph.connect.roleHost("artwork") != null -> {
                            // The cover the track already carries shows at once; a connected
                            // desktop resolves the rest, and local lookup covers a silent one.
                            mutableArtwork.value = TrackArtwork(track.id, track.artworkUrl)
                            graph.connect.providers.artwork(track)?.let { desktop ->
                                TrackArtwork(track.id, desktop.staticUrl.ifBlank { track.artworkUrl }, desktop.animatedUrl)
                            } ?: graph.artwork.artwork(track)
                        }
                        else -> graph.artwork.artwork(track)
                    }
                }
        }
    }

    fun observeArtistCredits(targetPlayback: Flow<PlaybackSnapshot>) {
        scope.launch {
            targetPlayback.map { it.currentTrack }.distinctUntilChanged { old, new -> old?.id == new?.id }
                .collectLatest { track ->
                    if (track == null) {
                        mutableArtistCredits.value = null
                        return@collectLatest
                    }
                    val existing = track.artists
                        .filter { it.id.isNotBlank() }
                        .distinctBy { it.id }
                    mutableArtistCredits.value = track.id to existing
                    if (!track.playbackSource.equals("youtube", ignoreCase = true) || existing.size > 1) {
                        return@collectLatest
                    }
                    val resolved = runCatching { graph.catalog.trackArtists(track.id) }.getOrDefault(emptyList())
                    if (resolved.isNotEmpty()) mutableArtistCredits.value = track.id to resolved
                }
        }
    }

    fun observeLyrics(playback: Flow<PlaybackSnapshot>) {
        scope.launch {
            playback.map { it.currentTrack }.distinctUntilChanged { old, new -> old?.id == new?.id }
                .collectLatest { track ->
                    if (track == null) {
                        mutableLyrics.value = LoadState.Idle
                        return@collectLatest
                    }
                    mutableLyrics.value = LoadState.Loading
                    // A song from this phone uses the user's own lyrics, and is never looked up online.
                    mutableLyrics.value = runCatching {
                        if (track.isLocal) graph.localLibrary.lyricsFor(track) else graph.lyrics.lyrics(track)
                    }
                        .fold(
                            onSuccess = {
                                if (it.isEmpty()) LoadState.Empty("Lyrics are not available for this track.")
                                else LoadState.Content(it)
                            },
                            onFailure = { LoadState.Error(it.message ?: "Lyrics could not be loaded.") },
                        )
                }
        }
    }

    fun observeMusicVideo(targets: Flow<PlaybackTargetState>) {
        scope.launch {
            combine(local.snapshot, targets) { snapshot, targetState ->
                snapshot.currentTrack.takeIf { targetState.selected is PlaybackTarget.LocalPhone }
            }
                .distinctUntilChangedBy { track ->
                    track?.let { Triple(it.id, it.musicVideoType, it.musicVideoId) }
                }
                .collectLatest { track ->
                    if (track == null || track.isQobuz || track.isLocal) {
                        mutableMusicVideo.value = MusicVideoState()
                        return@collectLatest
                    }
                    mutableMusicVideo.value = MusicVideoState(
                        trackId = track.id,
                        checking = true,
                        playing = local.snapshot.value.playingVideo,
                    )
                    val videoId = graph.videoVersions.videoId(track).orEmpty()
                    mutableMusicVideo.value = MusicVideoState(
                        trackId = track.id,
                        videoId = videoId,
                        playing = local.snapshot.value.playingVideo,
                    )
                }
        }
        scope.launch {
            combine(local.snapshot, targets) { snapshot, targetState ->
                snapshot.playingVideo && targetState.selected is PlaybackTarget.LocalPhone
            }.distinctUntilChanged().collect { playing ->
                mutableMusicVideo.update { it.copy(playing = playing) }
            }
        }
    }
}
