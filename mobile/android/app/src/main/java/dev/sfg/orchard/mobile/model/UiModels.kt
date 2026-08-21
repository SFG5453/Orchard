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

package dev.sfg.orchard.mobile.model

/** Explicit loading states keep cached content visible during refreshes. */
sealed interface LoadState<out T> {
    data object Idle : LoadState<Nothing>
    data object Loading : LoadState<Nothing>
    data class Content<T>(val value: T, val isCached: Boolean = false) : LoadState<T>
    data class Empty(val message: String) : LoadState<Nothing>
    data class Error(val message: String, val cachedValueAvailable: Boolean = false) : LoadState<Nothing>
}

enum class LibraryFilter { PLAYLISTS, ARTISTS, ALBUMS, SONGS, RECENT, DOWNLOADS }

enum class BuiltInHomeSection {
    YOUR_PLAYLISTS, SUBSCRIBED_ARTISTS, TOP_SONGS, RECOMMENDATIONS,
    DOWNLOADED_PLAYLISTS, DOWNLOADED_ARTISTS, DOWNLOADED_ALBUMS, DOWNLOADED_SONGS
}

data class HomeSectionConfig(
    val section: BuiltInHomeSection,
    val enabled: Boolean
)

enum class NowPlayingWidget { ARTWORK, TRACK_INFO, CONTROLS }

data class LibrarySnapshot(
    val likedTracks: List<Track> = emptyList(),
    val savedAlbums: List<Album> = emptyList(),
    val savedArtists: List<Artist> = emptyList(),
    val savedPlaylists: List<Playlist> = emptyList(),
    val recentlyPlayed: List<Track> = emptyList(),
    val playCounts: Map<String, Int> = emptyMap(),
) {
    /** Most listened to songs sorted by user's actual play count & recency. */
    val mostPlayed: List<Track>
        get() {
            val allTracks = (recentlyPlayed + likedTracks).distinctBy { it.id }
            return allTracks.sortedWith(
                compareByDescending<Track> { playCounts[it.id] ?: 0 }
                    .thenBy { recentlyPlayed.indexOfFirst { r -> r.id == it.id }.let { idx -> if (idx < 0) 999 else idx } }
            )
        }
}

data class OrchardSettings(
    val animatedArtwork: Boolean = true,
    val audioQuality: AudioQuality = AudioQuality.HIGH,
    /** Take the accent from the system's wallpaper palette instead of Orchard's own green. */
    val useSystemColors: Boolean = false,
    /** Let the artwork-tinted background drift instead of holding still between tracks. */
    val animatedBackground: Boolean = false,
    /** Overlap the end of a track with the start of the next one. */
    val crossfadeEnabled: Boolean = false,
    val crossfadeSeconds: Int = DEFAULT_CROSSFADE_SECONDS,
    /**
     * Let stored analysis place the overlap (on the beat, past the incoming track's lead-in, and
     * ending where the music does) instead of ramping blindly over the last few seconds. Falls
     * back to the plain fade for any track it has no evidence about, so it is never worse.
     */
    val smartCrossfade: Boolean = false,
    /** Ceiling on the on-disk stream cache, in megabytes. Whole tracks are kept, so this is the
     * difference between a few albums and a library, and between instant re-listens and refetching.
     */
    val cacheSizeMb: Int = DEFAULT_CACHE_SIZE_MB,
    /** Whether the user has completed or dismissed the initial welcome/onboarding setup. */
    val onboardingCompleted: Boolean = false,
    /** Whether to broadcast now playing status to Discord via Rich Presence. */
    val discordPresenceEnabled: Boolean = true,
    /** Whether to display animated artwork in Discord Rich Presence. */
    val discordAnimatedArtwork: Boolean = true,
    /** Whether to show stream bitrate under the player scrubber. */
    val showBitrate: Boolean = false,
    /** Spotify sp_dc session cookie value for Spotify Canvas fetching. */
    val spotifySpdc: String = "",
    /** Whether to fetch Spotify Canvas animated artwork loops. */
    val spotifyCanvasEnabled: Boolean = true,
    /** Whether to even out volume levels across played tracks. */
    val volumeNormalizationEnabled: Boolean = false,
    /** Keep playing related music once the queue runs out, matching desktop's default of on. */
    val autoplayEnabled: Boolean = true,
    /** Configuration for the 10-band audio equalizer and audio effects. */
    val equalizerConfig: EqualizerConfig = EqualizerConfig(),
    /** Enable swipe and tap gestures on the player artwork. */
    val playerGesturesEnabled: Boolean = true,
    /** Online layout configuration. */
    val homeLayoutOnline: List<HomeSectionConfig> = listOf(
        HomeSectionConfig(BuiltInHomeSection.YOUR_PLAYLISTS, true),
        HomeSectionConfig(BuiltInHomeSection.SUBSCRIBED_ARTISTS, true),
        HomeSectionConfig(BuiltInHomeSection.TOP_SONGS, true),
        HomeSectionConfig(BuiltInHomeSection.RECOMMENDATIONS, true)
    ),
    /** Offline layout configuration. */
    val homeLayoutOffline: List<HomeSectionConfig> = listOf(
        HomeSectionConfig(BuiltInHomeSection.DOWNLOADED_PLAYLISTS, true),
        HomeSectionConfig(BuiltInHomeSection.DOWNLOADED_ARTISTS, true),
        HomeSectionConfig(BuiltInHomeSection.DOWNLOADED_ALBUMS, true),
        HomeSectionConfig(BuiltInHomeSection.DOWNLOADED_SONGS, true)
    ),
    /** Layout for the Now Playing screen widgets. */
    val nowPlayingLayout: List<NowPlayingWidget> = listOf(
        NowPlayingWidget.ARTWORK,
        NowPlayingWidget.TRACK_INFO,
        NowPlayingWidget.CONTROLS
    ),
    /** Whether to show full-bleed artwork on the Now Playing screen. */
    val fullBleedArtworkEnabled: Boolean = true,
    /** Whether to receive beta builds from GitHub releases. */
    val betaChannelEnabled: Boolean = false,
) {
    /** Clamped, because a persisted value from an older build must not size the cache absurdly. */
    val cacheSizeBytes: Long
        get() = cacheSizeMb.coerceIn(MIN_CACHE_SIZE_MB, MAX_CACHE_SIZE_MB) * 1024L * 1024L

    /** Zero when crossfade is off, which is what the playback engine watches. */
    val crossfadeMs: Long
        get() = if (crossfadeEnabled) crossfadeSeconds.coerceIn(MIN_CROSSFADE_SECONDS, MAX_CROSSFADE_SECONDS) * 1_000L else 0L

    companion object {
        const val MIN_CROSSFADE_SECONDS = 1
        const val MAX_CROSSFADE_SECONDS = 12
        const val DEFAULT_CROSSFADE_SECONDS = 6

        const val MIN_CACHE_SIZE_MB = 256
        const val MAX_CACHE_SIZE_MB = 8192
        const val DEFAULT_CACHE_SIZE_MB = 1024
        /** Slider stops, so the control offers round sizes rather than arbitrary megabytes. */
        val CACHE_SIZE_STEPS_MB = listOf(256, 512, 1024, 2048, 4096, 8192)
    }
}

enum class AudioQuality { DATA_SAVER, NORMAL, HIGH, MAX }
