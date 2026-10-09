# Release notes

The app shows bundled release notes in **Settings > About**. The entries live in
[`app/release-notes/releases.json`](../app/release-notes/releases.json) and are
embedded in the Orchard executable by `meson/release_notes.qrc`.

For each release, add a new entry at the top of the JSON array with its `version`,
`date` (`YYYY-MM-DD`), `title`, and `sections`. Each section has a `title` and an
`items` array of short, user-facing changes. The `highlights` field is also
supported. Match `version` to the Meson project version in `meson.build`; About
marks that entry as the current version. Keep published entries so people can
read past notes offline.

The version label in About comes from the running executable's Meson version.
Rebuild Orchard after changing the version or notes; neither value is fetched
from the update server.
