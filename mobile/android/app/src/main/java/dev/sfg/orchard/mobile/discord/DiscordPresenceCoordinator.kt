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

package dev.sfg.orchard.mobile.discord

import android.util.Log
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.PlaybackStatus
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.songlinks.SongLinksRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.atomic.AtomicLong

/**
 * High-level coordinator connecting playback events, Discord authentication,
 * asset registration, SongLinks resolution, and Gateway presence updates.
 */
class DiscordPresenceCoordinator(
    http: OkHttpClient,
    private val auth: DiscordOAuthRepository,
    private val songLinks: SongLinksRepository,
    private val scope: CoroutineScope,
) {
    private val gateway = DiscordGatewayClient(http, scope)
    private val assetRegistrar = DiscordAssetRegistrar(http)
    private val currentRequestId = AtomicLong(0)
    private var enhanceJob: Job? = null
    private var isEnabled = true
    private var lastTrackId: String? = null
    private var lastStatus: PlaybackStatus? = null
    private var lastPositionMs: Long = 0L
    private var lastUpdateEpochMs: Long = 0L
    private var lastAnimatedUrl: String? = null

    val connectionState: StateFlow<GatewayConnectionState> = gateway.connectionState
    val authState: StateFlow<DiscordAuthState> = auth.authState

    init {
        scope.launch {
            auth.authState.collectLatest { state ->
                when (state) {
                    is DiscordAuthState.SignedIn -> {
                        if (isEnabled) gateway.connect(state.session.accessToken)
                    }
                    is DiscordAuthState.SignedOut, is DiscordAuthState.Error -> {
                        gateway.disconnect()
                    }
                    is DiscordAuthState.Authorizing -> Unit
                }
            }
        }
    }

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled) {
            gateway.updateActivity(null)
            gateway.disconnect()
        } else {
            val session = (auth.authState.value as? DiscordAuthState.SignedIn)?.session
            if (session != null) {
                gateway.connect(session.accessToken)
            }
        }
    }

    fun updatePlayback(snapshot: PlaybackSnapshot, animatedArtworkUrlOverride: String? = null) {
        if (!isEnabled) return
        val track = snapshot.currentTrack
        if (track == null || snapshot.status == PlaybackStatus.IDLE) {
            clearPresence()
            return
        }

        val isPlaying = snapshot.status == PlaybackStatus.PLAYING
        val positionMs = snapshot.positionMs.coerceAtLeast(0)
        val durationMs = snapshot.durationMs.takeIf { it > 0 } ?: track.durationMs
        val animatedUrl = animatedArtworkUrlOverride?.takeIf(String::isNotBlank)
            ?: track.animatedArtworkVerticalUrl.takeIf(String::isNotBlank)
            ?: track.animatedArtworkUrl.takeIf(String::isNotBlank)

        val now = System.currentTimeMillis()
        val isSameTrack = (track.id == lastTrackId)
        val isSameStatus = (snapshot.status == lastStatus)
        val isSameAnimatedUrl = (animatedUrl == lastAnimatedUrl)
        val expectedPositionMs = if (isPlaying && lastUpdateEpochMs > 0) {
            lastPositionMs + (now - lastUpdateEpochMs)
        } else {
            lastPositionMs
        }
        val isSeek = Math.abs(positionMs - expectedPositionMs) > 3000L

        if (isSameTrack && isSameStatus && isSameAnimatedUrl && !isSeek) {
            return
        }

        lastTrackId = track.id
        lastStatus = snapshot.status
        lastPositionMs = positionMs
        lastUpdateEpochMs = now
        lastAnimatedUrl = animatedUrl

        val requestId = currentRequestId.incrementAndGet()

        // 1. Send immediate base activity
        val immediateActivity = buildActivity(
            track = track,
            isPlaying = isPlaying,
            positionMs = positionMs,
            durationMs = durationMs,
            artworkKey = normalizeDiscordImageUrl(track.artworkUrl),
            songLinkUrl = null,
        )
        gateway.updateActivity(immediateActivity)

        // 2. Concurrently enhance with animated artwork & SongLinks
        enhanceJob?.cancel()
        enhanceJob = scope.launch(Dispatchers.IO) {
            enhancePresence(
                requestId = requestId,
                track = track,
                isPlaying = isPlaying,
                positionMs = positionMs,
                durationMs = durationMs,
                animatedArtworkUrl = animatedUrl,
            )
        }
    }

    private suspend fun enhancePresence(
        requestId: Long,
        track: Track,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long,
        animatedArtworkUrl: String?,
    ) = withContext(Dispatchers.IO) {
        val session = auth.getValidSession() ?: return@withContext
        if (requestId != currentRequestId.get()) return@withContext

        val artworkDeferred = async {
            assetRegistrar.resolveArtworkAsset(
                accessToken = session.accessToken,
                staticArtworkUrl = track.artworkUrl,
                animatedArtworkUrl = animatedArtworkUrl,
            )
        }

        val songLinkDeferred = async {
            runCatching {
                songLinks.resolveTrack(track).shareUrl
            }.getOrNull()
        }

        val resolvedArtworkKey = artworkDeferred.await() ?: normalizeDiscordImageUrl(track.artworkUrl)
        val resolvedSongLinkUrl = songLinkDeferred.await()

        if (requestId != currentRequestId.get()) return@withContext

        val enhancedActivity = buildActivity(
            track = track,
            isPlaying = isPlaying,
            positionMs = positionMs,
            durationMs = durationMs,
            artworkKey = resolvedArtworkKey,
            songLinkUrl = resolvedSongLinkUrl,
        )

        gateway.updateActivity(enhancedActivity)
    }

    private fun buildActivity(
        track: Track,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long,
        artworkKey: String?,
        songLinkUrl: String?,
    ): DiscordPresenceActivity {
        val title = trimDiscordText(track.title, fallback = "Music")
        val artist = trimDiscordText(track.artist, fallback = "Orchard")
        val album = trimDiscordText(track.album)

        val now = System.currentTimeMillis()
        val timestamps = if (isPlaying) {
            val start = (now - positionMs).coerceAtLeast(0)
            val end = if (durationMs > positionMs) start + durationMs else null
            DiscordPresenceTimestamps(start = start, end = end)
        } else {
            null
        }

        val assets = DiscordPresenceAssets(
            largeImage = artworkKey?.takeIf(String::isNotBlank),
            largeText = album.ifBlank { title },
            smallImage = null,
            smallText = if (isPlaying) "Playing" else "Paused",
        )

        val buttons = buildList {
            if (!songLinkUrl.isNullOrBlank()) {
                add(DiscordPresenceButton(label = "Listen on Your Platform", url = songLinkUrl))
            }
            add(DiscordPresenceButton(label = "View the Orchard Project", url = DISCORD_ORCHARD_PROJECT_URL))
        }

        return DiscordPresenceActivity(
            name = artist.ifBlank { "Orchard" },
            type = 2, // LISTENING
            details = if (isPlaying) title else "Paused - $title",
            state = artist,
            timestamps = timestamps,
            assets = assets,
            buttons = buttons,
            applicationId = DISCORD_APPLICATION_ID,
            platform = "android",
        )
    }

    fun clearPresence() {
        lastTrackId = null
        lastStatus = null
        lastPositionMs = 0L
        lastUpdateEpochMs = 0L
        lastAnimatedUrl = null
        currentRequestId.incrementAndGet()
        enhanceJob?.cancel()
        enhanceJob = null
        gateway.updateActivity(null)
    }

    fun disconnect() {
        clearPresence()
        gateway.disconnect()
    }

    companion object {
        private const val TAG = "DiscordPresence"
    }
}
