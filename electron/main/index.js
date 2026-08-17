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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

// Main-process composition root. It owns Electron lifecycle resources and
// injects privileged capabilities into isolated services and IPC registrars.
import { createRequire } from 'node:module';
import { pathToFileURL } from 'node:url';
import path from 'node:path';
import { Platform } from 'youtubei.js';
import { createAccountSummary } from '../auth/accountSummary.js';
import { createAuthService } from '../auth/authService.js';
import { createBrowserMusicApi } from '../auth/browserMusicApi.js';
import { setupAudioAnalysisService } from '../audio/audioAnalysisService.js';
import { startBridgeServer } from '../bridge/bridgeServer.js';
import { createArtistCatalog } from '../catalog/artistCatalog.js';
import { createArtistGenreResolver } from '../catalog/artistGenre.js';
import { createBrowseNormalizers } from '../catalog/browseNormalizers.js';
import { installInnertubeParserErrorHandler } from '../catalog/innertubeParserErrors.js';
import { createFutureAlbums } from '../catalog/futureAlbums.js';
import { createLyricsResolver } from '../catalog/lyricsResolver.js';
import { createMainFeeds } from '../catalog/mainFeeds.js';
import {
  clearDiscordPresence,
  resetDiscordRpcClient,
  resolveDiscordSongLink,
  resolveDiscordSongLinkDetails,
  setDiscordPresence
} from '../integrations/discordRpc.js';
import { setupGithubAuth } from '../integrations/githubAuth.js';
import { setupLastfm } from '../integrations/lastfm.js';
import { setupSpotify } from '../integrations/spotify.js';
import {
  asText,
  bestThumbnail,
  cleanedText,
  findDurationText,
  formatMillisDuration,
  hasExplicitBadge,
  normalizeTrack,
  normalizeTvLibrary,
  normalizedLooseText,
  textMatchesArtist,
  textMatchesTitle,
  textParts
} from '../catalog/musicText.js';
import { createMusicBrowse, musicBrowseRequest, musicBrowseRequests } from '../catalog/musicBrowse.js';
import { createPersonalizedRadio } from '../catalog/personalizedRadio.js';
import { createSearchUtils } from '../catalog/searchUtils.js';
import { createSubscribedArtistsService } from '../catalog/subscribedArtists.js';
import { createYouTubeHistoryService } from '../catalog/youtubeHistory.js';
import { createYouTubeLikesService } from '../catalog/youtubeLikes.js';
import { setupMigrationNotice } from '../integrations/migrationNotice.js';
import { setupOrchardUpdates } from '../integrations/updater.js';
import { createPreferredAudioTrack, createTrackInfoNormalizer } from '../playback/playbackFormats.js';
import { createMusicVideoFallback } from '../playback/musicVideoFallback.js';
import { createPlaybackService } from '../playback/playbackService.js';
import { registerAppHandlers } from '../platform/appHandlers.js';
import { registerClipboardHandlers } from '../platform/clipboard.js';
import { registerNetworkPreferences } from '../platform/networkPreferences.js';
import { setupDesktopControls } from '../platform/desktopControls.js';
import { registerScreenshotCapture } from '../platform/screenshotCapture.js';
import { setupSystemMediaHandlers } from '../platform/systemMedia.js';
import { setWelcomeCompleted, welcomeRequiredAtLaunch } from '../platform/welcomeState.js';
import { configureWindowOpenHandler, registerDevToolsShortcut, registerWindowControls } from '../platform/windowControls.js';
import { createGraphicsModeController, GRAPHICS_MODE_FILENAME } from './graphicsMode.js';
import { createSessionStateStore, SESSION_STATE_FILENAME } from './sessionState.js';
import { IPC_CHANNELS } from '../../shared/ipcChannels.js';
import { resolveRuntimePaths } from './runtimePaths.js';

// Installed before any InnerTube client exists, so no response can reach the
// stock handler that throws inside an asar.
installInnertubeParserErrorHandler();

const require = createRequire(import.meta.url);
const windowStateKeeper = require('electron-window-state');
const { app, BrowserWindow, Menu, Tray, clipboard, globalShortcut, ipcMain, nativeImage, net, safeStorage, screen, session, shell } = require('electron');
const isDev = !app.isPackaged && Boolean(process.env.VITE_DEV_SERVER_URL);
const isNiriSession = process.platform === 'linux' && (
  Boolean(process.env.NIRI_SOCKET) || /(?:^|:)niri(?:$|:)/i.test(process.env.XDG_CURRENT_DESKTOP || '')
);
const allowDevTools = !app.isPackaged;
const runtimePaths = resolveRuntimePaths({ app, isDev });
const graphicsMode = createGraphicsModeController({
  app,
  filePath: path.join(app.getPath('userData'), GRAPHICS_MODE_FILENAME)
});
const sessionState = createSessionStateStore({
  filePath: path.join(app.getPath('userData'), SESSION_STATE_FILENAME)
});

