// Electron installs its isolated APIs from electron/preload/index.cjs. Keeping
// this explicit module makes the renderer's platform selection symmetrical
// without weakening Electron's context-isolation boundary.
export function installElectronPlatform() {
  return globalThis.orchardApp || null;
}

