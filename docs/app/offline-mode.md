---
title: Offline mode and downloads
summary: Download songs, playlists, and albums to listen without a connection, choose the download quality, and see how Orchard handles a lost internet connection.
group: Basics
icon: download
keywords:
  - offline
  - download
  - downloads
  - no internet
  - no connection
  - retry
  - download quality
  - animated artwork
  - clear downloads
  - saved songs
platforms:
  - desktop
order: 63
---

# Offline mode and downloads

Orchard can save songs on your computer so you can keep listening when the internet is gone. Downloaded songs play from your disk, so they also use no bandwidth when you are online.

## Download songs

- **A song:** open its menu (right-click it or select the three dots) and select **Download**. The same menu shows **Cancel download** while it is in progress and **Remove download** once it is saved.
- **A playlist or album:** open its page and select the download button next to **Shuffle**. The button is available once every song on the page has loaded. Selecting it saves every song and remembers the playlist or album, so it appears in your offline library.
- **Remove them again:** on the playlist or album page, select the same button and choose **Remove downloads**. You can also use **Remove download** on a single song.

A small arrow icon next to a song's title in a playlist, album, or library list shows that it is downloaded.

Local files do not need downloading, because they already live on your computer. Downloading needs an internet connection and a signed-in YouTube Music account.

## Download settings

Open **Settings** and select **Downloads**.

- **Download quality:** **Saver** saves the smallest files, **Normal** saves up to 128 kbps audio, and **High** saves the best available audio. The setting applies to future downloads. Songs already saved keep their quality.
- **Download animated artwork:** when a song has a looping cover video, Orchard saves it too, so the loop plays offline. These videos are large, often several megabytes for each album, so this uses a lot more bandwidth and storage than audio alone. Orchard asks you to confirm before turning it on. It also needs **Animated artwork** to be on in [Appearance](appearance.md).
- **Clear all downloads:** deletes every downloaded song, cover, and saved playlist and stops downloads in progress. The page also shows how many songs are saved and how much space they use.

## When the connection drops

Orchard checks the connection in the background. When a check fails, a **Retrying** pill with a countdown appears at the top of the window and Orchard tries once more after 10 seconds. If that second attempt also fails, Orchard switches to offline mode and does not retry on its own.

To leave offline mode, reconnect to the internet and select the **Offline** pill at the top of the window, or **Try again** on the home page, or **Refresh** in the library. If you restart Orchard, it checks again from the start.

When Orchard gets back online it tells you and reloads your home page.

## What you see offline

- **Home** shows your recently downloaded songs, your playlists, and your local files.
- **Your library** lists downloaded and local songs in one table, and downloaded and local playlists as cards.
- **Search** looks only through those downloaded and local songs and playlists. The **Videos**, **Albums**, and **Artists** filters are hidden.
- **Playlists** in the sidebar are your local playlists and downloaded playlists and albums. A downloaded playlist lists only the songs that are still downloaded.
- **The queue** keeps only songs that can play offline. Autoplay and recommendations are paused, and songs that are not downloaded cannot start.

Album pages, artist pages, music videos, lyrics, likes, and edits to your YouTube Music playlists need a connection. Menu entries that would open or change them are disabled until you are back online.

## Troubleshooting

- **A download fails:** Orchard keeps failed downloads in the list on the **Downloads** settings page. Select **Retry** there, or wait: Orchard retries them when the connection comes back.
- **A song will not play offline:** it is not downloaded, or its file was moved or deleted. Download it again while online.

See also [Search](search.md), [Library and playlists](library-and-playlists.md), and [Local files and local playlists](local-files.md).
