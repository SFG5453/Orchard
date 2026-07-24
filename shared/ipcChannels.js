/**
 * Process-neutral names for Orchard's privileged Electron IPC surface.
 *
 * These strings are public compatibility contracts between the main process,
 * sandboxed preload, and renderer-facing APIs. Renaming one can silently break
 * packaged builds, so additions and removals are checked against the preload.
 */
export const IPC_CHANNELS = Object.freeze({
  APP: Object.freeze({
    CAPTURE_SCREENSHOT: 'app:capture-screenshot',
    DIAGNOSTICS: 'app:diagnostics',
    FINISH_WELCOME: 'app:finish-welcome',
    GRAPHICS_MODE: 'app:graphics-mode',
    RESTART: 'app:restart',
    SHOW_WELCOME: 'app:show-welcome'
  }),
  AUDIO_ANALYSIS: Object.freeze({
    ANALYZE: 'audio-analysis:analyze',
    AVAILABLE: 'audio-analysis:available',
    DEBUG: 'audio-analysis:debug',
    GET: 'audio-analysis:get',
    STORE: 'audio-analysis:store'
  }),
  CLIPBOARD: Object.freeze({
    WRITE_TEXT: 'clipboard:write-text'
  }),
  DESKTOP_CONTROLS: Object.freeze({
    COMPACT_STATE: 'desktop-controls:compact-state',
    SET_STATE: 'desktop-controls:set-state',
    TOGGLE_COMPACT: 'desktop-controls:toggle-compact'
  }),
  DISCORD: Object.freeze({
    CLEAR_PRESENCE: 'discord:clear-presence',
    SET_PRESENCE: 'discord:set-presence'
  }),
  GITHUB_AUTH: Object.freeze({
    CONNECT: 'github-auth:connect',
    CREATE_ISSUE: 'github-auth:create-issue',
    DISCONNECT: 'github-auth:disconnect',
    STATUS: 'github-auth:status'
  }),
  LASTFM: Object.freeze({
    COMPLETE: 'lastfm:complete',
    CONNECT: 'lastfm:connect',
    DISCONNECT: 'lastfm:disconnect',
    NOW_PLAYING: 'lastfm:now-playing',
    SCROBBLE: 'lastfm:scrobble',
    STATUS: 'lastfm:status'
  }),
  MIGRATION: Object.freeze({
    DOWNLOAD: 'migration:download',
    GET_STATE: 'migration:get-state',
    REFRESH: 'migration:refresh'
  }),
  SONG_LINKS: Object.freeze({
    RESOLVE: 'song-links:resolve'
  }),
  SYSTEM_MEDIA: Object.freeze({
    COMMAND: 'system-media:command',
    SET_STATE: 'system-media:set-state'
  }),
  UPDATES: Object.freeze({
    CHECK: 'updates:check',
    CHECK_CONTENT: 'updates:check-content',
    GET_STATE: 'updates:get-state',
    GET_USER_ARTIST_PACKS: 'updates:get-user-artist-packs',
    IMPORT_ARTIST_PACK: 'updates:import-artist-pack',
    INSTALL: 'updates:install',
    READ_ARTIST_PACK_ARCHIVE: 'updates:read-artist-pack-archive',
    STATE: 'updates:state'
  }),
  WINDOW: Object.freeze({
    CLOSE: 'window:close',
    MINIMIZE: 'window:minimize',
    SET_FULLSCREEN: 'window:set-fullscreen',
    TOGGLE_MAXIMIZE: 'window:toggle-maximize'
  })
});

/** Flat, immutable channel list used by contract tests and audit tooling. */
export const IPC_CHANNEL_VALUES = Object.freeze(
  Object.values(IPC_CHANNELS).flatMap((group) => Object.values(group))
);
