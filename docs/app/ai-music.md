---
title: AI-generated music
summary: Mark, skip, or remove songs that Orchard detects as AI-generated, or turn detection off.
group: Sound
icon: sparkles
keywords:
  - ai generated music
  - ai badge
  - slop
  - skip ai songs
  - remove ai songs
  - ai detection
platforms:
  - desktop
order: 100
---

# AI-generated music

Orchard can analyse upcoming songs in the background and flag songs that sound AI-generated. You choose what Orchard does with a flagged song. The default is **Mark**.

## Choose what happens to AI-generated songs

1. Open **Settings**.
2. Select **Playback**.
3. In the **Queue** group, on the "When a track sounds AI-generated" row, select **Off**, **Mark**, **Skip**, or **Remove**.

The four choices:

- **Off**: Orchard does not analyse songs.
- **Mark**: Orchard puts an AI badge on the song. This is the default.
- **Skip**: Orchard marks the song and skips it when it comes up.
- **Remove**: Orchard marks the song, skips it, and removes known AI songs from the queue.

## What the AI badge looks like

A small "AI" badge appears next to the song title in the player bar, the fullscreen player, the queue panel, and playlist song lists. Hover the badge to read "Orchard detected AI-generation artifacts in this track" with a confidence percentage. Its accessible name is "Likely AI-generated".

## How Orchard checks songs

Orchard downloads upcoming songs at low bitrate and analyses them in the background. The analysis catches fully generated songs. It does not catch AI voice covers.

## Stop skipping flagged songs

Select **Mark** or **Off** on the "When a track sounds AI-generated" row in the **Queue** group. **Mark** keeps the badge and plays every song. **Off** stops the analysis.
