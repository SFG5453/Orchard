// Owns tray, compact-window, and global-shortcut resources; callers must invoke `stop()` on quit.
import { IPC_CHANNELS } from '../../shared/ipcChannels.js';

const { DESKTOP_CONTROLS, SYSTEM_MEDIA } = IPC_CHANNELS;

export function setupDesktopControls({
  app,
  Menu,
  Tray,
  globalShortcut,
  ipcMain,
  nativeImage,
  getWindow,
  appIconPath
}) {
  let tray = null;
  let compact = false;
  let restoreBounds = null;
  let restoreMinimumSize = [760, 620];
  let playbackState = { track: null, isPlaying: false, canGoNext: false, canGoPrevious: false };

  function sendCommand(type, value) {
    const window = getWindow();
    if (!window || window.isDestroyed()) return;
    window.webContents.send(SYSTEM_MEDIA.COMMAND, { type, value });
  }

  function showWindow() {
    const window = getWindow();
    if (!window || window.isDestroyed()) return;
    if (!window.isVisible()) window.show();
    window.focus();
  }

  function setCompactMode(enabled) {
    const window = getWindow();
    if (!window || window.isDestroyed() || compact === enabled) return compact;

    compact = enabled;
    if (compact) {
      restoreBounds = window.getBounds();
      restoreMinimumSize = window.getMinimumSize();
      window.setMinimumSize(420, 150);
      window.setResizable(false);
      window.setAlwaysOnTop(true, 'floating');
      window.setSize(460, 172);
      window.center();
      window.show();
    } else {
      window.setAlwaysOnTop(false);
      window.setResizable(true);
      window.setMinimumSize(...restoreMinimumSize);
      if (restoreBounds) window.setBounds(restoreBounds);
    }

    window.webContents.send(DESKTOP_CONTROLS.COMPACT_STATE, compact);
    updateTrayMenu();
    return compact;
  }

  function trackLine() {
    const title = playbackState.track?.title || 'Nothing playing';
    const artist = playbackState.track?.artist ? ` - ${playbackState.track.artist}` : '';
    return `${title}${artist}`;
  }

  function updateTrayMenu() {
    if (!tray) return;

    tray.setToolTip(`Orchard${playbackState.track?.title ? ` - ${trackLine()}` : ''}`);
    tray.setContextMenu(Menu.buildFromTemplate([
      { label: trackLine(), enabled: false },
      { type: 'separator' },
      { label: 'Show Orchard', click: showWindow },
      { label: compact ? 'Leave Compact Player' : 'Compact Player', click: () => setCompactMode(!compact) },
      { type: 'separator' },
      { label: playbackState.isPlaying ? 'Pause' : 'Play', click: () => sendCommand('play-pause') },
      { label: 'Previous', enabled: playbackState.canGoPrevious, click: () => sendCommand('previous') },
      { label: 'Next', enabled: playbackState.canGoNext, click: () => sendCommand('next') },
      { type: 'separator' },
      { label: 'Quit Orchard', click: () => app.quit() }
    ]));
  }

  function registerShortcuts() {
    [
      ['MediaPlayPause', () => sendCommand('play-pause')],
      ['MediaNextTrack', () => sendCommand('next')],
      ['MediaPreviousTrack', () => sendCommand('previous')],
      ['CommandOrControl+Alt+Space', () => sendCommand('play-pause')],
      ['CommandOrControl+Alt+M', () => setCompactMode(!compact)]
    ].forEach(([accelerator, handler]) => {
      try {
        globalShortcut.register(accelerator, handler);
      } catch {
        // Another app may own the key. Orchard still works through tray/media controls.
      }
    });
  }

  function setState(state = {}) {
    playbackState = {
      track: state.track || null,
      isPlaying: Boolean(state.isPlaying),
      canGoNext: Boolean(state.canGoNext),
      canGoPrevious: Boolean(state.canGoPrevious)
    };
    updateTrayMenu();
  }

  try {
    const image = nativeImage.createFromPath(appIconPath).resize({ width: 22, height: 22 });
    tray = new Tray(image.isEmpty() ? appIconPath : image);
    tray.on('double-click', showWindow);
    updateTrayMenu();
  } catch (error) {
    console.warn(`Orchard tray unavailable: ${error.message}`);
  }

  registerShortcuts();

  ipcMain.handle(DESKTOP_CONTROLS.SET_STATE, (_event, state) => {
    setState(state);
  });

  ipcMain.handle(DESKTOP_CONTROLS.TOGGLE_COMPACT, () => setCompactMode(!compact));

  ipcMain.handle(DESKTOP_CONTROLS.COMPACT_STATE, () => compact);

  return {
    setState,
    stop() {
      globalShortcut.unregisterAll();
      tray?.destroy();
      tray = null;
    }
  };
}