app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');
if (process.platform === 'linux') {
  app.commandLine.appendSwitch('disable-features', 'HardwareMediaKeyHandling');
}

Platform.shim.eval = async (data) => new Function(data.output)();
let mainWindow;
let welcomeWindow;
let bridge;
let audioAnalysis;
let updates;
let systemMedia;
let desktopControls;
let welcomeCompleted = false;
// Distinguishes "user closed the window" (which may mean hide-to-tray) from a
// real quit, where the close must be allowed through.
let quitting = false;

const { appIconPath } = runtimePaths;
const useNativeTitlebar = false;
const youtubeMusicOrigin = 'https://music.youtube.com';
const youtubeWebOrigin = 'https://www.youtube.com';
const youtubeMusicClientVersion = '1.20260213.01.00';
const youtubeMusicClientUserAgent = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36';
const accountSummary = createAccountSummary({ asText, bestThumbnail });
const authService = createAuthService({
  accountSummary,
  allowDevTools,
  getMainWindow: () => mainWindow,
  onAuthState: (state) => bridge?.emit('auth:state', state),
  youtubeMusicClientUserAgent,
  youtubeMusicClientVersion,
  youtubeMusicOrigin
});
const {
  authState,
  ensureSignedIn,
  getBrowserInnertube,
  getGuestInnertube,
  getInnertube,
  hasBrowserLoginCookie,
  musicClientForBrowse,
  musicClientForPlayback,
  publicAuthState,
  refreshBrowserAuth,
  restoreCachedSignIn,
  signOutAuth,
  startAccountSwitch,
  startBrowserSignIn
} = authService;
const youtubeLikes = createYouTubeLikesService({ ensureSignedIn, refreshBrowserAuth, getBrowserInnertube });
const resolveLyrics = createLyricsResolver({ musicClientForBrowse });
const browserMusicApi = createBrowserMusicApi({
  authState,
  musicBrowseRequest,
  musicBrowseRequests,
  youtubeMusicClientUserAgent,
  youtubeMusicClientVersion,
  youtubeMusicOrigin
});
const {
  cookieWithPlaybackDefaults,
  rawBrowserMusicBrowse,
  resolveMusicCollectionWithBrowserAuth,
  sendBrowserHistoryStat
} = browserMusicApi;
const youtubeHistory = createYouTubeHistoryService({
  ensureSignedIn,
  getGuestInnertube,
  refreshBrowserAuth,
  sendBrowserHistoryStat
});
const musicBrowse = createMusicBrowse({
  getGuestInnertube,
  hasBrowserLoginCookie,
  rawBrowserMusicBrowse,
  resolveMusicCollectionWithBrowserAuth
});
const { continueMusicPlaylistWithFallback, resolveMusicCollectionWithFallback } = musicBrowse;
const personalizedRadio = createPersonalizedRadio({
  musicClientForBrowse,
  resolveMusicCollectionWithFallback
});
const playbackService = createPlaybackService({
  authState,
  cookieWithPlaybackDefaults,
  getBrowserInnertube,
  getGuestInnertube,
  hasBrowserLoginCookie,
  refreshBrowserAuth,
  youtubeMusicClientUserAgent,
  youtubeMusicClientVersion,
  youtubeMusicOrigin,
  youtubeWebOrigin
});
const normalizeTrackInfo = createTrackInfoNormalizer({ bestThumbnail });
const { proxyHlsResource, proxyStream, resolveStream } = playbackService;
const subscribedArtistService = createSubscribedArtistsService({ authState, cachePath: path.join(app.getPath('userData'), 'youtubei-cache', 'subscriptions') });
const { setArtistSubscription, subscribedArtists } = subscribedArtistService;
const browseNormalizers = createBrowseNormalizers({
  asText,
  bestThumbnail,
  cleanedText,
  findDurationText,
  hasExplicitBadge,
  normalizeTrack,
  normalizedLooseText,
  textParts
});
const {
  browseContinuationTokenFromData,
  isExpandableBrowseSectionTitle,
  normalizeAlbum,
  normalizeBrowseSection,
  normalizePlaylist,
  normalizePlaylistPage,
  normalizeRawBrowseItem,
  rawBrowseDescription,
  rawBrowseItemsFromData,
  rawBrowseThumbnail,
  rawHeader,
  rawMicroformat,
  rawSectionList
} = browseNormalizers;
const {
  catalogAudioItems,
  fetchBrowserMusicHome,
  fetchFeed, fetchMusicHomeFeed, fetchMusicLibraryCategory,
  fetchMusicLibraryFeed,
  shelfItems
} = createMainFeeds({
  asText, browseContinuationTokenFromData,
  bridgeError,
  fetchRawBrowserMusicBrowse: rawBrowserMusicBrowse,
  hasBrowserLoginCookie,
  normalizeBrowseSection, normalizeRawBrowseItem,
  normalizeTrack,
  normalizeTvLibrary,
  rawBrowseItemsFromData, rawSectionList
});
const searchUtils = createSearchUtils({
  asText,
  bestThumbnail,
  hasExplicitBadge,
  normalizedLooseText,
  shelfItems,
  textParts
});
const {
  artistBrowseSectionItemMatches,
  dedupeMediaItems,
  futureTrackPlayableMatches,
  isSingleOrEpRelease,
  itemMatchesReleaseSection,
  mergeTrackMetadata,
  normalizeSearch,
  normalizedLookupText,
  searchCatalog,
  searchTrackAlbumMetadata,
  searchArtistShelfFallback
} = searchUtils;
const preferredAudioTrack = createPreferredAudioTrack({ normalizedLookupText, shelfItems });
const findMusicVideoFallback = createMusicVideoFallback({ normalizedLookupText, shelfItems });
const futureAlbums = createFutureAlbums({
  dedupeMediaItems,
  formatMillisDuration,
  futureTrackPlayableMatches,
  normalizedLooseText,
  textMatchesArtist,
  textMatchesTitle
});
const { resolveArtistGenre } = createArtistGenreResolver();
const {
  artistFutureAlbumMetadata,
  cacheFutureAlbumDetails,
  hydrateFutureAlbumDetails,
  mergeFutureAlbumsIntoSections,
  releaseAlbumMatches,
  releaseRadarForArtists,
  resolveFutureAlbum,
  resolveItunesAlbum
} = futureAlbums;
const artistCatalog = createArtistCatalog({
  asText,
  artistBrowseSectionItemMatches,
  artistFutureAlbumMetadata,
  browseContinuationTokenFromData,
  cacheFutureAlbumDetails,
  dedupeMediaItems,
  hydrateFutureAlbumDetails,
  isExpandableBrowseSectionTitle,
  isSingleOrEpRelease,
  itemMatchesReleaseSection,
  mergeFutureAlbumsIntoSections,
  mergeTrackMetadata,
  normalizeAlbum,
  normalizeBrowseSection,
  normalizeRawBrowseItem,
  normalizedLooseText,
  rawBrowseDescription,
  rawBrowseItemsFromData,
  rawBrowseThumbnail,
  rawHeader,
  rawMicroformat,
  rawSectionList,
  searchTrackAlbumMetadata,
  searchArtistShelfFallback
});
const { cachedArtistResult, hydrateArtist, normalizeArtistSection } = artistCatalog;

