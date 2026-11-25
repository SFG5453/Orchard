# Release notes

The app shows bundled release notes in a Release notes window: **Settings > About >
View release notes**, or Spotlight (**Release notes**). The entries live in
[`app/release-notes/releases.json`](../app/release-notes/releases.json) and are
embedded in the Orchard executable by `meson/release_notes.qrc`.

For each release, add a new entry at the top of the JSON array with its `version`,
`date` (`YYYY-MM-DD`), `title`, and `sections`. Each section has a `title` and an
`items` array of short, user-facing changes. The `highlights` field is also
supported. An optional `callout` object with `title`, `text`, `action` (button
label), and `url` shows an announcement card above the sections. Match `version` to the Meson project version in `meson.build`; the window
marks that entry as installed. Keep published entries so people can read past
notes offline.

Orchard opens the window once after an update, on the newest entry. Releases newer
than the last one opened carry a "New" mark. The last opened version is stored as
`appearance/lastSeenRelease`. The version label comes from the running
executable's Meson version. Rebuild Orchard after changing the version or notes;
neither value is fetched from the update server.
