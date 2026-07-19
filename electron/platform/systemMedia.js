// Owns platform media-session integration and releases D-Bus/listener resources from `stop()`.
import { createRequire } from 'node:module';
import { IPC_CHANNELS } from '../../shared/ipcChannels.js';

const require = createRequire(import.meta.url);
const { SYSTEM_MEDIA } = IPC_CHANNELS;

const OBJECT_PATH = '/org/mpris/MediaPlayer2';
const SERVICE_NAME = 'org.mpris.MediaPlayer2.Orchard';
const NO_TRACK_PATH = '/org/mpris/MediaPlayer2/TrackList/NoTrack';

const LOOP_TO_REPEAT = {
  None: 'off',
  Track: 'one',
  Playlist: 'queue'
};

const REPEAT_TO_LOOP = {
  off: 'None',
  one: 'Track',
  queue: 'Playlist'
};

function toMicroseconds(value) {
  const number = Number(value);
  if (!Number.isFinite(number) || number <= 0) return 0n;
  return BigInt(Math.round(number * 1_000_000));
}

function trackObjectPath(track = {}) {
  if (!track.id) return NO_TRACK_PATH;
  const safeId = String(track.id).replace(/[^A-Za-z0-9_]/g, '_') || 'unknown';
  return `/dev/sfg/orchard/track/${safeId}`;
}

function metadataFromState(state, Variant) {
  const track = state.track || {};
  const metadata = {
    'mpris:trackid': new Variant('o', trackObjectPath(track)),
    'xesam:title': new Variant('s', track.title || 'Orchard'),
    'xesam:album': new Variant('s', track.album || ''),
    'xesam:artist': new Variant('as', (track.artists?.length ? track.artists : [track.artist]).filter(Boolean))
  };

  if (track.thumbnail) metadata['mpris:artUrl'] = new Variant('s', track.thumbnail);
  if (state.durationSeconds > 0) metadata['mpris:length'] = new Variant('x', toMicroseconds(state.durationSeconds));
  if (track.id) metadata['xesam:url'] = new Variant('s', `https://music.youtube.com/watch?v=${track.id}`);

  return metadata;
}

