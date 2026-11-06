# Arch Linux package

Build and install the current checkout on x86_64 with:

```sh
cd packaging/arch
makepkg -si
```

The build initializes Git submodules and downloads npm packages and Rust dependencies. It also
requires network access for the adaptive mix runtime. The package installs the
application and its staged runtime under `/opt/orchard`; pacman manages updates.
The application menu lists the package as **Orchard (Arch Linux)** so it can
be distinguished from an existing Orchard launcher. Apple Music playback additionally requires a Widevine installation, such as
Google Chrome, Chromium with Widevine, or Firefox with DRM enabled. These browsers are listed as optional package dependencies.

The manual desktop release workflow builds this package in an Arch Linux
container on an Ubuntu runner. The resulting package archives are uploaded as
the `orchard-arch-x86_64` workflow artifact and published to R2 alongside
the AppImage under `downloads/<channel>/<version>/`.
