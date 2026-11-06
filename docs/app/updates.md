---
title: Updates
summary: How Orchard updates itself, how to install an update, switch to canary builds, roll back, and repair an install.
group: Help
icon: refresh-cw
keywords:
  - update
  - updates
  - new version
  - restart to update
  - canary
  - release notes
  - what's new
  - changelog
  - beta
  - rollback
  - repair
  - reinstall
  - uninstall
platforms:
  - desktop
order: 145
---

# Updates

When installed with the Orchard installer, Orchard downloads updates in the background and installs them the next time it starts. Packaged installs notify you about new releases so you can update them yourself.

After an update, Orchard opens the **Release notes** window once to show what changed. Releases you have not opened yet are marked **New**.

To read the notes again, open **Settings** > **About** > **Release Notes** and select **View release notes**. You can also press Ctrl+K (or /) and choose **Release notes**. The window lists every release on the left, newest first. The one you run is marked **Installed**. Select a release to read its changes, or use **Newer** and **Older** to move between releases. Type in the search box to find releases by version, title, or wording, for example "lyrics". Select **Copy notes** to copy a release as Markdown.

The notes are included with Orchard, so you can read them offline. Settings > About still shows the version you are running in the Release Notes row.

## Install a downloaded update

When an update is ready, **Settings** > **General** > **Updates** says "Update ready. Restart Orchard to install." Select **Restart**. Orchard closes, switches to the new version, and opens again.

If you do nothing, the update installs the next time you start Orchard.

## Check for updates now

Open **Settings** > **General** > **Updates** and select **Check for updates**. The row shows the download size while it downloads. Select **Cancel** to stop; the next check resumes where it stopped.

Packaged installs, including the Arch package and AppImage, check for a newer release at startup and show it under **Updates**. Install a new package or AppImage yourself; Orchard does not install it automatically. The check follows the stable or canary channel of your installed version. Builds you compiled yourself do not check for updates. Other package maintainers can enable read-only checks by setting `ORCHARD_PACKAGE_MANAGED=1` in their launcher.

## How big updates are

Orchard downloads only the files that changed. A typical update is 5 to 40 MB. Shared parts such as the Qt runtime, FFmpeg and the AI models download again only when they change.

## Turn off automatic downloads

Turn off **Download updates automatically** in **Settings** > **General** > **Updates**. Orchard then downloads only when you select **Check for updates**.

## Get canary builds

Turn on **Canary builds** in **Settings** > **General** > **Updates**. Orchard downloads the latest canary build right away. Turn it off to go back to stable builds; Orchard installs the current stable version even if it is older.

## Orchard went back to an older version

If a new version crashes twice right after it starts, Orchard returns to the previous version and skips the broken one. A later update replaces it.

To go back yourself, run the Orchard launcher with `--rollback`, for example `Orchard.exe --rollback` on Windows.

## Repair a broken install

If Orchard does not start or reports missing files, run the Orchard launcher with `--repair`. It checks every installed file and downloads only the damaged or missing ones. Orchard also repairs itself automatically when the current version is incomplete.

## Uninstall Orchard

On Windows, open **Settings** > **Apps** and uninstall **Orchard**. On Linux, run `~/.local/share/orchard/orchard --uninstall`. Your library, settings and downloads are kept.
