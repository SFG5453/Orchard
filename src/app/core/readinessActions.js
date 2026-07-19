import { computed, ref } from 'vue';
import { readPinnedTracks } from '../browse/pinsPersistence.js';
import { readPlaybackState } from '../playback/queuePersistence.js';

const SETUP_STORAGE_KEY = 'orchard:setup-state';
const WELCOME_RESET_STORAGE_KEY = 'orchard:welcome-reset-version';
const WELCOME_RESET_VERSION = '1.0.0';
const WELCOME_RESET_PENDING_MS = 20_000;
const BACKUP_SCHEMA_VERSION = 1;
const STORAGE_KEYS = [
  'orchard:user-preferences',
  'orchard:playback-state',
  'orchard:pinned-tracks',
  'orchard:replay-events',
  'orchard:session-history',
  'orchard:audio-engine',
  'orchard:last-seen-changelog',
  'orchard:support-identity',
  SETUP_STORAGE_KEY
];

function canStore() {
  return typeof window !== 'undefined' && Boolean(window.localStorage);
}

function readStoredJson(key, fallback) {
  if (!canStore()) return fallback;

  try {
    return JSON.parse(window.localStorage.getItem(key) || JSON.stringify(fallback));
  } catch {
    return fallback;
  }
}

function readArrayStorage(key) {
  const value = readStoredJson(key, []);
  return Array.isArray(value) ? value : [];
}

function writeStoredJson(key, value) {
  if (!canStore()) return;

  try {
    window.localStorage.setItem(key, JSON.stringify(value));
  } catch {
    // Readiness helpers should not interrupt playback or navigation.
  }
}

function storageSnapshot() {
  if (!canStore()) return {};

  return Object.fromEntries(
    STORAGE_KEYS
      .map((key) => [key, window.localStorage.getItem(key)])
      .filter(([, value]) => value !== null)
  );
}

function storageByteCount(snapshot = {}) {
  return Object.values(snapshot).reduce((total, value) => total + String(value || '').length, 0);
}

function formatTime(timestamp) {
  if (!timestamp) return 'Never';
  return new Intl.DateTimeFormat(undefined, {
    month: 'short',
    day: 'numeric',
    hour: 'numeric',
    minute: '2-digit'
  }).format(new Date(timestamp));
}

function downloadJson(filename, data) {
  const blob = new Blob([`${JSON.stringify(data, null, 2)}\n`], { type: 'application/json' });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = filename;
  anchor.click();
  URL.revokeObjectURL(url);
}

function readFileAsText(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || ''));
    reader.onerror = () => reject(reader.error || new Error('Could not read backup file.'));
    reader.readAsText(file);
  });
}

function statusTone(status) {
  if (status === 'ok') return 'Ready';
  if (status === 'error') return 'Needs attention';
  return 'Review';
}

