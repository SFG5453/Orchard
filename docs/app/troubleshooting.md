---
title: Troubleshooting
summary: Fixes for common Orchard problems, listed by symptom.
group: Help
icon: circle-alert
keywords:
  - troubleshooting
  - not working
  - greyed out
  - disabled
  - no sound effects
  - empty library
  - discord not showing
  - lastfm not connecting
  - docs search
platforms:
  - desktop
order: 150
---

# Troubleshooting

Each section starts with what you see. Find the matching symptom and follow the first fix.

## The music video player says "No music video for this song."

Orchard found no official video with the same title and artist and a similar length. Select **Back to song** to return to the regular player. See [Watch music videos](playback.md#watch-music-videos).

## The music video player says "The video couldn't be played."

The video stream failed to load or expired, and the song keeps playing from its album audio. Select **Try again** to return to the video. See [Watch music videos](playback.md#watch-music-videos).

## The Adaptive mix button is disabled

Adaptive mix is disabled when **Crossfade** is off or when the **Audio Engine** is on. Turn **Crossfade** on, then turn the **Audio Engine** switch off. See [Crossfade and gapless playback](crossfade.md).

## The Audio Engine switch is disabled

The **Audio Engine** row says "Unavailable while adaptive mix is on. Switch crossfade to Standard to use it." Select **Standard** under **Crossfade style** in the **Transitions** group, then turn the **Audio Engine** on. See [Audio Engine and equalizer](audio-engine.md).

## The equalizer sliders or preset buttons are disabled

The band sliders, EQ preamp, and presets need both the **Audio Engine** switch and the **Manual equalizer** switch on. Turn both on. See [Audio Engine and equalizer](audio-engine.md).

## Songs change without a blend

Check these in order:

1. **Crossfade** is on in Settings, Playback.
2. In Standard mode, the **Crossfade length** slider is set to a length you can hear. The default is 6 seconds.
3. In Adaptive mix mode, the status line under the mode buttons says Adaptive mix is ready. Adaptive mix needs a WebGPU-capable GPU and FFmpeg. Without them, songs change at their natural boundary. See [Crossfade and gapless playback](crossfade.md).

## Lyrics show "Lyrics unavailable"

Select **Try again** in the lyrics view. If the message returns, none of the lyric sources have lyrics for that song. See [Lyrics](lyrics.md).

## Animated artwork does not appear

1. Turn on **Enable animated artwork** in Settings, Appearance.
2. Remember that only some songs and albums have moving artwork.

If the artwork still does not move, select **Reset mirror order** to try the default mirrors. See [Appearance](appearance.md).

## Discord does not show my song

1. Start the Discord desktop app. The Discord card says "Connecting to Discord…" while Orchard waits for a presence update response.
2. Turn on **Discord Rich Presence** in Settings, Integrations.
3. Read the status line at the bottom of the Discord card. "Discord is unavailable:" is followed by the reason.

See [Integrations](integrations.md).

## The Discord animated artwork switch is disabled

Sign in to your Orchard account in Settings, General. The Discord **Animated artwork** switch needs it. See [Accounts and startup](accounts.md).

## Last.fm will not finish connecting

1. Select **Connect Last.fm** and approve Orchard in the browser page that opens.
2. Return to Orchard and select **Finish connection**. The button works after the browser step starts.
3. Select **Cancel** and start again if the card stays on "Approve Orchard on Last.fm, then finish the connection here."

See [Integrations](integrations.md).

## Last.fm connected but nothing is scrobbled

Turn on the **Last.fm scrobbling** switch. The card reads "Connected as" followed by your user name and "Scrobbling is paused." while the switch is off. Orchard does not scrobble live streams.

## The Home page or Your library is empty

Select **Refresh** on the page. Home says "Your music home is empty. Refresh to try again." when it has nothing to show. Your library says "Could not load" followed by the category name and the reason when a category fails. See [Library and playlists](library-and-playlists.md).

## Add to playlist is greyed out

**Add to playlist…** needs a signed-in YouTube account and a playable song. Sign in, then try again. See [Library and playlists](library-and-playlists.md).

## Remove from this playlist is missing

The entry appears only on playlists that Orchard can edit. Open one of your own playlists. See [Library and playlists](library-and-playlists.md).

## The Best Mix button is disabled or the sort stopped

Best Mix needs at least two songs in the queue. Changing the queue while Best Mix runs cancels the sort. Select **Best Mix** again when the queue is settled. See [Manage the queue](queue.md).

## The queue is empty and Autoplay adds nothing

Autoplay does not refill the queue after **Clear** while the current song plays. Turn **Autoplay** on in the queue panel or in Settings, Playback, and select **Retry** if the queue panel shows it. See [Manage the queue](queue.md).

## A song is skipped on its own

The **AI-generated music** setting is on **Skip** or **Remove**, and Orchard flagged the song. Select **Mark** or **Off** in Settings, Playback. See [AI-generated music](ai-music.md).

## Space and the arrow keys do nothing

Orchard turns these shortcuts off while you type in a text box and while Spotlight or Settings is open. Close Spotlight or Settings, or click outside the text box, then press the key again. See [Keyboard shortcuts](shortcuts.md).

## Closing the window leaves Orchard running

**Close to system tray** is on, so the window hides and the music keeps playing. Open Orchard again to show the window, use the exit command in the tray menu, or turn the switch off in Settings, General. See [Accounts and startup](accounts.md).

## Orchard starts with an old queue

The **Save queue and current song** switch in Settings, General restores the last queue when Orchard starts. Turn it off to start with an empty player. See [Accounts and startup](accounts.md).

## The audio sounds wrong after changing Audio Engine settings

Select **Reset engine** at the bottom of the **Audio Engine** group. Reset engine clears every saved Track gain and returns the output device to the system default, so use it only when you accept losing those settings. See [Audio Engine and equalizer](audio-engine.md).

## The docs show no Best matches

Type at least three characters and ask about something the docs cover, such as "how do I open the queue". The docs show no **Best matches** and say "No pages match" when nothing fits. If the box reads **Search the docs** instead of **Search or ask a question**, the search model is missing from this copy of Orchard, and the box only matches words. See [Use the docs](using-the-docs.md).
