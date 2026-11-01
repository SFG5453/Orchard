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
  - up next
  - screensaver
  - player bar
  - picture in picture
  - pip
  - floating video
  - mini player
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

Drag the volume slider next to the volume icon in the player bar. In the Canopy layout, click the volume icon to open a vertical slider below it, and drag that. In both layouts you can also scroll the mouse wheel over the control. The icon changes to a muted speaker when the volume is at zero.

Turn on **Exponential volume** in Settings, Playback for finer control at low volumes. You can also choose **Exponential volume** on the welcome screen's **Tune the sound** step. The setting is off by default and is saved between sessions. With it on, 50% on the slider produces 12.5% output, while zero stays silent and 100% stays at full volume.

## Like a song

Select the heart button in the player bar or in the fullscreen player. Its tooltip reads "Like". Select the heart again ("Remove from liked songs") to unlike the song. The heart needs a signed-in YouTube account and a loaded song.

## Open the fullscreen player

Click the cover art in the player bar, or press F while a song is loaded. The fullscreen player shows "PLAYING FROM" followed by the album or playlist the song came from.

To use this layout in a movable, resizable app window, turn on **Keep player windowed** under **Settings**, **Appearance**, **Fullscreen player**. It applies the next time you open the player. See [Appearance](appearance.md).

Inside the fullscreen player:

- Click the cover art to play or pause.
- Right-click the cover art, or select **More**, for the song menu.
- Select the lyrics button to show or hide lyrics. See [Lyrics](lyrics.md).
- Select the queue button to show or hide the **Up next** list. See [Manage the queue](queue.md).

While the fullscreen player is open and music is playing, Orchard asks the system to keep the screen from dimming or locking.

## Hide the fullscreen controls

While music plays, the fullscreen player hides its controls and the cursor after 3 seconds without mouse movement. The title, artist, and a faint progress bar stay. With lyrics and the queue hidden, the cover grows into the space. With lyrics open beside the cover, the cover shrinks and the lyrics get wider and larger.

Move the mouse, or press Space, Left, or Right, to bring the controls back. The controls stay on screen while playback is paused, while the queue is open, or while the pointer rests on them. To keep them on screen all the time, turn off **Hide controls when idle** in Settings, Appearance. See [Appearance](appearance.md).

## See the next song in the fullscreen player

About 15 seconds before the current song ends or starts mixing into the next one, a small **UP NEXT** card with the next song's cover, title, and artist appears in the top-left corner. Select the card to skip to that song. The card does not appear when repeat one is on or the queue is empty.

## Make the fullscreen player pulse with the music

The glow behind the cover and the background brighten on each bass hit. To turn this off, turn off **Pulse with the music** in Settings, Appearance. When the setting is off, or when Orchard cannot read the audio (for example while playing on another device), the glow breathes slowly instead.

## Close the fullscreen player

Press Esc, press F, or select the close button (tooltip "Close (Esc)").

## Watch music videos

When the playing song has a music video, the player bar shows a video button (tooltip "Watch music video"). Select it to open the music video player. The button is dimmed while Orchard looks for a video ("Finding music video") and hidden when the song has none. Selecting a video in search, Home, your library, an artist page, or an album page also opens the player.

A video card shows the video's thumbnail. Point at it to show a play button in the bottom left corner and a **More options** button (three dots) in the bottom right corner. Select the card or the play button to watch the video. **More options** opens the same menu as right-clicking the card.

Album pages list related shelves under the tracks:

- YouTube Music: a **Music Videos** shelf with the album's videos, when it has any, then shelves such as **Other versions** and **Releases for you**.
- Apple Music: **Music Videos**, **More By** the artist, **Other Versions**, **You Might Also Like**, and **Featured On** (playlists with the album), when Apple has them.

