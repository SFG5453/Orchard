// Captures only the BrowserWindow that originated the IPC request.
import { IPC_CHANNELS } from '../../shared/ipcChannels.js';

export function registerScreenshotCapture({ BrowserWindow, ipcMain }) {
  ipcMain.handle(IPC_CHANNELS.APP.CAPTURE_SCREENSHOT, async (event) => {
    const window = BrowserWindow.fromWebContents(event.sender);
    if (!window || window.isDestroyed()) throw new Error('Orchard window is unavailable.');
    const image = await window.capturePage();
    return image.toDataURL();
  });
}
