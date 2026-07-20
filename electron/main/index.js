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
import { setupDesktopControls } from '../platform/desktopControls.js';
import { registerScreenshotCapture } from '../platform/screenshotCapture.js';
import { setupSystemMediaHandlers } from '../platform/systemMedia.js';
import { welcomeRequiredAtLaunch } from '../platform/welcomeState.js';
import { configureWindowOpenHandler, registerDevToolsShortcut, registerWindowControls } from '../platform/windowControls.js';
import { resolveRuntimePaths } from './runtimePaths.js';

const require = createRequire(import.meta.url);
const { app, BrowserWindow, Menu, Tray, clipboard, globalShortcut, ipcMain, nativeImage, net, safeStorage, screen, session, shell } = require('electron');
const isDev = !app.isPackaged && Boolean(process.env.VITE_DEV_SERVER_URL);
const allowDevTools = !app.isPackaged;
const runtimePaths = resolveRuntimePaths({ app, isDev });

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

const { appIconPath } = runtimePaths;
const useNativeTitlebar = process.platform === 'linux' && (process.env.ORCHARD_NATIVE_TITLEBAR ? !/^(0|false|no)$/i.test(process.env.ORCHARD_NATIVE_TITLEBAR) : /kde|kwin|plasma/i.test([process.env.XDG_CURRENT_DESKTOP, process.env.XDG_SESSION_DESKTOP, process.env.DESKTOP_SESSION, process.env.KDE_FULL_SESSION].filter(Boolean).join(' ')));
const youtubeMusicOrigin = 'https://music.youtube.com';
const youtubeWebOrigin = 'https://www.youtube.com';
const youtubeMusicClientVersion = '1.20260114.01.00';
const welcomeResetVersion = '1.0.0';
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
const youtubeLikes = createYouTubeLikesService({ ensureSignedIn, refreshBrowserAuth });
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
  youtubeWebOrigin
});
const normalizeTrackInfo = createTrackInfoNormalizer({ bestThumbnail });
const { proxyStream, resolveStream } = playbackService;
const subscribedArtists = createSubscribedArtistsService({ authState, cachePath: path.join(app.getPath('userData'), 'youtubei-cache', 'subscriptions') }).subscribedArtists;
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
  fetchFeed, fetchMusicLibraryCategory,
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
    fetchFeed, fetchMusicLibraryCategory,
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
    shelfItems,
    signOutAuth,
    startAccountSwitch,
    startBrowserSignIn,
    subscribedArtists,
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
  mainWindow = new BrowserWindow({
    width: 1220,
    height: 780,
    minWidth: 760,
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
  registerAppHandlers({
    app,
    clearDiscordPresence,
    ipcMain,
    isDev,
    resolveDiscordSongLink,
    resolveDiscordSongLinkDetails,
    setDiscordPresence,
    showMainWindow,
    showWelcomeWindow
  });
  audioAnalysis = setupAudioAnalysisService({
    cachePath: path.join(app.getPath('userData'), 'audio-analysis-cache.json'),
    ipcMain,
    nativeModulePath: runtimePaths.nativeModulePath
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
  updates = setupOrchardUpdates({ isDev });
  await startBridge();
  await createMainWindow();
  const needsWelcome = await welcomeRequiredAtLaunch(mainWindow, {
    currentVersion: app.getVersion(),
    resetVersion: welcomeResetVersion
  });
  if (needsWelcome) await showWelcomeWindow();
  else showMainWindow();
  setTimeout(() => {
    updates.checkForUpdates();
  }, 3000);

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createMainWindow().then(async () => {
        const needsWelcome = await welcomeRequiredAtLaunch(mainWindow, {
          currentVersion: app.getVersion(),
          resetVersion: welcomeResetVersion
        });
        if (needsWelcome) await showWelcomeWindow();
        else showMainWindow();
      });
    }
  });
});

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') app.quit();
});
// Services own native handles/listeners/servers; async cache flushing is best-effort here.
app.on('before-quit', () => {
  void audioAnalysis?.stop();
  resetDiscordRpcClient();
  systemMedia?.stop();
  desktopControls?.stop();
  bridge?.close();
});
