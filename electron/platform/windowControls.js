// Resolves every window command from the sending WebContents and blocks untrusted window opens.
import { IPC_CHANNELS } from '../../shared/ipcChannels.js';

const { WINDOW } = IPC_CHANNELS;

export function focusedWindowFromEvent(BrowserWindow, event) {
  return BrowserWindow.fromWebContents(event.sender);
}

const fallbackMaximizeState = new WeakMap();

function toggleBoundsMaximize(window, screen) {
  const fallback = fallbackMaximizeState.get(window);
  if (fallback?.maximized) {
    window.setBounds(fallback.bounds);
    fallbackMaximizeState.delete(window);
    return false;
  }

  fallbackMaximizeState.set(window, { maximized: true, bounds: window.getBounds() });
  const display = screen.getDisplayMatching(window.getBounds());
  window.setBounds(display.workArea);
  return true;
}

export function registerWindowControls({ BrowserWindow, ipcMain, screen }) {
  ipcMain.handle(WINDOW.MINIMIZE, (event) => {
    const window = focusedWindowFromEvent(BrowserWindow, event);
    if (!window) return false;

    window.minimize();
    return window.isMinimized();
  });

  ipcMain.handle(WINDOW.TOGGLE_MAXIMIZE, (event) => {
    const window = focusedWindowFromEvent(BrowserWindow, event);
    if (!window) return false;

    if (window.isMaximized()) {
      window.unmaximize();
      fallbackMaximizeState.delete(window);
      return false;
    }

    const before = window.getBounds();
    window.maximize();
    const after = window.getBounds();
    if (window.isMaximized() || before.width !== after.width || before.height !== after.height) {
      fallbackMaximizeState.delete(window);
      return true;
    }

    return process.platform === 'linux' && screen
      ? toggleBoundsMaximize(window, screen)
      : false;
  });

  ipcMain.handle(WINDOW.SET_FULLSCREEN, (event, fullscreen) => {
    const window = focusedWindowFromEvent(BrowserWindow, event);
    if (!window) return false;

    window.setFullScreen(Boolean(fullscreen));
    return window.isFullScreen();
  });

  ipcMain.handle(WINDOW.CLOSE, (event) => {
    focusedWindowFromEvent(BrowserWindow, event)?.close();
  });
}

export function registerDevToolsShortcut(window) {
  window.webContents.on('before-input-event', (event, input) => {
    const key = input.key?.toLowerCase();
    const wantsDevTools = input.key === 'F12' ||
      (input.control && input.shift && key === 'i') ||
      (input.control && input.alt && key === 'i');
    if (!wantsDevTools) return;

    event.preventDefault();
    window.webContents.toggleDevTools();
  });
}

export function configureWindowOpenHandler(window, shell) {
  window.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url);
    return { action: 'deny' };
  });
}
