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

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.zIndex
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.sfg.orchard.mobile.model.CatalogKind
import dev.sfg.orchard.mobile.model.LoadState
import dev.sfg.orchard.mobile.model.LibraryFilter
import dev.sfg.orchard.mobile.model.PlaybackTarget
import androidx.compose.ui.graphics.Color
import dev.sfg.orchard.mobile.ui.components.ArtworkBackdrop
import dev.sfg.orchard.mobile.ui.components.CanopyReadout
import dev.sfg.orchard.mobile.ui.components.OrchardBottomBar
import dev.sfg.orchard.mobile.ui.components.PlaylistPickerSheet
import dev.sfg.orchard.mobile.ui.components.SongShareBottomSheet
import dev.sfg.orchard.mobile.ui.navigation.Routes
import dev.sfg.orchard.mobile.ui.screens.DetailScreen
import dev.sfg.orchard.mobile.ui.screens.DevicesScreen
import dev.sfg.orchard.mobile.ui.screens.HomeScreen
import dev.sfg.orchard.mobile.ui.screens.LibraryScreen
import dev.sfg.orchard.mobile.ui.screens.NativeLoginScreen
import dev.sfg.orchard.mobile.ui.screens.NowPlayingScreen
import dev.sfg.orchard.mobile.ui.screens.SearchScreen
import dev.sfg.orchard.mobile.ui.screens.SettingsScreen
import dev.sfg.orchard.mobile.ui.screens.WelcomeScreen
import dev.sfg.orchard.mobile.ui.theme.CanopyColors

@Composable
fun OrchardApp(viewModel: OrchardViewModel) {
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val route = backStack?.destination?.route
    val playback by viewModel.playback.collectAsStateWithLifecycle()
    val targets by viewModel.targets.collectAsStateWithLifecycle()
    val library by viewModel.library.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val shareState by viewModel.shareState.collectAsStateWithLifecycle()
    val warning by viewModel.warning.collectAsStateWithLifecycle()
    val updateState by viewModel.updateState.collectAsStateWithLifecycle()
    val transitionMarker by viewModel.transitionMarker.collectAsStateWithLifecycle()
    val fullPlayer = route == Routes.NOW_PLAYING
    val chromeHidden = fullPlayer || route == Routes.DEVICES || route == Routes.LOGIN || route == Routes.ACCOUNT_SWITCH || route == Routes.WELCOME
    // Collection artwork runs under the status bar, so these screens take no top inset and
    // apply it themselves where the content actually needs it.
    val artworkUnderStatusBar = fullPlayer || route == Routes.DETAIL

    Scaffold(
        // Transparent so the artwork wash below shows through every screen.
        containerColor = Color.Transparent,
    ) { padding ->
        // The full player paints its own artwork edge to edge, so the app wash stays out of its way.
        if (!fullPlayer) {
            ArtworkBackdrop(
                artworkUrl = playback.currentTrack?.artworkUrl.orEmpty(),
                animated = settings.animatedBackground,
            )
        }

        Box(Modifier.fillMaxSize()) {
            Box(
                if (artworkUnderStatusBar) {
                    Modifier.padding(bottom = padding.calculateBottomPadding())
                } else {
                    Modifier.padding(padding)
                },
            ) {
    OrchardNavigation(navController, viewModel, playback, targets, library, settings)

                if (!chromeHidden) {
                    Column(modifier = Modifier.align(Alignment.BottomCenter)) {
                        CanopyReadout(
                            playback = playback,
                            transition = transitionMarker,
                            onOpen = { navController.navigate(Routes.NOW_PLAYING) },
                            onToggle = viewModel::togglePlayback,
                            onNext = viewModel::next,
                            onPrevious = viewModel::previous,
                        )
                        OrchardBottomBar(route) { navController.openTopLevel(it) }
                    }
                }
            }

            dev.sfg.orchard.mobile.ui.components.WarningBanner(
                message = warning,
                onDismiss = viewModel::dismissWarning,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .zIndex(100f),
            )

            dev.sfg.orchard.mobile.ui.components.UpdateDialog(
                state = updateState,
                onInstall = viewModel::installUpdate,
                onDismiss = viewModel::dismissUpdate,
            )
        }
    }

    shareState?.let { state ->
        SongShareBottomSheet(
            state = state,
            onDismiss = viewModel::dismissShare,
        )
    }
}

