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
import dev.sfg.orchard.mobile.artwork.ArtistImageRepository
import dev.sfg.orchard.mobile.artwork.ArtworkRepository
import dev.sfg.orchard.mobile.auth.NativeYouTubeAuthRepository
import dev.sfg.orchard.mobile.auth.SecureYouTubeSessionStore
import dev.sfg.orchard.mobile.catalog.CatalogRepository
import dev.sfg.orchard.mobile.catalog.AudioVersionResolver
import dev.sfg.orchard.mobile.catalog.InnerTubeClient
import dev.sfg.orchard.mobile.catalog.PlaylistActions
import dev.sfg.orchard.mobile.connect.ConnectDeviceRepository
import dev.sfg.orchard.mobile.library.LibraryCache
import dev.sfg.orchard.mobile.library.LibraryRepository
import dev.sfg.orchard.mobile.lyrics.LyricsRepository
import dev.sfg.orchard.mobile.settings.SettingsRepository
import dev.sfg.orchard.mobile.download.DownloadManager
import dev.sfg.orchard.mobile.songlinks.SongLinksRepository
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
    // The loader is deferred so auth and innerTube can depend on each other without a cycle.
    val auth = NativeYouTubeAuthRepository(
        store = SecureYouTubeSessionStore(context),
        scope = applicationScope,
        profileLoader = { innerTube.accountInfo() },
    )
    private val innerTube: InnerTubeClient = InnerTubeClient(http, auth)
    val settings = SettingsRepository(context, applicationScope)
    val networkMonitor = dev.sfg.orchard.mobile.network.NetworkMonitor(context)
    val downloads = DownloadManager(context, http, auth, applicationScope)
    val spotifyCanvas = dev.sfg.orchard.mobile.spotify.SpotifyCanvasRepository(context, http, settings)
    val artwork = ArtworkRepository(http, spotifyCanvas)
    val artistImages = ArtistImageRepository(http)
    val catalog = CatalogRepository(innerTube)
    val playlistActions = PlaylistActions(innerTube)
    val audioVersions = AudioVersionResolver(innerTube)
    val library = LibraryRepository(LibraryCache(context), catalog, applicationScope)
    val lyrics = LyricsRepository(http, innerTube)
    val connect = ConnectDeviceRepository(context, applicationScope)
    val songLinks = SongLinksRepository(http)

    /**
     * The transition the playback service has planned, or null when there is none.
     *
     * Held on the graph rather than sent through the media session because both live in this
     * process: the session carries what a remote controller needs, and a planned-but-not-started
     * transition is not that. A Connect target simply leaves this null, which is correct; the
     * marker describes local playback.
     */
    /**
     * Stored analysis for a track, set by the playback service once it is running.
     *
     * The transition planner uses this to plan crossfades. Both live in this process, so the lookup
     * is shared directly rather than copied through the media session. Null before playback starts.
     */
    @Volatile
    var analysisLookup: ((dev.sfg.orchard.mobile.model.Track) ->
        dev.sfg.orchard.mobile.playback.smart.TrackAnalysis)? = null



    val transitionMarker = kotlinx.coroutines.flow.MutableStateFlow<dev.sfg.orchard.mobile.model.TransitionMarker?>(null)
    val warningEvent = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 16)
    val activeBitrate = kotlinx.coroutines.flow.MutableStateFlow(0)
    val discordAuth = dev.sfg.orchard.mobile.discord.DiscordOAuthRepository(context, http, applicationScope)
    val discordPresence = dev.sfg.orchard.mobile.discord.DiscordPresenceCoordinator(
        http = http,
        auth = discordAuth,
        songLinks = songLinks,
        scope = applicationScope,
    )

    fun postWarning(message: String) {
        warningEvent.tryEmit(message)
    }

    init {
        applicationScope.launch { auth.restore() }
        UpdateManager(context).checkForUpdates()
    }

    companion object {
        fun from(context: Context): OrchardGraph =
            (context.applicationContext as OrchardApplication).graph
    }
}
