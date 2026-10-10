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

import android.content.Context
import android.content.pm.PackageManager
import dev.sfg.orchard.mobile.artwork.ArtistImageRepository
import dev.sfg.orchard.mobile.catalog.CatalogRepository
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.PlaybackStatus
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.songlinks.SongLinksRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.Collections
import java.util.concurrent.atomic.AtomicLong

private const val DISCORD_PACKAGE = "com.discord"

/** Builds Rich Presence from playback and hands it to the Social SDK. */
class DiscordPresenceCoordinator(
    private val context: Context,
    private val songLinks: SongLinksRepository,
    private val artistImages: ArtistImageRepository,
    private val catalog: CatalogRepository,
    private val scope: CoroutineScope,
) {
    private val currentRequestId = AtomicLong(0)
    private var enhanceJob: Job? = null
    private var isEnabled = false
    private var started = false
    private var lastTrackId: String? = null
    private var lastStatus: PlaybackStatus? = null
    private var lastPositionMs = 0L
    private var lastUpdateEpochMs = 0L

    private val artistPortraits = lruCache<String>()

    val status: StateFlow<DiscordPresenceStatus> = DiscordNative.status

    private fun <V> lruCache(): MutableMap<String, V> = Collections.synchronizedMap(
        object : LinkedHashMap<String, V>(32, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, V>?) = size > 100
        }
    )

    private fun discordInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(DISCORD_PACKAGE, 0)
    }.isSuccess

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        if (!enabled) {
            if (started) DiscordNative.stop()
            started = false
            resetTracking()
            return
        }
        if (started) return
        if (!discordInstalled()) {
            DiscordNative.report(DiscordPresenceStatus.Unavailable)
            return
        }
        if (!DiscordNative.isLoaded) {
            DiscordNative.report(DiscordPresenceStatus.Error("Discord SDK failed to load"))
            return
        }
        DiscordNative.report(DiscordPresenceStatus.Ready)
        DiscordNative.start(DISCORD_APPLICATION_ID)
        started = true
    }

    fun updatePlayback(snapshot: PlaybackSnapshot) {
        if (!isEnabled || !started) return
        val track = snapshot.currentTrack
        if (track == null || snapshot.status == PlaybackStatus.IDLE) {
            clearPresence()
            return
        }

        val isPlaying = snapshot.status == PlaybackStatus.PLAYING
        val positionMs = snapshot.positionMs.coerceAtLeast(0)
        val durationMs = snapshot.durationMs.takeIf { it > 0 } ?: track.durationMs
        val now = System.currentTimeMillis()
        val expectedPositionMs = if (isPlaying && lastUpdateEpochMs > 0) {
            lastPositionMs + (now - lastUpdateEpochMs)
        } else {
            lastPositionMs
        }
        val isSeek = Math.abs(positionMs - expectedPositionMs) > 3000L
        if (track.id == lastTrackId && snapshot.status == lastStatus && !isSeek) return

        lastTrackId = track.id
        lastStatus = snapshot.status
        lastPositionMs = positionMs
        lastUpdateEpochMs = now

        val requestId = currentRequestId.incrementAndGet()
        send(track, isPlaying, positionMs, durationMs, normalizeDiscordImageUrl(track.artworkUrl), artistPortraits[artistKey(track)])

        enhanceJob?.cancel()
        enhanceJob = scope.launch(Dispatchers.IO) {
            val portrait = resolveArtistPortrait(track)
            if (requestId != currentRequestId.get() || portrait.isNullOrBlank()) return@launch
            send(track, isPlaying, positionMs, durationMs, normalizeDiscordImageUrl(track.artworkUrl), portrait)
        }
    }

    private fun send(
        track: Track,
        isPlaying: Boolean,
        positionMs: Long,
        durationMs: Long,
        artworkUrl: String,
        artistImageUrl: String?,
    ) {
        val title = trimDiscordText(track.title, fallback = "Music")
        val artist = trimDiscordText(track.artist, fallback = "Orchard")
        val album = trimDiscordText(track.album)

        // Timestamps follow the wall clock at send time so the enhanced update stays aligned.
        val start = if (isPlaying) (System.currentTimeMillis() - positionMs).coerceAtLeast(0) else 0L
        val end = if (isPlaying && durationMs > positionMs) start + durationMs else 0L

        val buttons = buildList {
            songLinks.trackUrl(track)?.takeIf(String::isNotBlank)?.let { add("Listen on Your Platform" to it) }
            add("View the Orchard Project" to DISCORD_ORCHARD_PROJECT_URL)
        }

        DiscordNative.setPresence(
            type = DISCORD_LISTENING,
            name = artist,
            details = if (isPlaying) title else "Paused - $title",
            state = artist,
            startMs = start,
            endMs = end,
            largeImage = artworkUrl,
            largeText = album.ifBlank { title },
            smallImage = artistImageUrl.orEmpty(),
            smallText = artist,
            buttonLabels = buttons.map { it.first }.toTypedArray(),
            buttonUrls = buttons.map { it.second }.toTypedArray(),
        )
    }

    private fun artistKey(track: Track): String =
        "${track.artists.firstOrNull()?.name?.ifBlank { track.artist } ?: track.artist}|${track.artistId}"

    private suspend fun resolveArtistPortrait(track: Track): String? {
        artistPortraits[artistKey(track)]?.let { return it }
        val name = track.artists.firstOrNull()?.name?.ifBlank { track.artist }
            ?: track.artist.substringBefore(", ")
        if (name.isBlank()) return null

        var url = normalizeDiscordImageUrl(artistImages.images(name)?.portraitUrl)
        if (url.isBlank()) {
            // A channel avatar covers artists TheAudioDB has no portrait for.
            val artistId = track.artists.firstOrNull()?.id?.ifBlank { track.artistId } ?: track.artistId
            val resolvedId = artistId.ifBlank {
                runCatching { catalog.trackArtists(track.id).firstOrNull()?.id.orEmpty() }.getOrDefault("")
            }
            if (resolvedId.isNotBlank()) {
                url = normalizeDiscordImageUrl(runCatching { catalog.browse(resolvedId).artworkUrl }.getOrDefault(""))
            }
        }
        return url.takeIf(String::isNotBlank)?.also { artistPortraits[artistKey(track)] = it }
    }

    private fun resetTracking() {
        lastTrackId = null
        lastStatus = null
        lastPositionMs = 0L
        lastUpdateEpochMs = 0L
        currentRequestId.incrementAndGet()
        enhanceJob?.cancel()
        enhanceJob = null
    }

    fun clearPresence() {
        resetTracking()
        if (started) DiscordNative.clearPresence()
    }
}
