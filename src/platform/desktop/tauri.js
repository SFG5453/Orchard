import { invoke } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';
import { installTauriWheelNormalization } from './tauriScroll.js';

function fireAndForget(command, args) {
  void invoke(command, args).catch(() => {});
}

function listener(event, callback) {
  let disposed = false;
  let unlisten = null;
  void listen(event, ({ payload }) => callback(payload)).then((remove) => {
    if (disposed) remove();
    else unlisten = remove;
  });
  return () => {
    disposed = true;
    unlisten?.();
  };
}

export async function installTauriPlatform(target = globalThis) {
  const initialSessionState = await invoke('session_state_all').catch(() => ({}));
  const sessionState = initialSessionState && typeof initialSessionState === 'object' && !Array.isArray(initialSessionState)
    ? { ...initialSessionState }
    : {};
  target.orchardDesktopRuntime = 'tauri-linux';
  target.orchardDisposeWheelNormalization?.();
  target.orchardDisposeWheelNormalization = installTauriWheelNormalization(target);
  target.orchardWindow = {
    minimize: () => invoke('window_minimize'),
    toggleMaximize: () => invoke('window_toggle_maximize'),
    setFullscreen: (fullscreen) => invoke('window_set_fullscreen', { fullscreen: Boolean(fullscreen) }),
    close: () => invoke('window_close'),
    setZoomFactor: (factor) => {
      document.documentElement.style.zoom = String(Math.max(0.85, Math.min(1.5, Number(factor) || 1)));
    }
  };
  target.orchardApp = {
    diagnostics: () => Promise.resolve({
      runtime: 'webview',
      fetch: 'tauri-http',
      platform: 'linux',
      version: __APP_VERSION__
    }),
    graphicsMode: (selectedMode = 'automatic') => Promise.resolve({
      selectedMode,
      appliedMode: selectedMode,
      integratedGpuSupported: false,
      platform: 'linux'
    }),
    restart: () => location.reload(),
    onSettingsSync: () => () => {},
    viewLicense: () => Promise.resolve(false)
  };
  target.orchardClipboard = {
    writeText: (value) => navigator.clipboard.writeText(String(value || ''))
  };
  target.orchardDiscord = {
    setPresence: (presence) => invoke('discord_set_presence', { presence }),
    clearPresence: () => invoke('discord_clear_presence')
  };
  target.orchardSessionState = {
    get: (key) => key ? sessionState[key] ?? null : { ...sessionState },
    set: (key, value) => {
      const normalizedKey = String(key || '');
      if (!normalizedKey) return false;
      sessionState[normalizedKey] = value ?? null;
      fireAndForget('session_state_set', { key: normalizedKey, value: value ?? null });
      return true;
    }
  };
  target.orchardNetwork = {
    getProxyMode: () => Promise.resolve('direct'),
    setProxyMode: () => Promise.resolve('direct')
  };
  target.orchardSystemMedia = {
    nativeSystemMedia: true,
    setState: (state) => invoke('system_media_set', { state }),
    onCommand: (callback) => listener('system-media-command', callback)
  };
  target.orchardDesktopControls = {
    getCompactState: () => Promise.resolve(false),
    setCloseToTray: () => Promise.resolve(false),
    setState: () => Promise.resolve(),
    toggleCompact: () => Promise.resolve(false),
    onCompactState: () => () => {}
  };
  target.orchardAudioAnalysis = {
    available: () => Promise.resolve(true),
    renderTransition: (outgoing, incoming, options) => invoke('transition_render', { outgoing, incoming, options })
  };
  target.orchardNativeAudio = {
    load: (deck, source) => invoke('audio_load', { deck, source }),
    play: (deck) => invoke('audio_play', { deck }),
    pause: (deck) => invoke('audio_pause', { deck }),
    clear: (deck) => invoke('audio_clear', { deck }),
    seek: (deck, seconds) => invoke('audio_seek', { deck, seconds }),
    setVolume: (deck, volume) => fireAndForget('audio_set_volume', { deck, volume }),
    setRate: (deck, rate) => fireAndForget('audio_set_rate', { deck, rate }),
    setEngineConfig: (config = {}) => fireAndForget('audio_set_engine_config', {
      config: {
        enabled: config.enabled !== false,
        autoEqEnabled: Boolean(config.autoEqEnabled),
        eqEnabled: Boolean(config.eqEnabled),
        gains: Array.from(config.gains || [], (value) => Number(value) || 0),
        preampDb: Number(config.preampDb) || 0,
        outputGainDb: Number(config.outputGainDb) || 0,
        q: Number(config.q) || 1.1,
        balance: Number(config.balance) || 0
      }
    }),
    setAutoEqGains: (gains) => fireAndForget('audio_set_auto_eq_gains', {
      gains: Array.from(gains || [], (value) => Number(value) || 0)
    }),
    setTrackGain: (deck, gainDb) => fireAndForget('audio_set_track_gain', {
      deck,
      gainDb: Number(gainDb) || 0
    }),
    state: (deck) => invoke('audio_state', { deck })
  };
}