@Composable
private fun OrchardNavigation(
    nav: NavHostController,
    viewModel: OrchardViewModel,
    playback: dev.sfg.orchard.mobile.model.PlaybackSnapshot,
    targets: dev.sfg.orchard.mobile.model.PlaybackTargetState,
    library: dev.sfg.orchard.mobile.model.LibrarySnapshot,
    settings: dev.sfg.orchard.mobile.model.OrchardSettings,
) {
    val home by viewModel.home.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val search by viewModel.search.collectAsStateWithLifecycle()
    val history by viewModel.searchHistory.collectAsStateWithLifecycle()
    val detail by viewModel.detail.collectAsStateWithLifecycle()
    val detailArtwork by viewModel.detailArtwork.collectAsStateWithLifecycle()
    val lyrics by viewModel.lyrics.collectAsStateWithLifecycle()
    val artistImages by viewModel.artistImages.collectAsStateWithLifecycle()
    val auth by viewModel.auth.collectAsStateWithLifecycle()
    val discordAuth by viewModel.discordAuth.collectAsStateWithLifecycle()
    val discordConnection by viewModel.discordConnection.collectAsStateWithLifecycle()
    val libraryFilter by viewModel.libraryFilter.collectAsStateWithLifecycle()
    val isOnline by viewModel.isOnline.collectAsStateWithLifecycle()
    val downloads by viewModel.downloads.collectAsStateWithLifecycle()
    val downloadsList = androidx.compose.runtime.remember(downloads) { downloads.values.toList() }
    val downloadedTrackIds by viewModel.downloadedTrackIds.collectAsStateWithLifecycle()
    val downloadingTrackIds by viewModel.downloadingTrackIds.collectAsStateWithLifecycle()
    val totalBytesUsed by viewModel.totalBytesUsed.collectAsStateWithLifecycle()
    val connectMessage by viewModel.connectMessage.collectAsStateWithLifecycle()
    val connectProtocolVersion by viewModel.connectProtocolVersion.collectAsStateWithLifecycle()
    val connectAudioEngine by viewModel.connectAudioEngine.collectAsStateWithLifecycle()
    val connectRemoteVolume by viewModel.connectRemoteVolume.collectAsStateWithLifecycle()
    val localTargetSelected = targets.selected is PlaybackTarget.LocalPhone
    val canControlQueue = localTargetSelected || connectProtocolVersion >= 2
    val canShuffle = localTargetSelected || connectProtocolVersion >= 2
    val context = androidx.compose.ui.platform.LocalContext.current
    val startDestination = if (settings.onboardingCompleted) Routes.HOME else Routes.WELCOME
    var playlistPickerTrack by remember { mutableStateOf<dev.sfg.orchard.mobile.model.Track?>(null) }

    NavHost(navController = nav, startDestination = startDestination) {
        composable(Routes.WELCOME) {
            WelcomeScreen(
                settings = settings,
                auth = auth,
                onUpdateSettings = viewModel::updateSettings,
                onSignIn = { nav.navigate(Routes.LOGIN) },
                onSignOut = viewModel::signOut,
                onFinish = {
                    viewModel.updateSettings(settings.copy(onboardingCompleted = true))
                    nav.navigate(Routes.HOME) {
                        popUpTo(Routes.WELCOME) { inclusive = true }
                    }
                },
            )
        }
        composable(Routes.HOME) {
            HomeScreen(
                state = home,
                library = library,
                auth = auth,
                downloads = downloadsList,
                downloadedTrackIds = downloadedTrackIds,
                isOffline = !isOnline,
                onRefresh = viewModel::refreshHome,
                onSearch = { nav.openTopLevel(Routes.SEARCH) },
                onLibrary = { filter ->
                    viewModel.selectLibraryFilter(filter)
                    nav.openTopLevel(Routes.LIBRARY)
                },
                onDevices = { nav.navigate(Routes.DEVICES) },
                onPlay = { viewModel.play(it, "Home") },
                onOpenDetail = { id -> viewModel.openDetail(id); nav.navigate(Routes.detail(id)) },
            )
        }
        composable(Routes.SEARCH) {
            SearchScreen(
                query = query,
                state = search,
                history = history,
                onQueryChange = viewModel::updateQuery,
                onSubmit = viewModel::runSearch,
                onClearHistory = viewModel::clearSearchHistory,
                downloadedTrackIds = downloadedTrackIds,
                downloadingTrackIds = downloadingTrackIds,
                onPlay = { viewModel.play(it, "Search") },
                onPlayNext = if (canControlQueue) viewModel::playNext else null,
                onAddToQueue = if (canControlQueue) viewModel::addToQueue else null,
                onAddToPlaylist = { playlistPickerTrack = it },
                onDownloadTrack = viewModel::downloadTrack,
                onRemoveDownloadTrack = viewModel::removeDownload,
                onOpenDetail = { id -> viewModel.openDetail(id); nav.navigate(Routes.detail(id)) },
                onShare = viewModel::shareTrack,
            )
        }
        composable(Routes.LIBRARY) {
            LibraryScreen(
                library = library,
                filter = libraryFilter,
                onFilterChange = viewModel::selectLibraryFilter,
                downloads = downloadsList,
                downloadedTrackIds = downloadedTrackIds,
                downloadingTrackIds = downloadingTrackIds,
                totalBytesUsed = totalBytesUsed,
                onPlay = { viewModel.play(it, libraryFilter.sourceTitle()) },
                onPlayNext = if (canControlQueue) viewModel::playNext else null,
                onAddToQueue = if (canControlQueue) viewModel::addToQueue else null,
                onOpenDetail = { id -> viewModel.openDetail(id); nav.navigate(Routes.detail(id)) },
                onDownloadTrack = viewModel::downloadTrack,
                onRemoveDownloadTrack = viewModel::removeDownload,
                onShare = viewModel::shareTrack,
            )
        }
        composable(Routes.DOWNLOADS) {
            dev.sfg.orchard.mobile.ui.screens.DownloadsScreen(
                downloads = downloadsList,
                totalBytesUsed = totalBytesUsed,
                onPlay = { viewModel.play(it, "Downloads") },
                onRemoveDownload = viewModel::removeDownload,
            )
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                settings = settings,
                auth = auth,
                discordAuth = discordAuth,
                discordConnection = discordConnection,
                onSettings = viewModel::updateSettings,
                onAutoplayEnabled = viewModel::setAutoplayEnabled,
                onSignIn = { nav.navigate(Routes.LOGIN) },
                onSwitchAccount = { nav.navigate(Routes.ACCOUNT_SWITCH) },
                onSignOut = viewModel::signOut,
                onConnectDiscord = { viewModel.connectDiscord(context) },
                onDisconnectDiscord = viewModel::disconnectDiscord,
                onConnectSpotify = { nav.navigate(Routes.SPOTIFY_LOGIN) },
                onDevices = { nav.navigate(Routes.DEVICES) },
                onWelcome = { nav.navigate(Routes.WELCOME) },
            )
        }
        composable(Routes.LOGIN) {
            NativeLoginScreen(
                auth = auth,
                onBegin = viewModel::beginSignIn,
                onSession = viewModel::completeSignIn,
                onCancel = viewModel::cancelSignIn,
                // Scoped to this entry so a repeated call cannot pop whatever
                // sent the user here. From Welcome that would be the whole back
                // stack, leaving an empty NavHost and a black screen.
                onComplete = { nav.popBackStack(Routes.LOGIN, inclusive = true) },
            )
        }
        composable(Routes.ACCOUNT_SWITCH) {
            NativeLoginScreen(
                auth = auth,
                onBegin = viewModel::beginSignIn,
                onSession = viewModel::completeSignIn,
                onCancel = viewModel::cancelSignIn,
                onComplete = { nav.popBackStack(Routes.ACCOUNT_SWITCH, inclusive = true) },
                switchingAccount = true,
            )
        }
        composable(Routes.SPOTIFY_LOGIN) {
            dev.sfg.orchard.mobile.ui.screens.SpotifyLoginScreen(
                onSpdcCaptured = { spdc ->
                    viewModel.updateSettings(settings.copy(spotifySpdc = spdc))
                    nav.popBackStack()
                },
                onCancel = { nav.popBackStack() },
            )
        }
        composable(
            route = Routes.DETAIL,
            arguments = listOf(navArgument("id") { type = androidx.navigation.NavType.StringType }),
        ) { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            // A restored navigation stack may recreate this screen after the
            // process state holder has been lost; reload its actual route id.
            androidx.compose.runtime.LaunchedEffect(id) {
                val current = (detail as? LoadState.Content)?.value?.id
                if (id.isNotBlank() && current != id) viewModel.openDetail(id)
            }
            val isSaved = (detail as? LoadState.Content)?.value?.let { detailVal ->
                when (detailVal.kind) {
                    CatalogKind.ALBUM -> library.savedAlbums.any { it.id == detailVal.id }
                    CatalogKind.PLAYLIST -> library.savedPlaylists.any { it.id == detailVal.id }
                    CatalogKind.ARTIST -> library.savedArtists.any { it.id == detailVal.id }
                    CatalogKind.TRACK -> false
                }
            } ?: false

            // The hero is square, so the wide asset crops far better than the 9:16 one the
            // full player wants; vertical is only a fallback when there is nothing else.
            val animatedArtworkUrl = if (settings.animatedArtwork) {
                detailArtwork?.let { it.videoUrl.ifBlank { it.videoUrlVertical } }.orEmpty()
            } else ""

            // Only artist pages swap in TheAudioDB's photograph; albums keep their cover, which
            // also drives the page palette.
            val portrait = artistImages?.portraitUrl.orEmpty()
            val shownDetail = (detail as? LoadState.Content)
                ?.takeIf { portrait.isNotBlank() && it.value.kind == CatalogKind.ARTIST }
                ?.let { LoadState.Content(it.value.copy(artworkUrl = portrait)) }
                ?: detail

            DetailScreen(
                state = shownDetail,
                onBack = nav::popBackStack,
                onPlayAll = { tracks, source -> viewModel.playAll(tracks, contextTitle = source) },
                onShuffle = { tracks, source -> viewModel.shuffleAll(tracks, source) },
                shuffleAvailable = canShuffle,
                onPlay = { track, source -> viewModel.play(track, source) },
                onPlayTrack = { tracks, index, source -> viewModel.playAll(tracks, startIndex = index, contextTitle = source) },
                onPlayNext = if (canControlQueue) viewModel::playNext else null,
                onAddToQueue = if (canControlQueue) viewModel::addToQueue else null,
                onAddToPlaylist = { playlistPickerTrack = it },
                onRemoveFromPlaylist = viewModel::removeTrackFromCurrentPlaylist,
                onSave = viewModel::saveDetail,
                onOpenDetail = { next -> viewModel.openDetail(next); nav.navigate(Routes.detail(next)) },
                isSaved = isSaved,
                downloadedTrackIds = downloadedTrackIds,
                downloadingTrackIds = downloadingTrackIds,
                onDownloadTrack = viewModel::downloadTrack,
                onDownloadTracks = viewModel::downloadTracks,
                onRemoveDownloadTrack = viewModel::removeDownload,
                onRemoveDownloadTracks = viewModel::removeDownloads,
                animatedArtworkUrl = animatedArtworkUrl,
                artistPortraitUrl = artistImages?.portraitUrl.orEmpty(),
                onShareTrack = viewModel::shareTrack,
                onShareCollection = viewModel::shareCollection,
                onFetchSectionItems = viewModel::fetchSectionItems,
            )
        }
        composable(Routes.NOW_PLAYING) {
            val liked = playback.currentTrack?.let { track -> library.likedTracks.any { it.id == track.id } } == true
            val transition by viewModel.transitionMarker.collectAsStateWithLifecycle()
            val activeBitrate by viewModel.activeBitrate.collectAsStateWithLifecycle()
            val autoplayLoading by viewModel.autoplayLoading.collectAsStateWithLifecycle()
            val autoplayError by viewModel.autoplayError.collectAsStateWithLifecycle()
            NowPlayingScreen(
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
                onBack = nav::popBackStack,
                onToggle = viewModel::togglePlayback,
                onPrevious = viewModel::previous,
                onNext = viewModel::next,
                onSeek = viewModel::seek,
                onShuffle = viewModel::toggleShuffle,
                onRepeat = viewModel::cycleRepeat,
                onLiked = { playback.currentTrack?.let(viewModel::toggleLiked) },
                onDevices = { nav.navigate(Routes.DEVICES) },
                onPlayQueueIndex = viewModel::playQueueIndex,
                onRemoveQueueIndex = viewModel::removeQueueIndex,
                onMoveQueueItem = viewModel::moveQueueItem,
                onClearUpcoming = viewModel::clearUpcoming,
                downloadedTrackIds = downloadedTrackIds,
                onDownloadTrack = viewModel::downloadTrack,
                onRemoveDownloadTrack = viewModel::removeDownload,
                onAddToPlaylist = { playlistPickerTrack = it },
                onShare = { playback.currentTrack?.let(viewModel::shareTrack) },
                // Leaves the player so the collection is not buried underneath it.
                onOpenCollection = { id ->
                    viewModel.openDetail(id)
                    nav.popBackStack()
                    nav.navigate(Routes.detail(id))
                },
            )
        }
        composable(Routes.DEVICES) {
            DevicesScreen(
                targets = targets,
                connectMessage = connectMessage,
                protocolVersion = connectProtocolVersion,
                audioEngine = connectAudioEngine,
                onBack = nav::popBackStack,
                onSelect = viewModel::selectTarget,
                onPair = viewModel::pairDevice,
                onDisconnect = viewModel::disconnectDevice,
                onPresetSelect = viewModel::setAudioEnginePreset,
                onToggleAutoEq = viewModel::toggleAutoEq,
                onToggleManualEq = viewModel::toggleManualEq,
            )
        }
    }

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

private fun NavHostController.openTopLevel(route: String) {
    val startId = graph.findStartDestination().id
    popBackStack(startId, false)
    navigate(route) {
        popUpTo(startId) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

private fun LibraryFilter.sourceTitle(): String = when (this) {
    LibraryFilter.PLAYLISTS -> "Your playlists"
    LibraryFilter.ARTISTS -> "Your artists"
    LibraryFilter.ALBUMS -> "Your albums"
    LibraryFilter.SONGS -> "Liked songs"
    LibraryFilter.RECENT -> "Recently played"
    LibraryFilter.DOWNLOADS -> "Downloads"
}
