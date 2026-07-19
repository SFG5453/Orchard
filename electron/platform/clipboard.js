// Restricts renderer clipboard access to plain-text writes.
import { IPC_CHANNELS } from '../../shared/ipcChannels.js';

export function registerClipboardHandlers({ clipboard, ipcMain }) {
  ipcMain.handle(IPC_CHANNELS.CLIPBOARD.WRITE_TEXT, (_event, value) => {
    clipboard.writeText(String(value || ''));
    return true;
  });
}
