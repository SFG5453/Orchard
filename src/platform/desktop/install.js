import { isTauriRuntime } from './runtime.js';

export async function installDesktopPlatform() {
  if (isTauriRuntime()) {
    const { installTauriPlatform } = await import('./tauri.js');
    await installTauriPlatform();
    return 'tauri';
  }
  const { installElectronPlatform } = await import('./electron.js');
  installElectronPlatform();
  return 'electron';
}

