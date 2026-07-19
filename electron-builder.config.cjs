const updateUrl = process.env.ORCHARD_UPDATE_URL || 'https://downloads.sfg545.dev/orchard/';

module.exports = {
  appId: 'dev.sfg.orchard',
  productName: 'Orchard',
  asar: true,
  publish: {
    provider: 'generic',
    url: updateUrl
  },
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
    maintainer: 'SFG545 <khyrenjoseph253@gmail.com>',
    vendor: 'SFG545',
    syncDesktopName: true
  },
  deb: {
    compression: 'xz',
    fpm: ['--deb-compression-level', '9']
  },
  rpm: {
    compression: 'xzmt',
    fpm: ['--rpm-compression-level', '9']
  },
  win: {
    target: ['nsis'],
    icon: 'build/icon.ico'
  }
};
