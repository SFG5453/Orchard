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

package dev.sfg.orchard.mobile

import android.content.Context
import dev.sfg.orchard.mobile.model.AudioQuality
import kotlinx.coroutines.flow.stateIn
import dev.sfg.orchard.mobile.artwork.ArtistImageRepository
import dev.sfg.orchard.mobile.artwork.ArtworkRepository
import dev.sfg.orchard.mobile.auth.NativeYouTubeAuthRepository
import dev.sfg.orchard.mobile.auth.SecureYouTubeSessionStore
import dev.sfg.orchard.mobile.catalog.CatalogRepository
import dev.sfg.orchard.mobile.catalog.VideoVersionResolver
import dev.sfg.orchard.mobile.catalog.PlaylistActions
import dev.sfg.orchard.mobile.connect.ConnectRepository
import dev.sfg.orchard.mobile.library.LibraryCache
import dev.sfg.orchard.mobile.library.LibraryRepository
import dev.sfg.orchard.mobile.lyrics.LyricsRepository
import dev.sfg.orchard.mobile.lastfm.LastfmRepository
import dev.sfg.orchard.mobile.listenbrainz.ListenBrainzRepository
import dev.sfg.orchard.mobile.settings.SettingsRepository
import dev.sfg.orchard.mobile.download.DownloadManager
import dev.sfg.orchard.mobile.playback.YouTubeStreamResolver
import dev.sfg.orchard.mobile.playback.smart.BestMixFeatureStore
import dev.sfg.orchard.mobile.songlinks.SongLinksRepository
import dev.sfg.orchard.mobile.youtube.YouTubeProvider
import dev.sfg.orchard.mobile.youtube.providerJson
import dev.sfg.orchard.mobile.youtube.text
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Explicit process graph; presentation code receives repositories, not Android
 * singletons or transport objects. The graph owns only application-lifetime work.
 */
class OrchardGraph(context: Context) {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
    private val providerAsset = { name: String ->
        runCatching { context.assets.open("providers/$name").use { it.readBytes() } }.getOrNull()
    }
    /** The desktop YouTube provider in QuickJS: catalog, library, lyrics and stream URLs. */
    val youtube = YouTubeProvider(http, context.cacheDir, providerAsset)
    val auth = NativeYouTubeAuthRepository(
        store = SecureYouTubeSessionStore(context),
        scope = applicationScope,
        profileLoader = { session ->
            val profile = youtube.invoke("account.profile", JSONObject().put("session", session.providerJson()))
            profile.text("name") to profile.text("avatarUrl")
        },
        switchValidator = { session ->
            youtube.invokeArray("catalog.playlists", JSONObject().put("session", session.providerJson()))
        },
    )
    val settings = SettingsRepository(context, applicationScope)
    val bestMixFeatures = BestMixFeatureStore(context)
    val slopVerdicts by lazy { dev.sfg.orchard.mobile.playback.slop.SlopVerdicts(context) }
    val networkMonitor = dev.sfg.orchard.mobile.network.NetworkMonitor(context)
    val spotifyCanvas = dev.sfg.orchard.mobile.spotify.SpotifyCanvasRepository(context, http, settings)
    val artwork = ArtworkRepository(http, spotifyCanvas) { settings.settings.value.artworkSourceOrder }
    val downloads = DownloadManager(
        context = context,
        http = http,
        scope = applicationScope,
        streams = { streams },
        artworkResolver = artwork::artwork,
        downloadAnimatedArtworkProvider = { settings.settings.value.downloadAnimatedArtwork },
    )
    val artistImages = ArtistImageRepository(http)
    /** Shared by playback and downloads, so both reuse one URL cache and one player. */
    val streams: YouTubeStreamResolver by lazy {
        YouTubeStreamResolver(youtube, auth, { settings.settings.value.audioQuality }, { downloads })
    }
    val catalog = CatalogRepository(youtube, auth)
    val playlistActions = PlaylistActions(youtube, auth)
    val videoVersions = VideoVersionResolver(catalog)
    val library = LibraryRepository(LibraryCache(context), catalog, applicationScope)
    val lyrics = LyricsRepository(http) { videoId ->
        youtube.invoke("lyrics.youtube", JSONObject().put("session", auth.session().providerJson()).put("videoId", videoId))
            .text("text")
    }
    /** Lyrics in English, courtesy of a 20 MB model that has never heard a song in its life. */
    val lyricTranslation = dev.sfg.orchard.mobile.lyrics.translation.LyricTranslator(
        context, http, settings.settings, applicationScope,
    )
    /** Songs and playlists kept on this phone; no account or network needed. */
    val localLibrary = dev.sfg.orchard.mobile.local.LocalLibraryRepository(context, applicationScope, notify = { postWarning(it) })
    val songLinks = SongLinksRepository()
    val lastfm = LastfmRepository(context, http, applicationScope)
    val listenBrainz = ListenBrainzRepository(context, http, applicationScope)

