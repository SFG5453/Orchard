// Security boundary between sandboxed renderer code and privileged Electron APIs.
// Keep this file dependency-free and expose only narrow, structured operations.
// Channel literals mirror shared/ipcChannels.js because a sandboxed preload
// cannot load arbitrary local modules; test/ipcChannels.test.js prevents drift.
const { contextBridge, ipcRenderer } = require('electron');

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
  close: () => ipcRenderer.invoke('window:close')
});

contextBridge.exposeInMainWorld('orchardApp', {
  captureScreenshot: () => ipcRenderer.invoke('app:capture-screenshot'),
  diagnostics: () => ipcRenderer.invoke('app:diagnostics'),
  finishWelcome: () => ipcRenderer.invoke('app:finish-welcome'),
  showWelcome: () => ipcRenderer.invoke('app:show-welcome')
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

contextBridge.exposeInMainWorld('orchardSongLinks', {
  resolve: (presence) => ipcRenderer.invoke('song-links:resolve', presence),
  resolveDetails: (presence) => ipcRenderer.invoke('song-links:resolve', { ...presence, includeDetails: true })
});

contextBridge.exposeInMainWorld('orchardClipboard', {
  writeText: (value) => ipcRenderer.invoke('clipboard:write-text', value)
});

contextBridge.exposeInMainWorld('orchardCrypto', {
  randomInt: (maxExclusive) => randomInt(Number(maxExclusive))
});

// The renderer never receives require() or the native addon. PCM crosses this
// structured-clone boundary and is shape/size checked again in the main process.
contextBridge.exposeInMainWorld('orchardAudioAnalysis', {
  available: () => ipcRenderer.invoke('audio-analysis:available'),
  get: (trackId) => ipcRenderer.invoke('audio-analysis:get', trackId),
  debug: (event, details = {}) => ipcRenderer.invoke('audio-analysis:debug', { event, details }),
  analyze: (trackId, samples, sampleRate, duration) => ipcRenderer.invoke('audio-analysis:analyze', {
    trackId,
    samples,
    sampleRate,
    duration
  })
});

contextBridge.exposeInMainWorld('orchardMigration', {
  getState: () => ipcRenderer.invoke('migration:get-state'),
  refresh: () => ipcRenderer.invoke('migration:refresh'),
  download: () => ipcRenderer.invoke('migration:download')
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
  importArtistPack: () => ipcRenderer.invoke('updates:import-artist-pack'),
  getUserArtistPacks: () => ipcRenderer.invoke('updates:get-user-artist-packs'),
  readArtistPackArchive: (archiveUrl) => ipcRenderer.invoke('updates:read-artist-pack-archive', archiveUrl),
  install: () => ipcRenderer.invoke('updates:install'),
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
  nativeSystemMedia: process.platform === 'linux',
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