function bridgeError(error) {
  const context = error.browseContext ? ` (${error.browseContext})` : '';
  const details = typeof error.info === 'string' ? error.info.trim() : '';
  if (!details) return `${error.message}${context}`;

  try {
    const parsed = JSON.parse(details);
    const message = parsed.error?.message || parsed.error?.status;
    if (message) return `${error.message}${context}: ${message}`;
  } catch {
    const compact = details.replace(/\s+/g, ' ').slice(0, 220);
    if (compact) return `${error.message}${context}: ${compact}`;
  }

  return `${error.message}${context}`;
}

async function startBridge() {
  bridge = await startBridgeServer({
    bridgeError,
    catalogAudioItems,
    continueMusicPlaylistWithFallback,
    ensureSignedIn,
    fetchBrowserMusicHome,
    fetchFeed, fetchMusicHomeFeed, fetchMusicLibraryCategory,
    fetchMusicLibraryFeed,
    findMusicVideoFallback,
    getBrowserInnertube,
    getGuestInnertube,
    getInnertube,
    hasBrowserLoginCookie,
    cachedArtistResult,
    hydrateArtist,
    musicClientForBrowse,
    musicClientForPlayback,
    normalizeAlbum,
    normalizeArtistSection,
    normalizePlaylist,
    normalizePlaylistPage,
    normalizeSearch,
    normalizeTrackInfo,
    personalizedRadio,
    playback: playbackService,
    preferredAudioTrack,
    proxyHlsResource,
    proxyStream,
    publicAuthState,
    releaseAlbumMatches,
    releaseRadarForArtists,
    refreshBrowserAuth,
    resolveFutureAlbum,
    resolveArtistGenre,
    resolveItunesAlbum,
    resolveLyrics,
    resolveMusicCollectionWithFallback,
    resolveStream,
    restoreCachedSignIn,
    searchCatalog,
    shelfItems,
    signOutAuth,
    startAccountSwitch,
    startBrowserSignIn,
    subscribedArtists,
    setArtistSubscription,
    youtubeHistory,
    youtubeLikes,
    connectDevicesPath: path.join(app.getPath('userData'), 'orchard-connect-devices.json')
  });
}