export function installReadinessActions(ctx) {
  ctx.setupState = ref({
    completed: false,
    welcomeCompleted: false,
    appearanceReviewed: false,
    audioEngineReviewed: false,
    connectReviewed: false,
    discordReviewed: false,
    updateChecked: false,
    backupCreatedAt: 0,
    ...readStoredJson(SETUP_STORAGE_KEY, {})
  });
  ctx.setupPanelOpen = ref(!ctx.setupState.value.completed);
  ctx.diagnostics = ref({ generatedAt: 0, items: [], report: null });
  ctx.diagnosticsMessage = ref('');
  ctx.backupMessage = ref('');

  ctx.persistSetupState = function persistSetupState() {
    writeStoredJson(SETUP_STORAGE_KEY, ctx.setupState.value);
  };

  ctx.updateSetupState = function updateSetupState(patch = {}) {
    ctx.setupState.value = { ...ctx.setupState.value, ...patch };
    ctx.persistSetupState();
  };

  ctx.resetWelcomeForCurrentVersion = async function resetWelcomeForCurrentVersion() {
    if (!canStore() || ctx.appVersion !== WELCOME_RESET_VERSION) return false;

    const stored = window.localStorage.getItem(WELCOME_RESET_STORAGE_KEY) || '';
    if (stored === WELCOME_RESET_VERSION) return false;

    if (stored.startsWith(`pending:${WELCOME_RESET_VERSION}:`)) {
      const startedAt = Number(stored.split(':').pop()) || 0;
      if (Date.now() - startedAt < WELCOME_RESET_PENDING_MS) return true;
    }

    window.localStorage.setItem(WELCOME_RESET_STORAGE_KEY, `pending:${WELCOME_RESET_VERSION}:${Date.now()}`);
    ctx.updateSetupState({ completed: false, welcomeCompleted: false });

    try {
      await ctx.emitWithReply('auth:logout');
      window.localStorage.setItem(WELCOME_RESET_STORAGE_KEY, WELCOME_RESET_VERSION);
      window.orchardApp?.showWelcome?.();
      return true;
    } catch (error) {
      window.localStorage.removeItem(WELCOME_RESET_STORAGE_KEY);
      throw error;
    }
  };

  ctx.setupProgress = computed(() => {
    const items = ctx.setupItems.value;
    const done = items.filter((item) => item.done).length;
    return { done, total: items.length, label: `${done}/${items.length}` };
  });

  ctx.setupItems = computed(() => [
    {
      key: 'signin',
      icon: 'login',
      title: 'Sign in to YouTube Music',
      detail: ctx.authState.value.signedIn ? 'Connected to your music account.' : 'Required for library, search, and playback.',
      done: ctx.authState.value.signedIn,
      action: () => ctx.startLogin()
    },
    {
      key: 'updates',
      icon: 'system_update_alt',
      title: 'Check updates',
      detail: ctx.updateState.value.message || 'Make sure the 1.0 app can reach release metadata.',
      done: Boolean(ctx.setupState.value.updateChecked),
      action: async () => {
        await ctx.checkForUpdates();
        ctx.updateSetupState({ updateChecked: true });
      }
    },
    {
      key: 'appearance',
      icon: 'palette',
      title: 'Review appearance',
      detail: 'Theme, accent color, and background motion are ready to tune.',
      done: Boolean(ctx.setupState.value.appearanceReviewed),
      action: () => {
        ctx.updateSetupState({ appearanceReviewed: true });
        document.getElementById('settings-appearance')?.scrollIntoView({ behavior: 'smooth' });
      }
    },
    {
      key: 'audio-engine',
      icon: 'equalizer',
      title: 'Tune the Audio Engine',
      detail: ctx.audioEngineConfig.value.autoEqEnabled
        ? 'Automatic EQ is ready to adapt as tracks play.'
        : (ctx.audioEngineConfig.value.eqEnabled
          ? `${ctx.audioEngineActivePreset.value === 'custom' ? 'Custom' : ctx.audioEngineActivePreset.value} EQ is active.`
          : 'Choose automatic EQ, a manual preset, or direct playback.'),
      done: Boolean(ctx.setupState.value.audioEngineReviewed),
      action: () => {
        ctx.updateSetupState({ audioEngineReviewed: true });
        document.getElementById('settings-audio-engine')?.scrollIntoView({ behavior: 'smooth' });
      }
    },
    {
      key: 'connect',
      icon: 'phonelink',
      title: 'Prepare Orchard Connect',
      detail: ctx.orchardConnect.value.devices.length
        ? `${ctx.orchardConnect.value.devices.length} phone${ctx.orchardConnect.value.devices.length === 1 ? '' : 's'} paired.`
        : 'Generate a QR code and pair a phone on your LAN.',
      done: ctx.orchardConnect.value.devices.length > 0 || Boolean(ctx.setupState.value.connectReviewed),
      action: async () => {
        await ctx.loadOrchardConnectInfo({ refresh: true });
        ctx.updateSetupState({ connectReviewed: true });
        document.getElementById('settings-connect')?.scrollIntoView({ behavior: 'smooth' });
      }
    },
    {
      key: 'discord',
      icon: 'extension',
      title: 'Review Discord presence',
      detail: ctx.discordRpcEnabled.value ? 'Discord Rich Presence is enabled.' : 'Discord Rich Presence is off.',
      done: Boolean(ctx.setupState.value.discordReviewed),
      action: () => {
        ctx.updateSetupState({ discordReviewed: true });
        document.getElementById('settings-integrations')?.scrollIntoView({ behavior: 'smooth' });
      }
    },
    {
      key: 'backup',
      icon: 'archive',
      title: 'Create a backup',
      detail: `Last backup: ${formatTime(ctx.setupState.value.backupCreatedAt)}`,
      done: Boolean(ctx.setupState.value.backupCreatedAt),
      action: () => ctx.exportOrchardBackup()
    }
  ]);

  ctx.finishSetup = function finishSetup() {
    ctx.updateSetupState({ completed: true });
    ctx.setupPanelOpen.value = false;
  };

  ctx.completeWelcomeSetup = function completeWelcomeSetup() {
    if (!ctx.authState.value.signedIn) {
      ctx.errorMessage.value = 'Sign in before opening Orchard.';
      return;
    }

    ctx.updateSetupState({ completed: true, welcomeCompleted: true });
    ctx.setupPanelOpen.value = false;
    window.orchardApp?.finishWelcome?.();
  };

  ctx.reopenSetup = function reopenSetup() {
    ctx.setupPanelOpen.value = true;
    ctx.updateSetupState({ completed: false });
  };

  ctx.collectDiagnostics = async function collectDiagnostics() {
    if (ctx.socket.value?.connected) {
      try {
        await ctx.loadOrchardConnectInfo();
      } catch {
        // The diagnostics list will still show the bridge state below.
      }
    }

    const storage = storageSnapshot();
    let appInfo = {};
    try {
      appInfo = await window.orchardApp?.diagnostics?.();
    } catch {
      appInfo = {};
    }

    const connectStable = Boolean(ctx.orchardConnect.value.serverUrl);
    const items = [
      {
        label: 'Desktop bridge',
        status: ctx.socketState.value === 'connected' ? 'ok' : 'warning',
        detail: ctx.socketState.value
      },
      {
        label: 'YouTube Music account',
        status: ctx.authState.value.signedIn ? 'ok' : 'warning',
        detail: ctx.authState.value.signedIn ? 'Signed in' : ctx.authState.value.status
      },
      {
        label: 'Updater',
        status: ctx.updateState.value.status === 'error' ? 'error' : 'ok',
        detail: ctx.updateState.value.message || ctx.updateStatusLabel.value
      },
      {
        label: 'Orchard Connect',
        status: connectStable ? 'ok' : 'warning',
        detail: connectStable ? ctx.orchardConnect.value.serverUrl : 'Waiting for bridge state'
      },
      {
        label: 'Discord Rich Presence',
        status: ctx.discordRpcEnabled.value ? 'ok' : 'warning',
        detail: ctx.discordRpcEnabled.value ? 'Enabled' : 'Disabled'
      },
      {
        label: 'Local storage',
        status: canStore() ? 'ok' : 'error',
        detail: `${Object.keys(storage).length} keys, ${storageByteCount(storage)} bytes`
      },
      {
        label: 'Audio Engine',
        status: typeof window.AudioContext === 'function' || typeof window.webkitAudioContext === 'function' ? 'ok' : 'warning',
        detail: ctx.audioEngineConfig.value.enabled
          ? `${ctx.audioEngineConfig.value.autoEqEnabled ? 'Automatic EQ active' : ctx.audioEngineConfig.value.eqEnabled ? 'Manual EQ active' : 'Direct mode'}, ${ctx.audioOutputDevices.value.length} output option${ctx.audioOutputDevices.value.length === 1 ? '' : 's'}`
          : 'Bypassed'
      },
      {
        label: 'Audio formats',
        status: ctx.supportedAudioMimes().length ? 'ok' : 'warning',
        detail: ctx.supportedAudioMimes().map((item) => item.mimeType).join(', ') || 'No reported support'
      },
      {
        label: 'Video formats',
        status: ctx.supportedVideoMimes().length ? 'ok' : 'warning',
        detail: ctx.supportedVideoMimes().slice(0, 3).map((item) => item.mimeType).join(', ') || 'No reported support'
      }
    ];

    const report = {
      generatedAt: new Date().toISOString(),
      app: {
        version: ctx.appVersion,
        platform: appInfo.platform || navigator.platform,
        chrome: appInfo.chrome || '',
        electron: appInfo.electron || '',
        node: appInfo.node || '',
        dev: Boolean(appInfo.dev)
      },
      state: {
        socket: ctx.socketState.value,
        auth: ctx.authState.value.status,
        update: ctx.updateState.value.status,
        connectDevices: ctx.orchardConnect.value.devices.length,
        activeView: ctx.activeView.value,
        activeTrack: ctx.activeTrack.value?.id || ''
      },
      storageKeys: Object.keys(storage),
      checks: items
    };

    ctx.diagnostics.value = { generatedAt: Date.now(), items, report };
    ctx.diagnosticsMessage.value = 'Diagnostics refreshed.';
  };

  ctx.diagnosticTone = statusTone;

  ctx.copyDiagnostics = async function copyDiagnostics() {
    if (!ctx.diagnostics.value.report) await ctx.collectDiagnostics();
    const text = JSON.stringify(ctx.diagnostics.value.report, null, 2);
    await navigator.clipboard?.writeText(text);
    ctx.diagnosticsMessage.value = 'Diagnostic report copied.';
  };

  ctx.exportOrchardBackup = async function exportOrchardBackup() {
    const storage = storageSnapshot();
    let connectDevices = null;
    try {
      connectDevices = ctx.socket.value?.connected
        ? await ctx.emitWithReply('connect:devices-export')
        : null;
    } catch {
      connectDevices = null;
    }

    const backup = {
      app: 'orchard',
      schemaVersion: BACKUP_SCHEMA_VERSION,
      orchardVersion: ctx.appVersion,
      exportedAt: new Date().toISOString(),
      storage,
      connectDevices
    };
    const day = new Date().toISOString().slice(0, 10);
    downloadJson(`orchard-backup-${ctx.appVersion}-${day}.json`, backup);
    ctx.updateSetupState({ backupCreatedAt: Date.now() });
    ctx.backupMessage.value = 'Backup downloaded.';
  };

  ctx.applyImportedPreferences = function applyImportedPreferences() {
    const preferences = ctx.readUserPreferences();
    ctx.accentColorSource.value = preferences.accentColorSource;
    ctx.autoplayEnabled.value = preferences.autoplayEnabled;
    ctx.crossfadeEnabled.value = preferences.crossfadeEnabled;
    ctx.crossfadeMode.value = preferences.crossfadeMode;
    ctx.crossfadeSeconds.value = preferences.crossfadeSeconds;
    ctx.customArtistPagesEnabled.value = preferences.customArtistPagesEnabled;
    ctx.playbackStatePersistenceEnabled.value = preferences.playbackStatePersistenceEnabled;
    ctx.youtubeHistoryEnabled.value = preferences.youtubeHistoryEnabled;
    ctx.customAccentColor.value = preferences.customAccentColor;
    ctx.discordRpcEnabled.value = preferences.discordRpcEnabled;
    ctx.discordRpcActivityName.value = preferences.discordRpcActivityName;
    ctx.immersiveBackgroundsEnabled.value = preferences.immersiveBackgroundsEnabled;
    ctx.immersiveBackgroundIntensity.value = preferences.immersiveBackgroundIntensity;
    ctx.immersiveBackgroundMotion.value = preferences.immersiveBackgroundMotion;
    ctx.volumeNormalizationEnabled.value = preferences.volumeNormalizationEnabled;
    ctx.repeatMode.value = preferences.repeatMode;
    ctx.shuffleEnabled.value = preferences.shuffleEnabled;
    ctx.themePreference.value = preferences.themePreference;
    ctx.volume.value = preferences.volume;
    const audioEngineState = readStoredJson('orchard:audio-engine', null);
    if (audioEngineState?.config) ctx.audioEngineConfig.value = audioEngineState.config;
    if (audioEngineState?.trackGains) ctx.audioEngineTrackGains.value = audioEngineState.trackGains;
  };

  ctx.importOrchardBackup = async function importOrchardBackup(file) {
    if (!file) return;
    const backup = JSON.parse(await readFileAsText(file));
    if (backup?.app !== 'orchard' || backup.schemaVersion !== BACKUP_SCHEMA_VERSION || !backup.storage) {
      throw new Error('That file is not a compatible Orchard backup.');
    }

    for (const [key, value] of Object.entries(backup.storage)) {
      if (STORAGE_KEYS.includes(key) && typeof value === 'string') {
        window.localStorage.setItem(key, value);
      }
    }

    if (backup.connectDevices && ctx.socket.value?.connected) {
      await ctx.emitWithReply('connect:devices-import', backup.connectDevices);
      await ctx.loadOrchardConnectInfo();
    }

    ctx.applyImportedPreferences();
    if (!ctx.playbackStatePersistenceEnabled.value) ctx.clearPlaybackState();
    const playbackState = ctx.playbackStatePersistenceEnabled.value
      ? readPlaybackState()
      : ctx.emptyPlaybackState();
    ctx.activeTrack.value = playbackState.activeTrack;
    ctx.queue.value = playbackState.queue;
    ctx.history.value = playbackState.history;
    ctx.shuffleSourceQueue.value = playbackState.shuffleSourceQueue;
    ctx.pinnedTracks.value = readPinnedTracks();
    ctx.replayEvents.value = readArrayStorage('orchard:replay-events');
    ctx.sessionHistory.value = readArrayStorage('orchard:session-history');
    ctx.setupState.value = { ...ctx.setupState.value, ...readStoredJson(SETUP_STORAGE_KEY, {}) };
    ctx.backupMessage.value = 'Backup restored. Restart Orchard to reload the saved queue and history.';
  };
}
