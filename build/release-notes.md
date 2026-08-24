## Orchard 5.0.0-beta.2 "Aerie Hymned"

This is a transition release. Installing it replaces the old desktop updater with the new Orchard package service, which is what lets Orchard update itself to 5.0.0-beta.3 and everything after it. Beta 3 is fetched from the GitHub beta releases.

### New & improved
- **Orchard package service**: Replaced the desktop updater with the new package service, which downloads, verifies, and installs updates directly instead of handing you off to an external installer.
- **Retiring the old install**: Once Orchard is running from the package service, it offers a one-time prompt to remove the older install it replaced, using your platform's own uninstaller — the Windows uninstaller, your package manager on Linux, or Finder on macOS.

### Fixed
- **Beta endpoint**: Prerelease builds now resolve to the GitHub beta endpoint instead of the stable manifest, so this build reliably finds 5.0.0-beta.3.
