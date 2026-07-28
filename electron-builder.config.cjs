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
    'package.json'
  ],
  asarUnpack: [
    'native/build/Release/*.node'
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
  win: {
    target: ['nsis'],
    icon: 'build/icon.ico'
  }
};
