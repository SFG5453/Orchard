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
    // Opened by ONNX Runtime's native code, which cannot read inside an asar,
    // so the packaged copy lives in app.asar.unpacked like the addon above.
    beatModelPath: app.isPackaged
      ? path.join(resourcesPath, 'app.asar.unpacked', 'models/beat-this/beat_this_int8.onnx')
      : path.join(appRoot, 'models/beat-this/beat_this_int8.onnx'),
    vocalModelPath: app.isPackaged
      ? path.join(resourcesPath, 'app.asar.unpacked', 'models/vocal-separation/vocals_umxhq_int8.onnx')
      : path.join(appRoot, 'models/vocal-separation/vocals_umxhq_int8.onnx'),
    preloadPath: path.join(appRoot, 'electron/preload/index.cjs'),
    rendererEntryPath: path.join(appRoot, 'dist/index.html')
  };
}
