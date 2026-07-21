import { computed, ref } from 'vue';
import { createAudioAnalyzer } from '../../audio/engine/audioAnalyzer.js';
import {
  AUTO_CROSSFADE_DEFAULTS,
  clampCrossfadeSeconds,
  normalizeCrossfadeMode,
  createAutoCrossfade
} from '../../audio/crossfade/autoCrossfade.js';
import orchardLogoUrl from '../../assets/orchard-logo.png';
import {
  ACCENT_COLOR_SOURCE_OPTIONS,
  APPEARANCE_DEFAULTS,
  IMMERSIVE_BACKGROUND_INTENSITY_OPTIONS,
  IMMERSIVE_BACKGROUND_MOTION_OPTIONS,
  THEME_PREFERENCE_OPTIONS,
  immersiveBackgroundOpacity,
  normalizeAccentColorSource,
  normalizeCustomAccentColor,
  normalizeImmersiveBackgroundIntensity,
  normalizeImmersiveBackgroundMotion,
  normalizeThemePreference
} from '../appearance/appearancePreferences.js';
import {
  clampVolume,
  clearPlaybackState,
  normalizeRepeatMode,
  readPlaybackState,
  writePlaybackState
} from '../playback/queuePersistence.js';
import { SONG_CACHE_DEFAULTS, clampSongCacheMaxSizeMb } from '../playback/songCachePreferences.js';

