import path from 'node:path';

/**
 * Resolves files that live in the application archive and resources unpacked
 * beside it. Keeping these rules here prevents source-folder depth from
 * affecting development or packaged builds.
 */
export function resolveRuntimePaths({ app, isDev }) {
  const appRoot = app.getAppPath();
  // Derived from the archive's own location rather than process.resourcesPath:
  // the system-Electron package (scripts/package-system-electron.mjs) installs
  // app.asar and app.asar.unpacked side by side under its own prefix while
  // running on a shared Electron binary, so resourcesPath points at that
  // runtime's directory instead of the payload. In development, and in
  // electron-builder output where the two happen to coincide, the replace is a
  // no-op. Matches the idiom in beatModelHost.js / vocalMaskHost.js.
  const unpackedRoot = appRoot.replace(`app.asar${path.sep}`, `app.asar.unpacked${path.sep}`)
    .replace(/app\.asar$/, 'app.asar.unpacked');

  return {
    appIconPath: path.join(appRoot, isDev ? 'build/icon.png' : 'dist/orchard-logo.png'),
    nativeModulePath: path.join(unpackedRoot, 'native/build/Release/orchard_audio_analysis.node'),
    // Opened by ONNX Runtime's native code, which cannot read inside an asar,
    // so the packaged copy lives in app.asar.unpacked like the addon above.
    beatModelPath: path.join(unpackedRoot, 'models/beat-this/beat_this_int8.onnx'),
    vocalModelPath: path.join(unpackedRoot, 'models/vocal-separation/vocals_umxhq_int8.onnx'),
    preloadPath: path.join(appRoot, 'electron/preload/index.cjs'),
    rendererEntryPath: path.join(appRoot, 'dist/index.html')
  };
}