function configureMprisInterfaces(dbus) {
  const {
    Interface,
    ACCESS_READ,
    ACCESS_READWRITE
  } = dbus.interface;

  class MediaPlayerInterface extends Interface {
    constructor(emitCommand) {
      super('org.mpris.MediaPlayer2');
      this.emitCommand = emitCommand;
    }

    Raise() {
      this.emitCommand({ type: 'raise' });
    }

    Quit() {
      this.emitCommand({ type: 'quit' });
    }

    get CanQuit() { return true; }
    get Fullscreen() { return false; }
    set Fullscreen(_value) {}
    get CanSetFullscreen() { return false; }
    get CanRaise() { return true; }
    get HasTrackList() { return false; }
    get Identity() { return 'Orchard'; }
    get DesktopEntry() { return 'dev.sfg.orchard'; }
    get SupportedUriSchemes() { return ['http', 'https']; }
    get SupportedMimeTypes() { return ['audio/mpeg', 'audio/mp4', 'audio/webm', 'video/mp4', 'video/webm']; }
  }

  MediaPlayerInterface.configureMembers({
    methods: {
      Raise: {},
      Quit: {}
    },
    properties: {
      CanQuit: { signature: 'b', access: ACCESS_READ },
      Fullscreen: { signature: 'b', access: ACCESS_READWRITE },
      CanSetFullscreen: { signature: 'b', access: ACCESS_READ },
      CanRaise: { signature: 'b', access: ACCESS_READ },
      HasTrackList: { signature: 'b', access: ACCESS_READ },
      Identity: { signature: 's', access: ACCESS_READ },
      DesktopEntry: { signature: 's', access: ACCESS_READ },
      SupportedUriSchemes: { signature: 'as', access: ACCESS_READ },
      SupportedMimeTypes: { signature: 'as', access: ACCESS_READ }
    }
  });

  class PlayerInterface extends Interface {
    constructor(emitCommand) {
      super('org.mpris.MediaPlayer2.Player');
      this.emitCommand = emitCommand;
      this.state = {};
      this.Variant = dbus.Variant;
    }

    update(nextState = {}) {
      const previous = this.state;
      this.state = { ...previous, ...nextState };

      const changed = {};
      for (const property of [
        'PlaybackStatus',
        'LoopStatus',
        'Shuffle',
        'Metadata',
        'Volume',
        'CanGoNext',
        'CanGoPrevious',
        'CanPlay',
        'CanPause',
        'CanSeek'
      ]) {
        if (this[property] !== undefined) changed[property] = this[property];
      }

      Interface.emitPropertiesChanged(this, changed);
    }

    Next() { this.emitCommand({ type: 'next' }); }
    Previous() { this.emitCommand({ type: 'previous' }); }
    Pause() { this.emitCommand({ type: 'pause' }); }
    PlayPause() { this.emitCommand({ type: 'play-pause' }); }
    Stop() { this.emitCommand({ type: 'stop' }); }
    Play() { this.emitCommand({ type: 'play' }); }
    Seek(offset) { this.emitCommand({ type: 'seek-relative', value: Number(offset) / 1_000_000 }); }
    SetPosition(_trackId, position) { this.emitCommand({ type: 'seek', value: Number(position) / 1_000_000 }); }
    OpenUri(_uri) {}
    Seeked(position) { return position; }

    get PlaybackStatus() {
      if (!this.state.track) return 'Stopped';
      return this.state.isPlaying ? 'Playing' : 'Paused';
    }

    get LoopStatus() { return REPEAT_TO_LOOP[this.state.repeatMode] || 'None'; }
    set LoopStatus(value) {
      const repeatMode = LOOP_TO_REPEAT[value] || 'off';
      this.state.repeatMode = repeatMode;
      this.emitCommand({ type: 'set-repeat-mode', value: repeatMode });
      Interface.emitPropertiesChanged(this, { LoopStatus: this.LoopStatus });
    }

    get Rate() { return 1; }
    set Rate(_value) {}
    get Shuffle() { return Boolean(this.state.shuffleEnabled); }
    set Shuffle(value) {
      this.state.shuffleEnabled = Boolean(value);
      this.emitCommand({ type: 'set-shuffle', value: this.state.shuffleEnabled });
      Interface.emitPropertiesChanged(this, { Shuffle: this.Shuffle });
    }

    get Metadata() { return metadataFromState(this.state, this.Variant); }
    get Volume() { return Number.isFinite(this.state.volume) ? this.state.volume : 1; }
    set Volume(value) {
      this.state.volume = Math.max(0, Math.min(1, Number(value) || 0));
      this.emitCommand({ type: 'set-volume', value: this.state.volume });
      Interface.emitPropertiesChanged(this, { Volume: this.Volume });
    }

    get Position() { return toMicroseconds(this.state.currentTime); }
    get MinimumRate() { return 1; }
    get MaximumRate() { return 1; }
    get CanGoNext() { return Boolean(this.state.canGoNext); }
    get CanGoPrevious() { return Boolean(this.state.canGoPrevious); }
    get CanPlay() { return Boolean(this.state.track); }
    get CanPause() { return Boolean(this.state.track); }
    get CanSeek() { return Boolean(this.state.canSeek); }
    get CanControl() { return true; }
  }

  PlayerInterface.configureMembers({
    methods: {
      Next: {},
      Previous: {},
      Pause: {},
      PlayPause: {},
      Stop: {},
      Play: {},
      Seek: { inSignature: 'x' },
      SetPosition: { inSignature: 'ox' },
      OpenUri: { inSignature: 's' }
    },
    signals: {
      Seeked: { signature: 'x' }
    },
    properties: {
      PlaybackStatus: { signature: 's', access: ACCESS_READ },
      LoopStatus: { signature: 's', access: ACCESS_READWRITE },
      Rate: { signature: 'd', access: ACCESS_READWRITE },
      Shuffle: { signature: 'b', access: ACCESS_READWRITE },
      Metadata: { signature: 'a{sv}', access: ACCESS_READ },
      Volume: { signature: 'd', access: ACCESS_READWRITE },
      Position: { signature: 'x', access: ACCESS_READ },
      MinimumRate: { signature: 'd', access: ACCESS_READ },
      MaximumRate: { signature: 'd', access: ACCESS_READ },
      CanGoNext: { signature: 'b', access: ACCESS_READ },
      CanGoPrevious: { signature: 'b', access: ACCESS_READ },
      CanPlay: { signature: 'b', access: ACCESS_READ },
      CanPause: { signature: 'b', access: ACCESS_READ },
      CanSeek: { signature: 'b', access: ACCESS_READ },
      CanControl: { signature: 'b', access: ACCESS_READ }
    }
  });

  return { MediaPlayerInterface, PlayerInterface };
}

export function createSystemMediaService({ emitCommand }) {
  let bus = null;
  let player = null;
  let startPromise = null;

  async function start() {
    if (process.platform !== 'linux') return false;
    if (startPromise) return startPromise;

    startPromise = (async () => {
      const dbus = require('@particle/dbus-next');
      const { MediaPlayerInterface, PlayerInterface } = configureMprisInterfaces(dbus);
      bus = dbus.sessionBus();
      player = new PlayerInterface(emitCommand);
      await bus.requestName(SERVICE_NAME);
      bus.export(OBJECT_PATH, new MediaPlayerInterface(emitCommand));
      bus.export(OBJECT_PATH, player);
      return true;
    })().catch((error) => {
      console.warn(`System media integration disabled: ${error.message}`);
      bus = null;
      player = null;
      return false;
    });

    return startPromise;
  }

  return {
    async publish(state) {
      if (!await start()) return false;
      player?.update(state);
      return Boolean(player);
    },
    stop() {
      try {
        if (bus) bus.unexport(OBJECT_PATH);
        bus?.disconnect();
      } catch {
        // D-Bus teardown can race with session shutdown.
      }
      bus = null;
      player = null;
      startPromise = null;
    }
  };
}

export function setupSystemMediaHandlers({ ipcMain, app, getWindow }) {
  const systemMedia = createSystemMediaService({
    emitCommand: (command) => {
      const window = getWindow();

      if (command.type === 'raise') {
        window?.show();
        window?.focus();
        return;
      }

      if (command.type === 'quit') {
        app.quit();
        return;
      }

      window?.webContents.send(SYSTEM_MEDIA.COMMAND, command);
    }
  });

  ipcMain.handle(SYSTEM_MEDIA.SET_STATE, async (_event, state) => {
    return systemMedia.publish(state);
  });

  return systemMedia;
}
