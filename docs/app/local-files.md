---
title: Local files and local playlists
summary: Add songs from your computer, make local playlists, set your own covers and lyrics, reorder by drag and drop, and see each file's bitrate.
group: Basics
icon: hard-drive
keywords:
  - local files
  - local playlist
  - import music
  - mp3
  - flac
  - folder
  - drag and drop
  - reorder playlist
  - playlist cover
  - gif cover
  - custom lyrics
  - lrc
  - bitrate
platforms:
  - desktop
order: 62
---

# Local files and local playlists

Orchard can play music files stored on your computer. They live next to your YouTube Music library, never inside it. Orchard only remembers where each file is, plus the tags, covers, and lyrics it found or that you chose. Your files are not copied, moved, or uploaded.

Local songs play without a network connection. Orchard never looks up lyrics or animated artwork for them online, and likes, song.link, and downloads do not apply to them.

## Add songs

1. Select **Your library** in the left sidebar.
2. Select **Add local files**. This is a separate button from **Refresh**.
3. Select **Add songs** to pick files, or **Add a folder** to add every song inside it, including subfolders.

The button spins while Orchard reads the files. A message tells you how many songs were added. Orchard reads these formats: MP3, FLAC, M4A, AAC, OGG, Opus, WAV, WMA, AIFF, ALAC, APE, WV, and MKA.

Added songs appear in the **Local songs** filter, newest first. Select **Local playlists** to see your local playlists.

## What Orchard reads from a file

Orchard reads the title, artist, album, duration, and embedded cover from each file's tags. If a file has no tags, Orchard uses its name, so `Daft Punk - Digital Love.mp3` becomes the artist Daft Punk and the title Digital Love. If there is no embedded cover, Orchard uses a `cover.jpg`, `folder.jpg`, or similar picture from the same folder.

Reading tags uses `ffprobe`, which comes with FFmpeg. Without it, songs are still added, but only with the name-based details and no duration until they play.

## Make a playlist

You can make a playlist on YouTube Music or on this computer.

1. Select **+ New Playlist** at the top of the playlist list in the left sidebar. The library page has the same **New playlist** button.
2. Choose **YouTube Music** or **On this computer**.
3. Type a name.
4. Select **Create**. For a local playlist you can also select **Create and add songs…** to pick files right away.

A local playlist shows a small drive icon in the sidebar. Right-click a local song, select **Add to playlist…**, and pick a local playlist to add it. A local song can only go in a local playlist, and a YouTube Music song can only go in a YouTube Music playlist.

## Edit a local playlist

Open the playlist. The buttons above the song list let you:

- **Add songs** or **Add folder** to add more music.
- **Cover** to choose your own picture.
- **Auto cover** to go back to the generated cover. It appears after you choose a cover.
- **Rename** the playlist.
- **Delete** the playlist. Your songs stay on your computer.

You can also drag audio files from your file manager onto the playlist page to add them.

## Reorder songs

Move the pointer over a song and drag the grip at its left edge to a new place. A line shows where the song will land. Dragging near the top or bottom edge scrolls the list. With a song focused, press Alt+Up or Alt+Down to move it one place.

Reordering works when the **#** column is selected, which is the playlist's own order. A sorted list cannot be rearranged.

## Covers

A local playlist with no cover of its own shows a collage of its first four different song covers. The collage updates when the songs change. Choosing your own cover replaces the collage.

Your own cover can be a PNG, JPEG, WebP, GIF, or MP4 file. A GIF or MP4 plays as a silent loop on the playlist page and in the fullscreen player. Orchard needs `ffmpeg` to turn a GIF into a loop and to take a still frame from an MP4. Loops follow the **Animated artwork** setting in [Appearance](appearance.md).

To set a cover for one song, right-click it and select **Set cover…**. Select **Use the file's own cover** to go back to the one inside the file.

## Lyrics

Right-click a local song and select **Set lyrics…** to choose a lyrics file: `.lrc` for synced lyrics, `.srt`, or `.txt` for plain lyrics. Select **Remove custom lyrics** to remove it. If the file's tags include lyrics, Orchard uses those when you have not chosen a file. See [Lyrics](lyrics.md) for how to show them.

## Bitrate

While a local song plays, the pill beside the artist name in the player bar shows its format and bitrate, for example `FLAC · 1411 kbps`. Orchard measures the bitrate from the file and from the audio decoder, so it appears even when **Show audio bitrate** is off.

## Remove songs

Right-click a local song and select **Remove from this playlist** to take it off one playlist, or **Remove from library** to forget it everywhere. Neither deletes the file. If a file is moved or deleted, playing it shows a message that it could not be found.
