const updateUrl = process.env.ORCHARD_UPDATE_URL || 'https://downloads.sfg545.dev/orchard/';

module.exports = {
  appId: 'dev.sfg.orchard',
  productName: 'Orchard',
  asar: true,
  publish: {
    provider: 'generic',
    url: updateUrl
  },
  // Beta versions carry a semver prerelease suffix (e.g. 4.0.0-beta.1). electron-builder
  // otherwise infers an update "channel" from that suffix and names manifests beta.yml /
  // beta-mac.yml / beta-linux.yml instead of latest*.yml. Beta vs. stable here is decided
  // by publish destination (GitHub Release vs. R2), not by manifest channel, so keep the
  // filenames constant across both.
  detectUpdateChannel: false,
  releaseInfo: {
    releaseNotesFile: 'build/release-notes.md'
  },
  directories: {
    output: 'release'
  },
  files: [
    'dist/**/*',
    // Main, preload, and domain modules are packaged as auditable source files.
    'electron/**/*',
    'shared/**/*',
    'native/build/Release/*.node',
    // Only the quantized model ships. The glob is deliberately exact: a dev
    // machine may also hold the 83 MB fp32 model the int8 was derived from,
    // and a wildcard would quietly quadruple the installer.
    'models/beat-this/beat_this_int8.onnx',
    'models/vocal-separation/vocals_umxhq_int8.onnx',
    'package.json',
    'LICENSE'
  ],
  asarUnpack: [
    'native/build/Release/*.node',
    // ONNX Runtime dlopens its own shared libraries next to its binding, and
    // the model file is opened by native code; neither can read out of an asar.
    '**/node_modules/onnxruntime-node/**',
    'models/beat-this/beat_this_int8.onnx',
    'models/vocal-separation/vocals_umxhq_int8.onnx',
    // The beat model runs in a utility process, and utilityProcess.fork is
    // given a real path on disk. These are ES modules, whose loader does not
    // go through the same asar-aware layer CommonJS require does, so the fork
    // entry and everything it statically imports are unpacked rather than
    // relying on that working from inside the archive.
    'electron/audio/beatModelProcess.js',
    'electron/audio/beatThisTracker.js',
    'electron/audio/vocalMaskProcess.js',
    'electron/audio/vocalMaskTracker.js',
    'electron/audio/modelProcessHost.js'
  ],
  electronFuses: {
    runAsNode: false,
    enableCookieEncryption: true,
    enableNodeOptionsEnvironmentVariable: false,
    enableNodeCliInspectArguments: false,
    enableEmbeddedAsarIntegrityValidation: true,
    onlyLoadAppFromAsar: true
  },
  mac: {
    target: ['zip'],
    category: 'public.app-category.music',
    icon: 'build/icon.png',
    artifactName: '${productName}-${version}-mac-${arch}.${ext}'
  },
  linux: {
    target: ['AppImage', 'deb', 'rpm'],
    category: 'Audio',
    icon: 'build/icon.png',
    maintainer: 'SFG545 <sfg@sfg545.dev>',
    vendor: 'SFG545',
    syncDesktopName: true
  },
  deb: {
    compression: 'zst',
    fpm: ['--deb-compression-level', '7']
  },
  rpm: {
    compression: 'xzmt',
    fpm: ['--rpm-compression-level', '6']
  },
  flatpak: {
    runtime: 'org.freedesktop.Platform',
    runtimeVersion: '25.08',
    sdk: 'org.freedesktop.Sdk',
    base: 'org.electronjs.Electron2.BaseApp',
    baseVersion: '25.08',
    branch: 'stable',
    useWaylandFlags: true,
    finishArgs: [
      '--share=ipc',
      '--share=network',
      '--socket=x11',
      '--socket=wayland',
      '--socket=pulseaudio',
      '--device=dri',
      '--talk-name=org.freedesktop.Notifications',
      '--talk-name=org.freedesktop.secrets',
      '--talk-name=org.kde.StatusNotifierWatcher',
      '--talk-name=com.canonical.AppMenu.Registrar',
      '--own-name=org.mpris.MediaPlayer2.Orchard',
      '--filesystem=xdg-run/discord-ipc-0',
      '--env=ORCHARD_DISTRIBUTION_PACKAGE=Flatpak'
    ]
  },
  win: {
    target: ['nsis'],
    icon: 'build/icon.ico',
    // Default NSIS naming ("Orchard Setup 4.0.0.exe") contains spaces. GitHub Releases
    // silently rewrites spaces to dots on upload, which desyncs the filename from what's
    // recorded in latest.yml and breaks electron-updater downloads (404). R2 never
    // rewrites uploaded filenames, so this only surfaced for GitHub-published beta builds.
    artifactName: '${productName}-Setup-${version}.${ext}'
  }
};
