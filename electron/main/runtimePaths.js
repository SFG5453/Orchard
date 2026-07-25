import path from 'node:path';

/**
 * Resolves files that live in the application archive and resources unpacked
 * beside it. Keeping these rules here prevents source-folder depth from
 * affecting development or packaged builds.
 */
export function resolveRuntimePaths({
  app,
  isDev,
  modelPathOverride = process.env.ORCHARD_SMART_CROSSFADE_MODELS,
  resourcesPath = process.resourcesPath,
  userDataPath = app.getPath('userData')
}) {
  const appRoot = app.getAppPath();
  const smartCrossfadeModelPaths = [
    modelPathOverride,
    path.join(userDataPath, 'smart-crossfade-models'),
    isDev
      ? path.join(appRoot, 'models/smart-crossfade')
      : path.join(resourcesPath, 'smart-crossfade-models')
  ].filter((value, index, values) => value && values.indexOf(value) === index);

  return {
    appIconPath: path.join(appRoot, isDev ? 'build/icon.png' : 'dist/orchard-logo.png'),
    nativeModulePath: app.isPackaged
      ? path.join(resourcesPath, 'app.asar.unpacked', 'native/build/Release/orchard_audio_analysis.node')
      : path.join(appRoot, 'native/build/Release/orchard_audio_analysis.node'),
    preloadPath: path.join(appRoot, 'electron/preload/index.cjs'),
    rendererEntryPath: path.join(appRoot, 'dist/index.html'),
    smartCrossfadeModelPaths
  };
}