function rendererUrl(mode = 'main') {
  const url = isDev
    ? new URL(process.env.VITE_DEV_SERVER_URL)
    : pathToFileURL(runtimePaths.rendererEntryPath);
  url.searchParams.set('socketPort', bridge.port);
  if (useNativeTitlebar) url.searchParams.set('nativeTitlebar', '1');
  if (mode === 'welcome') url.searchParams.set('welcome', '1');
  return url.toString();
}

function showMainWindow() {
  welcomeCompleted = true;
  if (!mainWindow || mainWindow.isDestroyed()) return;

  if (!mainWindow.isVisible()) mainWindow.show();
  mainWindow.focus();
  if (welcomeWindow && !welcomeWindow.isDestroyed()) welcomeWindow.close();
}

async function showWelcomeWindow() {
  welcomeCompleted = false;
  if (mainWindow && !mainWindow.isDestroyed()) mainWindow.hide();
  if (welcomeWindow && !welcomeWindow.isDestroyed()) {
    if (!welcomeWindow.webContents.isLoading()) {
      welcomeWindow.show();
      welcomeWindow.focus();
    }
    return;
  }

  await createWelcomeWindow();
}

async function createMainWindow() {
  // Restores size/position (and maximized state) across launches; `manage`
  // below re-attaches the resize/move/close listeners that keep it current.
  const windowState = windowStateKeeper({
    defaultWidth: 1220,
    defaultHeight: 780,
    file: 'orchard-window-state.json'
  });

  mainWindow = new BrowserWindow({
    ...(isNiriSession ? {} : { x: windowState.x, y: windowState.y }),
    width: isNiriSession ? 1100 : windowState.width,
    height: isNiriSession ? 760 : windowState.height,
    minWidth: isNiriSession ? 480 : 760,
    minHeight: 620,
    autoHideMenuBar: true,
    frame: useNativeTitlebar,
    show: false,
    backgroundColor: '#111111',
    icon: appIconPath,
    webPreferences: {
      contextIsolation: true,
      devTools: allowDevTools,
      nodeIntegration: false,
      preload: runtimePaths.preloadPath,
      sandbox: true
    }
  });

  // Niri is authoritative for tiled geometry. electron-window-state restoring or
  // recording bounds fights the compositor and produces visible edge oscillation.
  if (!isNiriSession) windowState.manage(mainWindow);
  configureWindowOpenHandler(mainWindow, shell);
  if (allowDevTools) registerDevToolsShortcut(mainWindow);
  desktopControls ||= setupDesktopControls({
    app,
    Menu,
    Tray,
    globalShortcut,
    ipcMain,
    nativeImage,
    getWindow: () => mainWindow,
    appIconPath
  });
  mainWindow.on('close', (event) => {
    if (quitting || !desktopControls?.closeToTray()) return;
    event.preventDefault();
    mainWindow.hide();
  });

  await mainWindow.loadURL(rendererUrl());
}

async function createWelcomeWindow() {
  welcomeWindow = new BrowserWindow({
    width: 880,
    height: 720,
    minWidth: 720,
    minHeight: 620,
    autoHideMenuBar: true,
    frame: useNativeTitlebar,
    show: false,
    backgroundColor: '#080a08',
    icon: appIconPath,
    webPreferences: {
      contextIsolation: true,
      devTools: allowDevTools,
      nodeIntegration: false,
      preload: runtimePaths.preloadPath,
      sandbox: true
    }
  });

  configureWindowOpenHandler(welcomeWindow, shell);
  if (allowDevTools) registerDevToolsShortcut(welcomeWindow);
  welcomeWindow.once('ready-to-show', () => {
    welcomeWindow?.show();
    welcomeWindow?.focus();
  });
  welcomeWindow.on('closed', () => {
    welcomeWindow = null;
    if (!welcomeCompleted && mainWindow && !mainWindow.isDestroyed() && !mainWindow.isVisible()) {
      app.quit();
    }
  });

  await welcomeWindow.loadURL(rendererUrl('welcome'));
}

