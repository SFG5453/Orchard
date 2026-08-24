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

// Security boundary between sandboxed renderer code and privileged Electron APIs.
// Keep this file dependency-free and expose only narrow, structured operations.
// Channel literals mirror shared/ipcChannels.js because a sandboxed preload
// cannot load arbitrary local modules; test/ipcChannels.test.js prevents drift.
const { contextBridge, ipcRenderer, webFrame } = require('electron');

function randomInt(maxExclusive) {
  const max = Number(maxExclusive);
  if (!Number.isSafeInteger(max) || max <= 0 || max > 0x100000000) {
    throw new RangeError('maxExclusive must be a positive safe integer up to 2^32.');
  }

  const getRandomValues = globalThis.crypto?.getRandomValues?.bind(globalThis.crypto);
  if (typeof getRandomValues !== 'function') {
    throw new Error('Crypto random values are unavailable.');
  }

  const range = 0x100000000;
  const limit = Math.floor(range / max) * max;
  const buffer = new Uint32Array(1);
  do {
    getRandomValues(buffer);
  } while (buffer[0] >= limit);
  return buffer[0] % max;
}

contextBridge.exposeInMainWorld('orchardWindow', {
  minimize: () => ipcRenderer.invoke('window:minimize'),
  toggleMaximize: () => ipcRenderer.invoke('window:toggle-maximize'),
  setFullscreen: (fullscreen) => ipcRenderer.invoke('window:set-fullscreen', fullscreen),
  close: () => ipcRenderer.invoke('window:close'),
  setZoomFactor: (factor) => webFrame.setZoomFactor(Math.max(0.85, Math.min(1.5, Number(factor) || 1)))
});

contextBridge.exposeInMainWorld('orchardApp', {
  captureScreenshot: () => ipcRenderer.invoke('app:capture-screenshot'),
  diagnostics: () => ipcRenderer.invoke('app:diagnostics'),
  finishWelcome: () => ipcRenderer.invoke('app:finish-welcome'),
  graphicsMode: (value) => value === undefined
    ? ipcRenderer.invoke('app:graphics-mode')
    : ipcRenderer.invoke('app:graphics-mode', value),
  restart: () => ipcRenderer.invoke('app:restart'),
  showWelcome: (options = {}) => ipcRenderer.invoke('app:show-welcome', {
    resetCompletion: options?.resetCompletion === true
  }),
  viewLicense: () => ipcRenderer.invoke('app:view-license')
});

contextBridge.exposeInMainWorld('orchardDiscord', {
  setPresence: (presence) => ipcRenderer.invoke('discord:set-presence', presence),
  clearPresence: () => ipcRenderer.invoke('discord:clear-presence')
});

contextBridge.exposeInMainWorld('orchardLastfm', {
  status: () => ipcRenderer.invoke('lastfm:status'),
  connect: () => ipcRenderer.invoke('lastfm:connect'),
  complete: () => ipcRenderer.invoke('lastfm:complete'),
  disconnect: () => ipcRenderer.invoke('lastfm:disconnect'),
  updateNowPlaying: (track) => ipcRenderer.invoke('lastfm:now-playing', { track }),
  scrobble: (track, timestamp) => ipcRenderer.invoke('lastfm:scrobble', { track, timestamp })
});

contextBridge.exposeInMainWorld('orchardSpotify', {
  status: () => ipcRenderer.invoke('spotify:status'),
  connect: () => ipcRenderer.invoke('spotify:connect'),
  saveSpdc: (spdc) => ipcRenderer.invoke('spotify:save-spdc', { spdc }),
  disconnect: () => ipcRenderer.invoke('spotify:disconnect'),
  // Callers pass reactive track objects, which are not structured-cloneable.
  // Flatten to the plain fields the canvas lookup actually uses.
  getCanvas: (target) => ipcRenderer.invoke('spotify:get-canvas', {
    target: {
      title: String(target?.title || ''),
      artist: String(target?.artist || ''),
      artists: Array.isArray(target?.artists) ? target.artists.map((name) => String(name || '')) : [],
      album: String(target?.album || '')
    }
  })
});

// "Where was I?" state: the playback queue and the last page. Read
// synchronously because the renderer seeds its initial state from it; written
// fire-and-forget because the main process owns the flush at quit.
contextBridge.exposeInMainWorld('orchardSessionState', {
  get: (key) => ipcRenderer.sendSync('session-state:get', String(key || '')),
  set: (key, value) => ipcRenderer.send('session-state:set', String(key || ''), value)
});

contextBridge.exposeInMainWorld('orchardSongLinks', {
  resolve: (presence) => ipcRenderer.invoke('song-links:resolve', presence),
  resolveDetails: (presence) => ipcRenderer.invoke('song-links:resolve', { ...presence, includeDetails: true })
});

contextBridge.exposeInMainWorld('orchardClipboard', {
  writeText: (value) => ipcRenderer.invoke('clipboard:write-text', value)
});

// Album art and update checks resolve the system proxy; playback does not. This
// lets the listener put the rest of the app on the same footing as playback when
// the machine's proxy is unreachable.
contextBridge.exposeInMainWorld('orchardNetwork', {
  getProxyMode: () => ipcRenderer.invoke('network:get-proxy-mode'),
  setProxyMode: (mode) => ipcRenderer.invoke('network:set-proxy-mode', mode)
});

