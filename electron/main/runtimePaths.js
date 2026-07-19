import path from 'node:path';

/**
 * Resolves files that live in the application archive and resources unpacked
 * beside it. Keeping these rules here prevents source-folder depth from
 * affecting development or packaged builds.
 */
export function resolveRuntimePaths({ app, isDev, resourcesPath = process.resourcesPath }) {
  const appRoot = app.getAppPath();

  return {
    appIconPath: path.join(appRoot, isDev ? 'build/icon.png' : 'dist/orchard-logo.png'),
    nativeModulePath: app.isPackaged
      ? path.join(resourcesPath, 'app.asar.unpacked', 'native/build/Release/orchard_audio_analysis.node')
      : path.join(appRoot, 'native/build/Release/orchard_audio_analysis.node'),
    preloadPath: path.join(appRoot, 'electron/preload/index.cjs'),
    rendererEntryPath: path.join(appRoot, 'dist/index.html')
  };
}
