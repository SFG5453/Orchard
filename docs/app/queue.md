---
title: Manage the queue
summary: Open the queue, add songs, reorder, remove, clear, show played songs, turn Autoplay on or off, and sort with Best Mix.
group: Basics
icon: list-music
keywords:
  - queue
  - up next
  - play next
  - add to queue
  - reorder queue
  - clear queue
  - best mix
  - autoplay
  - continuous
  - queue style
  - history
platforms:
  - desktop
order: 30
---

# Manage the queue

The queue is the list of songs that play after the current song. The queue panel shows it and lets you change it.

## Open the queue panel

Select the **Queue** button in the player bar. Select it again to close the panel. In the fullscreen player, the queue button shows or hides the **Up next** list.

The panel header shows the number of songs and their total length, for example "12 songs · 45 min". The current song sits at the top and is labeled NOW PLAYING, PAUSED, or MIXING. The songs after it sit under **UP NEXT**.

## Show played songs in the queue

Open Settings, Playback, Queue style and choose **Continuous**. The queue panel then lists the songs that already played under **PLAYED**, the current song under **NOW PLAYING**, and the upcoming songs under **UP NEXT**, in one list. The panel opens scrolled to the current song, and the player card at the top is hidden.

Click a played song to go back to it. The songs between it and the current song return to the front of the queue. Played songs cannot be dragged or removed. Orchard keeps the last 50 played songs.

**Up next** is the default. It lists only the upcoming songs. The fullscreen player's queue list always uses Up next.

## Add a song to the queue

1. Right-click a song (or press the Menu key on a focused card).
2. Select **Add to queue** to put the song at the end, or **Play next** to put it right after the current song.

The song menu is also available from the **More** button in the fullscreen player and the "More options" button on search results.

## Play a song from the queue

Click the song in the queue panel.

## Reorder the queue

Drag a song by its row in the queue panel and drop it where you want it. Each row has the accessible name "Drag to reorder".

## Remove one song from the queue

Select the **X** button on the song's row in the queue panel.

## Clear the queue

Select **Clear** at the top of the queue panel. Clear removes every upcoming song. The current song keeps playing, and Autoplay does not refill the queue while that song plays. The Clear button is disabled when the queue is empty.

## Turn Autoplay on or off in the queue panel

Use the **Autoplay** switch at the bottom of the queue panel. Its caption reads "Keep the music going". While Orchard looks for songs, the caption reads "Finding more music…". If the lookup fails, select **Retry**.

The same setting is in Settings, Playback, Autoplay. Autoplay is on by default. An empty queue with Autoplay on shows "Autoplay will keep things going."

## Sort the queue with Best Mix

Best Mix reorders up to 50 upcoming songs so that neighboring songs blend well by tempo, key, energy, and production style.

Best Mix avoids placing songs with very different bass production next to each other, for example 90s boom-bap or R&B next to modern trap. Adaptive mix does not blend those songs; it lets the first song end and starts the next. Best Mix puts such a pair together only when no other song fits.

1. Open the queue panel.
2. Select **Best Mix**. The button needs at least two songs in the queue.
3. Wait while the button shows "Downloading", "Analyzing", and "Finding transitions…" with a progress count such as 3/20.

Best Mix runs the same local analysis worker as Adaptive mix, and the analysis stays on your device. FFmpeg must be installed. Songs that cannot be analyzed keep their place. Changing the queue while Best Mix runs cancels the sort. The queue then shows each song's tempo as a BPM label. The Android app sorts with the same code and analyzes the same low-bitrate audio, so the same queue gets the same order on both. Best Mix on Android downloads that audio separately from your downloads and deletes it after analysis.

## Cancel Best Mix

Click the Best Mix button while it shows progress. Its tooltip reads "Click to cancel".

## Restore the original order after Best Mix

Select **Restore order**. The original order returns while the sorted songs are still in sequence.

## Fix a queue that fails to load

Select **Retry loading queue** when the queue panel shows that button.