contextBridge.exposeInMainWorld('orchardCrypto', {
  randomInt: (maxExclusive) => randomInt(Number(maxExclusive))
});

// The renderer never receives require() or the native addon. PCM crosses this
// structured-clone boundary and is shape/size checked again in the main process.
contextBridge.exposeInMainWorld('orchardAudioAnalysis', {
  available: () => ipcRenderer.invoke('audio-analysis:available'),
  get: (trackId) => ipcRenderer.invoke('audio-analysis:get', trackId),
  store: (trackId, result) => ipcRenderer.invoke('audio-analysis:store', { trackId, result }),
  debug: (event, details = {}) => ipcRenderer.invoke('audio-analysis:debug', { event, details }),
  // `priority` follows ANALYSIS_PRIORITIES. The main process uses it to decide
  // whether a track is worth the extra Essentia confidence pass, which costs
  // several times the native analysis and must not run at Best Mix scale.
  // `beatWindows` is optional head/tail PCM at the beat model's sample rate;
  // it rides along only for transition-priority requests.
  analyze: (trackId, samples, sampleRate, duration, priority, beatWindows) => ipcRenderer.invoke('audio-analysis:analyze', {
    trackId,
    samples,
    sampleRate,
    duration,
    priority,
    beatWindows
  }),
  renderTransition: (outgoing, incoming, options) => ipcRenderer.invoke('audio-analysis:render-transition', {
    outgoing,
    incoming,
    options
  }),
  // Vocal presence across one overlap slice, used to duck the outgoing track's
  // mids only where it is actually singing. Resolves null whenever the model
  // has no opinion; the caller falls back to a flat duck.
  vocalMask: (channels, sampleRate) => ipcRenderer.invoke('audio-analysis:vocal-mask', {
    channels,
    sampleRate
  })
});

contextBridge.exposeInMainWorld('orchardLegacyInstall', {
  getState: () => ipcRenderer.invoke('legacy-install:get-state'),
  refresh: () => ipcRenderer.invoke('legacy-install:refresh'),
  remove: () => ipcRenderer.invoke('legacy-install:remove'),
  dismiss: () => ipcRenderer.invoke('legacy-install:dismiss')
});

contextBridge.exposeInMainWorld('orchardGithub', {
  status: () => ipcRenderer.invoke('github-auth:status'),
  connect: () => ipcRenderer.invoke('github-auth:connect'),
  disconnect: () => ipcRenderer.invoke('github-auth:disconnect'),
  createIssue: (input) => ipcRenderer.invoke('github-auth:create-issue', input)
});

contextBridge.exposeInMainWorld('orchardUpdates', {
  getState: () => ipcRenderer.invoke('updates:get-state'),
  check: () => ipcRenderer.invoke('updates:check'),
  checkContent: (options) => ipcRenderer.invoke('updates:check-content', options),
  downloadExternal: () => ipcRenderer.invoke('updates:download-external'),
  importArtistPack: () => ipcRenderer.invoke('updates:import-artist-pack'),
  getUserArtistPacks: () => ipcRenderer.invoke('updates:get-user-artist-packs'),
  readArtistPackArchive: (archiveUrl) => ipcRenderer.invoke('updates:read-artist-pack-archive', archiveUrl),
  revealExternal: () => ipcRenderer.invoke('updates:reveal-external'),
  install: (options = {}) => ipcRenderer.invoke('updates:install', {
    keepOldVersions: options?.keepOldVersions === true
  }),
  setChannel: (channel) => ipcRenderer.invoke('updates:set-channel', channel),
  onState: (callback) => {
    if (typeof callback !== 'function') return () => {};

    const listener = (_event, state) => callback(state);
    ipcRenderer.on('updates:state', listener);

    return () => {
      ipcRenderer.removeListener('updates:state', listener);
    };
  }
});

contextBridge.exposeInMainWorld('orchardSystemMedia', {
  // Every desktop platform now goes through the native-media addon, so the
  // renderer must not also drive Chromium's mediaSession. Leaving both live on
  // Windows is what produced two competing entries in the OS media flyout.
  nativeSystemMedia: true,
  setState: (state) => ipcRenderer.invoke('system-media:set-state', state),
  onCommand: (callback) => {
    if (typeof callback !== 'function') return () => {};

    const listener = (_event, command) => callback(command);
    ipcRenderer.on('system-media:command', listener);

    return () => {
      ipcRenderer.removeListener('system-media:command', listener);
    };
  }
});

contextBridge.exposeInMainWorld('orchardDesktopControls', {
  getCompactState: () => ipcRenderer.invoke('desktop-controls:compact-state'),
  setCloseToTray: (enabled) => ipcRenderer.invoke('desktop-controls:close-to-tray', Boolean(enabled)),
  setState: (state) => ipcRenderer.invoke('desktop-controls:set-state', state),
  toggleCompact: () => ipcRenderer.invoke('desktop-controls:toggle-compact'),
  onCompactState: (callback) => {
    if (typeof callback !== 'function') return () => {};

    const listener = (_event, compact) => callback(Boolean(compact));
    ipcRenderer.on('desktop-controls:compact-state', listener);

    return () => {
      ipcRenderer.removeListener('desktop-controls:compact-state', listener);
    };
  }
});