app.whenReady().then(async () => {
  Menu.setApplicationMenu(null);
  session.defaultSession.setPermissionCheckHandler((webContents, permission) =>
    webContents === mainWindow?.webContents &&
    (permission === 'geolocation' || permission === 'speaker-selection')
  );
  session.defaultSession.setPermissionRequestHandler((webContents, permission, callback) => {
    callback(webContents === mainWindow?.webContents &&
      (permission === 'geolocation' || permission === 'speaker-selection'));
  });
  registerWindowControls({ BrowserWindow, ipcMain, screen });
  registerClipboardHandlers({ clipboard, ipcMain });
  // Awaited here so the stored proxy mode is in force before any window requests
  // its first image; applying it later would leave the opening screen's artwork
  // going out through the proxy the listener asked Orchard to ignore.
  await registerNetworkPreferences({ app, ipcMain, session }).restore();
  // Synchronous on purpose: the renderer seeds the queue and the last page from
  // this while it builds its initial state, and an async read would mean
  // starting on an empty queue and rewriting it a tick later.
  ipcMain.on(IPC_CHANNELS.SESSION_STATE.GET, (event, key) => {
    event.returnValue = sessionState.get(typeof key === 'string' ? key : '');
  });
  ipcMain.on(IPC_CHANNELS.SESSION_STATE.SET, (_event, key, value) => {
    sessionState.set(key, value);
  });
  registerAppHandlers({
    app,
    clearDiscordPresence,
    completeWelcome: () => setWelcomeCompleted(sessionState, true),
    ipcMain,
    isDev,
    licensePath: runtimePaths.licensePath,
    graphicsMode,
    getMainWindow: () => mainWindow,
    resolveDiscordSongLink,
    resolveDiscordSongLinkDetails,
    setDiscordPresence,
    shell,
    showMainWindow,
    showWelcomeWindow,
    resetWelcome: () => setWelcomeCompleted(sessionState, false)
  });
  audioAnalysis = setupAudioAnalysisService({
    cachePath: path.join(app.getPath('userData'), 'audio-analysis-cache.json'),
    ipcMain,
    nativeModulePath: runtimePaths.nativeModulePath,
    beatModelPath: runtimePaths.beatModelPath,
    vocalModelPath: runtimePaths.vocalModelPath
  });
  registerScreenshotCapture({ BrowserWindow, ipcMain });
  systemMedia = setupSystemMediaHandlers({ ipcMain, app, getWindow: () => mainWindow });
  setupMigrationNotice({
    ipcMain,
    shell,
    fetchImpl: (url, options) => net.fetch(url, options)
  });
  setupGithubAuth({ app, ipcMain, net, safeStorage, shell });
  setupLastfm({ app, ipcMain, net, safeStorage, shell });
  setupSpotify({ app, ipcMain, net, safeStorage });
  updates = setupOrchardUpdates({ isDev });
  await startBridge();
  await createMainWindow();
  const needsWelcome = await welcomeRequiredAtLaunch(mainWindow, sessionState);
  if (needsWelcome) await showWelcomeWindow();
  else showMainWindow();
  setTimeout(() => {
    updates.checkForUpdates();
  }, 3000);

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createMainWindow().then(async () => {
        const needsWelcome = await welcomeRequiredAtLaunch(mainWindow, sessionState);
        if (needsWelcome) await showWelcomeWindow();
        else showMainWindow();
      });
    }
  });
});

app.on('window-all-closed', () => {
  // On macOS the app outlives its windows, so this is the moment the renderer
  // stops being able to tell us anything -- `before-quit` may be hours away.
  sessionState.flush();
  if (process.platform !== 'darwin') app.quit();
});
// Services own native handles/listeners/servers; async cache flushing is best-effort here.
app.on('before-quit', () => {
  quitting = true;
  // First, and synchronously: this is the last chance to get "where was I?" on
  // disk, and everything below it can afford to lose a few milliseconds.
  sessionState.flush();
  void audioAnalysis?.stop();
  resetDiscordRpcClient();
  systemMedia?.stop();
  desktopControls?.stop();
  bridge?.close();
});
