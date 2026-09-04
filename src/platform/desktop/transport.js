import { isTauriRuntime } from './runtime.js';

export async function createDesktopTransport(port) {
  if (isTauriRuntime()) {
    const { createTauriTransport } = await import('./tauriTransport.js');
    return createTauriTransport();
  }
  const { createElectronTransport } = await import('./electronTransport.js');
  return createElectronTransport(port);
}
