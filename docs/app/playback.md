---
title: Play music
summary: Control playback from the player bar and the fullscreen player.
group: Basics
icon: play
keywords:
  - play
  - pause
  - skip
  - seek
  - shuffle
  - repeat
  - volume
  - like
  - fullscreen
  - now playing
  - player bar
platforms:
  - desktop
order: 20
---

# Play music

The player bar at the bottom of the Orchard window controls playback. The fullscreen player shows the same controls with large cover art.

## Play or pause a song

Press Space, or select the play/pause button in the player bar. Space does nothing while you type in a text box or while Spotlight or Settings is open.

## Skip to the next or previous song

Select **Next** or **Previous** in the player bar. The keyboard media keys Media Next and Media Prev do the same.

## Jump to a point in a song

Drag the progress bar in the player bar. Press the Left arrow key to go back 5 seconds and the Right arrow key to go forward 5 seconds.

## Turn shuffle on or off

Select the shuffle button in the player bar. Its tooltip reads "Shuffle on" or "Shuffle off".

## Change the repeat mode

Select the repeat button in the player bar. Each click moves to the next mode in this order: Repeat off, Repeat all, Repeat one.

## Change the volume

Drag the volume slider next to the volume icon in the player bar. The icon changes to a muted speaker when the volume is at zero.

Turn on **Exponential volume** in Settings, Playback for finer control at low volumes. You can also choose **Exponential volume** on the welcome screen's **Tune the sound** step. The setting is off by default and is saved between sessions. With it on, 50% on the slider produces 12.5% output, while zero stays silent and 100% stays at full volume.

## Like a song

Select the heart button in the player bar or in the fullscreen player. Its tooltip reads "Like". Select the heart again ("Remove from liked songs") to unlike the song. The heart needs a signed-in YouTube account and a loaded song.

## Open the fullscreen player

Click the cover art in the player bar, or press F while a song is loaded. The fullscreen player shows "PLAYING FROM" followed by the album or playlist the song came from.

Inside the fullscreen player:

- Click the cover art to play or pause.
- Right-click the cover art, or select **More**, for the song menu.
- Select the lyrics button to show or hide lyrics. See [Lyrics](lyrics.md).
- Select the queue button to show or hide the **Up next** list. See [Manage the queue](queue.md).

## Close the fullscreen player

Press Esc, press F, or select the close button (tooltip "Close (Esc)").

## Watch music videos

When the playing song has a music video, the player bar shows a video button (tooltip "Watch music video"). Select it to open the music video player. The button is dimmed while Orchard looks for a video ("Finding music video") and hidden when the song has none. Selecting a video in search, Home, your library, or an artist page also opens the player.

The video fills the window. When a video has black bars built into it, Orchard trims them so the picture itself fills the space. Click the picture to play or pause, and double-click it to go full screen. The controls fade out a few seconds after you stop moving the mouse while the video plays, and come back when you move it:

- **Song** returns to the regular player. The close button and Esc do the same.
- The **Up next** button shows the queue beside the video. Select a row to play it.
- The quality button (it shows the playing height, for example "1080p") opens a menu: **Best available** or a height cap such as 720p. Orchard remembers the choice.
- Marked parts of the video show as colored spans on the seek bar, from SponsorBlock: non-music parts in orange, and sponsor segments, self-promotion and subscribe reminders in green. The **Non-music parts** setting in Settings, Playback applies: **Skip button** shows a skip button over the picture, and **Auto skip** skips them.
- The full screen button makes the window full screen. Esc leaves full screen first.

While the music video player is open, you hear the video's own soundtrack, so the picture and sound always match. The video starts from the song's current position. Returning to the regular player switches back to the album audio at the same point.

## See a crossfade happening

While Orchard blends one song into the next, the queue panel shows **MIXING** and the fullscreen player shows "Mixing" with the blend length. See [Crossfade and gapless playback](crossfade.md).

Halfway through the blend, the fullscreen player changes to the next song. The cover dissolves, and the title, artist, times, progress bar, and background change with it.

## Open a song's album or artist

Click the song title in the player bar to open its album. Click the artist name to open the artist page. A song with several artists shows a choice list.

## Keep music playing when the queue ends

Turn on **Autoplay** in Settings, Playback. Orchard then adds similar songs when the queue runs out. Autoplay is on by default. See [Manage the queue](queue.md) for the Autoplay switch in the queue panel.