export function installState(ctx) {
  ctx.orchardLogoUrl = orchardLogoUrl;
  ctx.appVersion = __APP_VERSION__;
  ctx.nativeTitlebar = ref(new URLSearchParams(window.location.search).get('nativeTitlebar') === '1');
  ctx.USER_PREFERENCES_STORAGE_KEY = 'orchard:user-preferences';
  ctx.DEFAULT_USER_PREFERENCES = {
    ...APPEARANCE_DEFAULTS,
    autoplayEnabled: true,
    crossfadeEnabled: true,
    crossfadeMode: AUTO_CROSSFADE_DEFAULTS.mode,
    crossfadeSeconds: AUTO_CROSSFADE_DEFAULTS.fadeSeconds,
    customArtistPagesEnabled: true,
    playbackStatePersistenceEnabled: true,
    youtubeHistoryEnabled: true,
    discordRpcEnabled: true,
    discordRpcActivityName: 'orchard',
    immersiveBackgroundsEnabled: true,
    songCacheEnabled: SONG_CACHE_DEFAULTS.enabled,
    songCacheMaxSizeMb: SONG_CACHE_DEFAULTS.maxSizeMb,
    volumeNormalizationEnabled: false,
    repeatMode: 'off',
    shuffleEnabled: false,
    volume: 0.85
  };
  ctx.accentColorSourceOptions = ACCENT_COLOR_SOURCE_OPTIONS;
  ctx.immersiveBackgroundIntensityOptions = IMMERSIVE_BACKGROUND_INTENSITY_OPTIONS;
  ctx.immersiveBackgroundMotionOptions = IMMERSIVE_BACKGROUND_MOTION_OPTIONS;
  ctx.themePreferenceOptions = THEME_PREFERENCE_OPTIONS;
  ctx.immersiveBackgroundOpacity = immersiveBackgroundOpacity;
  ctx.discordRpcActivityNameOptions = [
    { label: 'Orchard', value: 'orchard' },
    { label: 'YouTube Music', value: 'youtubeMusic' }
  ];
  ctx.normalizeDiscordRpcActivityName = function normalizeDiscordRpcActivityName(value) {
    return ctx.discordRpcActivityNameOptions.some((option) => option.value === value) ? value : 'orchard';
  };
  ctx.normalizeUserPreferences = function normalizeUserPreferences(preferences = {}) {
    return {
      accentColorSource: normalizeAccentColorSource(preferences.accentColorSource),
      autoplayEnabled: typeof preferences.autoplayEnabled === 'boolean'
        ? preferences.autoplayEnabled
        : ctx.DEFAULT_USER_PREFERENCES.autoplayEnabled,
      crossfadeEnabled: typeof preferences.crossfadeEnabled === 'boolean'
        ? preferences.crossfadeEnabled
        : ctx.DEFAULT_USER_PREFERENCES.crossfadeEnabled,
      crossfadeMode: normalizeCrossfadeMode(preferences.crossfadeMode),
      crossfadeSeconds: clampCrossfadeSeconds(preferences.crossfadeSeconds),
      customArtistPagesEnabled: typeof preferences.customArtistPagesEnabled === 'boolean'
        ? preferences.customArtistPagesEnabled
        : ctx.DEFAULT_USER_PREFERENCES.customArtistPagesEnabled,
      playbackStatePersistenceEnabled: typeof preferences.playbackStatePersistenceEnabled === 'boolean'
        ? preferences.playbackStatePersistenceEnabled
        : ctx.DEFAULT_USER_PREFERENCES.playbackStatePersistenceEnabled,
      youtubeHistoryEnabled: typeof preferences.youtubeHistoryEnabled === 'boolean'
        ? preferences.youtubeHistoryEnabled
        : ctx.DEFAULT_USER_PREFERENCES.youtubeHistoryEnabled,
      customAccentColor: normalizeCustomAccentColor(preferences.customAccentColor),
      discordRpcEnabled: typeof preferences.discordRpcEnabled === 'boolean'
        ? preferences.discordRpcEnabled
        : ctx.DEFAULT_USER_PREFERENCES.discordRpcEnabled,
      discordRpcActivityName: ctx.normalizeDiscordRpcActivityName(preferences.discordRpcActivityName),
      immersiveBackgroundsEnabled: typeof preferences.immersiveBackgroundsEnabled === 'boolean'
        ? preferences.immersiveBackgroundsEnabled
        : ctx.DEFAULT_USER_PREFERENCES.immersiveBackgroundsEnabled,
      immersiveBackgroundIntensity: normalizeImmersiveBackgroundIntensity(preferences.immersiveBackgroundIntensity),
      immersiveBackgroundMotion: normalizeImmersiveBackgroundMotion(preferences.immersiveBackgroundMotion),
      songCacheEnabled: typeof preferences.songCacheEnabled === 'boolean'
        ? preferences.songCacheEnabled
        : ctx.DEFAULT_USER_PREFERENCES.songCacheEnabled,
      songCacheMaxSizeMb: clampSongCacheMaxSizeMb(preferences.songCacheMaxSizeMb),
      volumeNormalizationEnabled: typeof preferences.volumeNormalizationEnabled === 'boolean'
        ? preferences.volumeNormalizationEnabled
        : ctx.DEFAULT_USER_PREFERENCES.volumeNormalizationEnabled,
      repeatMode: normalizeRepeatMode(preferences.repeatMode),
      shuffleEnabled: typeof preferences.shuffleEnabled === 'boolean'
        ? preferences.shuffleEnabled
        : ctx.DEFAULT_USER_PREFERENCES.shuffleEnabled,
      themePreference: normalizeThemePreference(preferences.themePreference),
      volume: clampVolume(preferences.volume ?? ctx.DEFAULT_USER_PREFERENCES.volume)
    };
  };
  ctx.readUserPreferences = function readUserPreferences() {
    if (typeof window === 'undefined') return ctx.normalizeUserPreferences(ctx.DEFAULT_USER_PREFERENCES);

    try {
      const parsed = JSON.parse(window.localStorage.getItem(ctx.USER_PREFERENCES_STORAGE_KEY) || '{}');
      return ctx.normalizeUserPreferences(parsed);
    } catch {
      return ctx.normalizeUserPreferences(ctx.DEFAULT_USER_PREFERENCES);
    }
  };
  ctx.writeUserPreferences = function writeUserPreferences(preferences) {
    if (typeof window === 'undefined') return;

    try {
      window.localStorage.setItem(ctx.USER_PREFERENCES_STORAGE_KEY, JSON.stringify(preferences));
    } catch {
      // A locked-down storage backend should not make playback controls fail.
    }
  };
  ctx.initialUserPreferences = ctx.readUserPreferences();
  ctx.emptyPlaybackState = function emptyPlaybackState() {
    return { activeTrack: null, queue: [], history: [], shuffleSourceQueue: [] };
  };
  ctx.initialPlaybackState = ctx.initialUserPreferences.playbackStatePersistenceEnabled
    ? readPlaybackState()
    : ctx.emptyPlaybackState();
  ctx.clearPlaybackState = clearPlaybackState;
  ctx.writePlaybackState = function persistPlaybackState() {
    if (ctx.playbackStatePersistenceEnabled?.value === false) return;

    writePlaybackState({
      activeTrack: ctx.activeTrack.value,
      queue: ctx.queue.value,
      history: ctx.history.value,
      shuffleSourceQueue: ctx.shuffleSourceQueue.value
    });
  };
  ctx.socket = ref(null);
  ctx.viewportWidth = ref(typeof window === 'undefined' ? 1220 : window.innerWidth);
  ctx.syncViewportSize = function syncViewportSize() {
    ctx.viewportWidth.value = typeof window === 'undefined' ? 1220 : window.innerWidth;
  };
  ctx.orchardConnect = ref({
    status: 'idle',
    serverUrl: '',
    pairUrl: '',
    appPairUrl: '',
    webPairUrl: '',
    qrSvg: '',
    expiresAt: 0,
    pending: [],
    devices: []
  });
  ctx.orchardConnectPairingMessage = ref('');
  ctx.audioRef = ref(null);
  ctx.nextAudioRef = ref(null);
  ctx.videoRef = ref(null);
  ctx.videoAudioRef = ref(null);
  ctx.nowArtworkVideoRef = ref(null);
  ctx.rightPanelArtworkVideoRef = ref(null);
  ctx.fullscreenArtworkVideoRef = ref(null);
  ctx.fullscreenPlayerRef = ref(null);
  ctx.detailArtworkVideoRef = ref(null);
  ctx.query = ref('');
  ctx.activeView = ref('home');
  ctx.aboutDialogOpen = ref(false);
  const params = new URLSearchParams(window.location.search);
  const partyRoomId = params.get('party') || params.get('room') || (window.location.pathname.match(/\/rooms\/([A-Z2-9]+)/i)?.[1] || '');
  ctx.initialListeningPartyRoomId = partyRoomId;
  ctx.listeningPartyDialogOpen = ref(Boolean(partyRoomId));
  ctx.changelogDialogOpen = ref(false);
  ctx.updateDialogOpen = ref(false);
  ctx.fullscreenPlayerOpen = ref(false);
  ctx.smartCrossfadeMix = ref({
    id: 0,
    visible: false,
    durationMs: 0,
    fadeDurationMs: 0,
    style: 'equal_power',
    styleLabel: 'Smart mix',
    from: { id: '', title: '', artist: '', artwork: '' },
    to: { id: '', title: '', artist: '', artwork: '' },
    fromBpm: 0,
    toBpm: 0,
    fromKey: '',
    toKey: '',
    tempoShift: 0,
    transitionBeats: 0
  });
  ctx.smartCrossfadeMixTimer = 0;
  ctx.smartCrossfadeMixSequence = 0;
  ctx.compactWindow = ref(false);
  ctx.rightPanelMode = ref('queue');
  ctx.narrowWindow = computed(() => ctx.viewportWidth.value < 1180 && !ctx.compactWindow.value);
  ctx.sidebarMini = computed(() => ctx.viewportWidth.value < 900 && !ctx.compactWindow.value);
  ctx.sidebarWidth = computed(() => {
    if (ctx.sidebarMini.value) return 68;
    return ctx.viewportWidth.value < 1180 ? 216 : 232;
  });
  ctx.rightPanelWidth = computed(() => 288);
  ctx.rightPanelMounted = computed(() => (
    !ctx.compactWindow.value &&
    !['settings', 'support'].includes(ctx.activeView.value)
  ));
  ctx.rightPanelVisible = computed(() => (
    ctx.viewportWidth.value >= 1281 &&
    ctx.rightPanelMounted.value
  ));
  ctx.browseOrigin = ref('home');
  ctx.selectedFilter = ref('all');
  ctx.loading = ref(false);
  ctx.homeLoading = ref(false);
  ctx.browseLoading = ref(false);
  ctx.browseTrackPageLoading = ref(false);
  ctx.browseTrackPageError = ref('');
  ctx.socketState = ref('connecting');
  ctx.errorMessage = ref('');
  ctx.warningMessage = ref('');
  ctx.migrationState = ref({
    status: 'loading',
    version: '',
    notes: '',
    pubDate: '',
    platformKey: '',
    downloadUrl: '',
    error: ''
  });
  ctx.updateState = ref({
    status: 'idle',
    message: '',
    version: '',
    updateUrl: '',
    availableVersion: '',
    releaseDate: '',
    releaseNotes: [],
    progress: null,
    error: '',
    dev: false,
    content: {
      status: 'idle',
      message: '',
      sourceUrl: '',
      installedVersion: '',
      availableVersion: '',
      updatedAt: '',
      error: '',
      notes: [],
      userPackCount: 0
    }
  });
  ctx.searchResult = ref({ sections: [] });
  ctx.browseDetail = ref(null);
  ctx.artistGenre = ref({
    status: 'idle',
    browseId: '',
    album: '',
    genre: '',
    source: '',
    error: ''
  });
  ctx.artistGenreCache = new Map();
  ctx.sectionMoreDetail = ref(null);
  ctx.homeData = ref({
    home: { sections: [] },
    library: { sections: [] }
  });
  ctx.homeLoadPromise = null;
  ctx.podcastFeed = ref({ title: 'Podcasts', sections: [] });
  ctx.podcastLoading = ref(false);
  ctx.authState = ref({
    signedIn: false,
    status: 'signed_out',
    pending: null,
    error: '',
    user: null
  });
  ctx.accountMenuOpen = ref(false);
  ctx.accountSwitching = ref(false);
  ctx.activeTrack = ref(ctx.initialPlaybackState.activeTrack);
  ctx.activeAudioDeck = ref('main');
  ctx.lyricsState = ref({
    trackId: '',
    status: 'idle',
    mode: '',
    lines: [],
    source: '',
    providers: []
  });
  ctx.queue = ref(ctx.initialPlaybackState.queue);
  ctx.history = ref(ctx.initialPlaybackState.history);
  ctx.shuffleSourceQueue = ref(ctx.initialPlaybackState.shuffleSourceQueue);
  ctx.navigationHistory = ref([]);
  ctx.restoringNavigation = false;
  ctx.currentTime = ref(0);
  ctx.duration = ref(0);
  ctx.seekPosition = ref(0);
  ctx.isSeeking = ref(false);
  ctx.volume = ref(ctx.initialUserPreferences.volume);
  ctx.isPlaying = ref(false);
  ctx.buffering = ref(false);
  ctx.playbackError = ref('');
  ctx.activeMediaKind = ref(ctx.activeTrack.value?.mediaKind || 'audio');
  ctx.videoPlayerMinimized = ref(false);
  ctx.enhancedArtwork = ref(null);
  ctx.detailEnhancedArtwork = ref(null);
  ctx.playlistArtworkCollage = ref([]);
  ctx.playerBarAccent = ref(ctx.createPlayerBarAccent([47, 223, 147]));
  ctx.lastImmersiveArtworkImage = ref('');
  ctx.nowArtworkVideoFailed = ref(false);
  ctx.detailArtworkVideoFailed = ref(false);
  ctx.artworkCache = new Map();
  ctx.itunesAlbumLookupCache = new Map();
  ctx.nextTrackPreload = ref(null);
  ctx.playbackPlaylistContext = ref(null);
  ctx.autoplayEnabled = ref(ctx.initialUserPreferences.autoplayEnabled);
  ctx.accentColorSource = ref(ctx.initialUserPreferences.accentColorSource);
  ctx.customAccentColor = ref(ctx.initialUserPreferences.customAccentColor);
  ctx.autoplayLoading = ref(false);
  ctx.autoplayError = ref('');
  ctx.crossfadeEnabled = ref(ctx.initialUserPreferences.crossfadeEnabled);
  ctx.crossfadeMode = ref(ctx.initialUserPreferences.crossfadeMode);
  ctx.crossfadeModeOptions = [
    { label: 'Standard', value: 'standard' },
    { label: 'Smart', value: 'smart' }
  ];
  ctx.crossfadeSeconds = ref(ctx.initialUserPreferences.crossfadeSeconds);
  ctx.customArtistPagesEnabled = ref(ctx.initialUserPreferences.customArtistPagesEnabled);
  ctx.playbackStatePersistenceEnabled = ref(ctx.initialUserPreferences.playbackStatePersistenceEnabled);
  ctx.youtubeHistoryEnabled = ref(ctx.initialUserPreferences.youtubeHistoryEnabled);
  ctx.discordRpcEnabled = ref(ctx.initialUserPreferences.discordRpcEnabled);
  ctx.discordRpcActivityName = ref(ctx.initialUserPreferences.discordRpcActivityName);
  ctx.immersiveBackgroundsEnabled = ref(ctx.initialUserPreferences.immersiveBackgroundsEnabled);
  ctx.immersiveBackgroundIntensity = ref(ctx.initialUserPreferences.immersiveBackgroundIntensity);
  ctx.immersiveBackgroundMotion = ref(ctx.initialUserPreferences.immersiveBackgroundMotion);
  ctx.songCacheEnabled = ref(ctx.initialUserPreferences.songCacheEnabled);
  ctx.songCacheMaxSizeMb = ref(ctx.initialUserPreferences.songCacheMaxSizeMb);
  ctx.songCacheInventory = ref({
    settings: { enabled: ctx.songCacheEnabled.value, maxSizeMb: ctx.songCacheMaxSizeMb.value },
    directory: '',
    totalBytes: 0,
    entries: []
  });
  ctx.songCacheLoading = ref(false);
  ctx.songCachePrefetching = ref(false);
  ctx.songCacheMessage = ref('');
  ctx.themePreference = ref(ctx.initialUserPreferences.themePreference);
  ctx.volumeNormalizationEnabled = ref(ctx.initialUserPreferences.volumeNormalizationEnabled);
  ctx.repeatMode = ref(ctx.initialUserPreferences.repeatMode);
  ctx.shuffleEnabled = ref(ctx.initialUserPreferences.shuffleEnabled);
  ctx.resetUserPreferences = function resetUserPreferences() {
    const defaults = ctx.normalizeUserPreferences(ctx.DEFAULT_USER_PREFERENCES);
    ctx.accentColorSource.value = defaults.accentColorSource;
    ctx.autoplayEnabled.value = defaults.autoplayEnabled;
    ctx.crossfadeEnabled.value = defaults.crossfadeEnabled;
    ctx.crossfadeMode.value = defaults.crossfadeMode;
    ctx.crossfadeSeconds.value = defaults.crossfadeSeconds;
    ctx.customArtistPagesEnabled.value = defaults.customArtistPagesEnabled;
    ctx.playbackStatePersistenceEnabled.value = defaults.playbackStatePersistenceEnabled;
    ctx.youtubeHistoryEnabled.value = defaults.youtubeHistoryEnabled;
    ctx.customAccentColor.value = defaults.customAccentColor;
    ctx.discordRpcEnabled.value = defaults.discordRpcEnabled;
    ctx.discordRpcActivityName.value = defaults.discordRpcActivityName;
    ctx.immersiveBackgroundsEnabled.value = defaults.immersiveBackgroundsEnabled;
    ctx.immersiveBackgroundIntensity.value = defaults.immersiveBackgroundIntensity;
    ctx.immersiveBackgroundMotion.value = defaults.immersiveBackgroundMotion;
    ctx.songCacheEnabled.value = defaults.songCacheEnabled;
    ctx.songCacheMaxSizeMb.value = defaults.songCacheMaxSizeMb;
    ctx.volumeNormalizationEnabled.value = defaults.volumeNormalizationEnabled;
    ctx.repeatMode.value = defaults.repeatMode;
    ctx.shuffleEnabled.value = defaults.shuffleEnabled;
    ctx.themePreference.value = defaults.themePreference;
    ctx.volume.value = defaults.volume;
  };
  ctx.audioAnalyzer = createAudioAnalyzer({ createProcessor: ctx.audioEngine.createProcessor });
  ctx.autoCrossfade = createAutoCrossfade({
    analyzer: ctx.audioAnalyzer,
    settings: {
      fadeSeconds: ctx.crossfadeSeconds.value,
      mode: ctx.crossfadeMode.value
    }
  });
  ctx.artworkLookupRequest = 0;
  ctx.detailArtworkLookupRequest = 0;
  ctx.playlistArtworkCollageRequest = 0;
  ctx.nextPreloadRequest = 0;
  ctx.autoplayRequest = 0;
  ctx.autoplayRequestSeedId = '';
  ctx.autoplayRequestPromise = null;
  ctx.autoplaySuppressedTrackId = '';
  ctx.playerColorRequest = 0;
  ctx.lyricsRequest = 0;
  ctx.lyricAutoScrollPauseTimer = 0;
  ctx.lyricAutoScrollPausedUntil = 0;
  ctx.searchRequest = 0;
  ctx.crossfadeAnalysisRequest = 0;
  ctx.crossfadeAnalysisAbort = null;
  ctx.orchardConnectSyncTimer = 0;
  ctx.orchardConnectEventsBound = false;
  ctx.discordPresenceSyncTimer = 0;
  ctx.shareMessageTimer = 0;
  ctx.playbackStallTimer = 0;
  ctx.playbackStallRequest = 0;
  ctx.playTrackRequest = 0;
  ctx.searchDebounceTimer = 0;
  ctx.updateUnsubscribe = null;
  ctx.desktopControlsUnsubscribe = null;
  ctx.browseTrackPageRequest = 0;
  ctx.browseTrackPrefetchRequest = 0;
  ctx.browseTrackPrefetching = false;
  ctx.shelfRails = new Map();
  ctx.artworkApiProviders = [
    { id: 'm8tec', baseUrl: 'https://artwork.m8tec.top/' },
    { id: 'boidu', baseUrl: 'https://artwork.boidu.dev/' },
    { id: 'orchard', baseUrl: 'https://artwork.sfg545.dev/' }
  ];
  ctx.SEARCH_DEBOUNCE_MS = 320;
  ctx.lyricProviders = [
    { id: 'amlyrics', label: 'am-lyrics (BetterLyrics)' },
    { id: 'lrclib', label: 'LRCLIB' },
    { id: 'youtube', label: 'YouTube' }
  ];
  ctx.filters = [
    { label: 'All', value: 'all' },
    { label: 'Songs', value: 'songs' },
    { label: 'Videos', value: 'videos' },
    { label: 'Albums', value: 'albums' },
    { label: 'Artists', value: 'artists' },
    { label: 'Playlists', value: 'playlists' }
  ];
  ctx.audioMimeCandidates = [
    'audio/mp4; codecs="mp4a.40.2"',
    'audio/mp4; codecs="mp4a.40.5"',
    'audio/webm; codecs="opus"',
    'audio/webm; codecs="vorbis"'
  ];
  ctx.videoMimeCandidates = [
    'video/mp4; codecs="avc1.640028"',
    'video/mp4; codecs="avc1.64001F"',
    'video/mp4; codecs="avc1.4d401F"',
    'video/mp4; codecs="avc1.42001E"',
    'video/mp4; codecs="avc1.64001F, mp4a.40.2"',
    'video/mp4; codecs="avc1.4d401F, mp4a.40.2"',
    'video/mp4; codecs="avc1.42001E, mp4a.40.2"',
    'video/mp4',
    'video/webm; codecs="vp9"',
    'video/webm; codecs="vp8"',
    'video/webm'
  ];
}