Apple Music videos play from Apple Music in the same player when **Music source** is **Apple**. See [Watch Apple Music videos](integrations.md#watch-apple-music-videos).

The video fills the window. When a video has black bars built into it, Orchard trims them so the picture itself fills the space. Click the picture to play or pause, and double-click it to go full screen. The controls fade out a few seconds after you stop moving the mouse while the video plays, and come back when you move it:

- **Song** returns to the regular player and the album audio.
- The minimize button (top left, tooltip "Minimize video") and Esc shrink the video into a corner of the main window, so you can browse Orchard while it plays. See [Keep watching while you browse](playback.md#keep-watching-while-you-browse).
- The **Up next** button shows the queue beside the video. Select a row to play it.
- The quality button (it shows the playing height, for example "1080p") opens a menu: **Best available** or a height cap such as 720p. Orchard remembers the choice. For Apple Music videos on Linux, the menu notes "Apple limits videos on Linux to 486p."
- **FSR 1 upscaling** in the same menu sharpens a video that is smaller than the window, using AMD FidelityFX Super Resolution 1. It is on by default and only works when the picture is shown larger than the video. Drag **Sharpness** to the left for a softer picture or to the right for crisper edges. Orchard remembers both. On computers where Orchard falls back to OpenGL, the setting has no effect.
- Marked parts of the video show as colored spans on the seek bar, from SponsorBlock: non-music parts in orange, and sponsor segments, self-promotion and subscribe reminders in green. The **Non-music parts** setting in Settings, Playback applies: **Skip button** shows a skip button over the picture, and **Auto skip** skips them.
- The full screen button makes the window full screen. Esc leaves full screen first.
- The picture in picture button, next to the full screen button, moves the video into a small floating window. See [Watch in picture in picture](playback.md#watch-in-picture-in-picture).

While the music video player is open, you hear the video's own soundtrack, so the picture and sound always match. The video starts from the song's current position. Returning to the regular player switches back to the album audio at the same point.

## Keep watching while you browse

Select the minimize button in the music video player, or press Esc, to shrink the video into a corner of the Orchard window. The rest of Orchard works as usual, and the video keeps playing with its own soundtrack.

- Drag the small player to move it. When you let go, it settles in the nearest corner. Orchard remembers the corner.
- Point at it to show its controls: **Expand video** (top left), play or pause (center), **Picture in picture** and **Close video** (top right).
- **Expand video**, or a double-click on the picture, returns to the full music video player.
- **Picture in picture** moves the video into its own window. Closing that window brings the video back to the corner.
- **Close video** stops the video and switches back to the album audio, like **Song**.
- The video button in the player bar also returns to the full music video player.

## Watch in picture in picture

Select the picture in picture button (tooltip "Picture in picture") in the music video player or on the small player in the corner. The video moves into a small window without a frame that stays above other windows, and the main window goes back to the regular player. The video keeps playing in step with the sound, and you still hear the video's own soundtrack. YouTube and Apple Music videos both work.

- Drag anywhere on the picture to move the window. Drag an edge or a corner to resize it.
- Point at the window to show its controls: **Back to app** (top left), play or pause (center), and **Close picture in picture** (top right). They fade out after two seconds while the video plays.
- **Back to app**, or a double-click on the picture, returns the video to the music video player and brings the main window to the front.
- **Close picture in picture**, Esc, or closing the window another way (for example Alt+F4) also returns the video to the main window, without bringing it forward. The video goes back to where it was: the music video player, or the corner of the window.
- Space plays or pauses while the window is focused.
- Minimizing the main window leaves the video window up.
- The video button in the player bar also returns the video to the music video player.
- Orchard remembers the window's size and position. A position on a screen that is no longer connected falls back to the bottom right corner of the main screen.

Limits:

- KDE Plasma on Wayland: Wayland does not let apps keep their own windows above others, so the video window can go behind other windows. To keep it on top, press Alt+F3 on the video window and choose **More Actions**, **Keep Above Others**. For a permanent fix, add a KWin window rule (System Settings, Window Management, Window Rules) that matches the window title "Orchard Picture in Picture" and forces **Keep above other windows**.
- Wayland: apps cannot place their own windows, so Orchard restores only the size there. Plasma picks the position.
- With FSR 1 upscaling on, the small window rarely shows the picture larger than the video, so upscaling often has nothing to do.

## See a crossfade happening

While Orchard blends one song into the next, the queue panel shows **MIXING** and the fullscreen player shows "Mixing" with the blend length. See [Crossfade and gapless playback](crossfade.md).

Halfway through the blend, the fullscreen player changes to the next song. The cover dissolves, and the title, artist, times, progress bar, and background change with it.

## Open a song's album or artist

Click the song title in the player bar to open its album. Click the artist name to open the artist page. A song with several artists shows a choice list.

## Keep music playing when the queue ends

Turn on **Autoplay** in Settings, Playback. Orchard then adds similar songs when the queue runs out. Autoplay is on by default. See [Manage the queue](queue.md) for the Autoplay switch in the queue panel.
