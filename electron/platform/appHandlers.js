// Registers application-level IPC that is safe to expose through the audited preload surface.
import { IPC_CHANNELS } from '../../shared/ipcChannels.js';

const { APP, DISCORD, SONG_LINKS } = IPC_CHANNELS;

export function registerAppHandlers({
  app,
  clearDiscordPresence,
  graphicsMode,
  ipcMain,
  isDev,
  resolveDiscordSongLink,
  resolveDiscordSongLinkDetails,
  setDiscordPresence,
  showMainWindow,
  showWelcomeWindow
}) {
  ipcMain.handle(DISCORD.SET_PRESENCE, async (_event, presence) => {
    await setDiscordPresence(presence);
  });
  ipcMain.handle(DISCORD.CLEAR_PRESENCE, async () => {
    await clearDiscordPresence();
  });
  ipcMain.handle(SONG_LINKS.RESOLVE, async (_event, presence) => {
    return presence?.includeDetails ? resolveDiscordSongLinkDetails(presence) : resolveDiscordSongLink(presence);
  });
  ipcMain.handle(APP.DIAGNOSTICS, () => ({
    version: app.getVersion(),
    platform: process.platform,
    arch: process.arch,
    chrome: process.versions.chrome,
    electron: process.versions.electron,
    node: process.versions.node,
    dev: isDev
  }));
  ipcMain.handle(APP.GRAPHICS_MODE, (_event, value) => {
    return value === undefined ? graphicsMode.state() : graphicsMode.setMode(value);
  });
  ipcMain.handle(APP.RESTART, () => {
    graphicsMode.restart();
  });
  ipcMain.handle(APP.FINISH_WELCOME, () => {
    showMainWindow();
  });
  ipcMain.handle(APP.SHOW_WELCOME, () => {
    void showWelcomeWindow();
  });
}