    /**
     * The transition the playback service has planned, or null when there is none.
     *
     * Held on the graph rather than sent through the media session because both live in this
     * process: the session carries what a remote controller needs, and a planned-but-not-started
     * transition is not that. A Connect target simply leaves this null, which is correct; the
     * marker describes local playback.
     */
    /**
     * Clear hook for the active playback service's [StreamCache].
     * Null before the playback service starts or after it is destroyed.
     */
    @Volatile
    var onClearStreamCache: (() -> Unit)? = null



    val transitionMarker = kotlinx.coroutines.flow.MutableStateFlow<dev.sfg.orchard.mobile.model.TransitionMarker?>(null)
    val warningEvent = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 16)
    val activeBitrate = kotlinx.coroutines.flow.MutableStateFlow(0)
    val activeTrackIsQobuz = kotlinx.coroutines.flow.MutableStateFlow(false)
    val activeStreamDetail = kotlinx.coroutines.flow.MutableStateFlow(dev.sfg.orchard.mobile.model.StreamDetail())
    val qobuzTiers = dev.sfg.orchard.mobile.qobuz.QobuzTierMemory(context)
    val qobuz = dev.sfg.orchard.mobile.qobuz.QobuzRepository(context, applicationScope)
    val qobuzResolver = dev.sfg.orchard.mobile.qobuz.QobuzResolver(qobuz, http, providerAsset)

    /** Active Qobuz playback leaves adaptive mix and the equalizer off. */
    val maxActive: kotlinx.coroutines.flow.StateFlow<Boolean> by lazy {
        kotlinx.coroutines.flow.combine(settings.settings, qobuz.status, connect.state) { s, _, _ ->
            s.audioQuality == AudioQuality.MAX && qobuzResolver.isAvailable()
        }.stateIn(
            applicationScope,
            kotlinx.coroutines.flow.SharingStarted.Eagerly,
            settings.settings.value.audioQuality == AudioQuality.MAX && qobuzResolver.isAvailable(),
        )
    }

    /** Names where MAX audio comes from; cached streams and bytes from another source must not be reused. */
    fun streamVariant(): String {
        val quality = settings.settings.value.audioQuality
        val qobuzEnabled = quality == AudioQuality.MAX && qobuzResolver.isAvailable()
        return audioCacheVariant(quality, qobuzEnabled, qobuz.status.value.quality.id)
    }

    val discordAuth = dev.sfg.orchard.mobile.discord.DiscordOAuthRepository(context, http, applicationScope)
    val discordPresence = dev.sfg.orchard.mobile.discord.DiscordPresenceCoordinator(
        http = http,
        auth = discordAuth,
        songLinks = songLinks,
        artistImages = artistImages,
        catalog = catalog,
        scope = applicationScope,
    )

    fun postWarning(message: String) {
        warningEvent.tryEmit(message)
    }

    val updates = UpdateManager(context) { settings.settings.value.betaChannelEnabled }

    /** Orchard Connect; declared last because it reads the player, providers and settings above. */
    val connect = ConnectRepository(context, applicationScope, this)

    init {
        applicationScope.launch { auth.restore() }
        updates.checkForUpdates()
    }

    companion object {
        fun from(context: Context): OrchardGraph =
            (context.applicationContext as OrchardApplication).graph
    }
}
