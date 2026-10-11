---
title: Crossfade and gapless playback
summary: Blend songs together with Standard crossfade or Adaptive mix, set the blend length, and turn on gapless playback.
group: Sound
icon: audio-lines
keywords:
  - crossfade
  - adaptive mix
  - smart crossfade
  - beat matching
  - mix songs
  - gapless
  - transition length
  - fade between songs
platforms:
  - desktop
order: 90
---

# Crossfade and gapless playback

Crossfade blends the end of one song into the start of the next. Orchard offers two crossfade modes, **Standard** and **Adaptive mix**, and a separate **Gapless playback** switch. These settings are in Settings, Playback.

## Turn crossfade on or off

Use the **Crossfade** switch in the **Transitions** group. Its description reads "Blend the end of one track into the next." Crossfade is on by default. The **Crossfade style** buttons (**Standard** and **Adaptive mix**) and the length slider are disabled while Crossfade is off.

## Change the Standard crossfade length

Standard mode uses the same crossfade length between songs.

1. Open **Settings**, then **Playback**.
2. Make sure **Crossfade** is on and **Standard** is selected.
3. Drag the **Crossfade length** slider.

The slider goes from 1 to 12 seconds in 1 second steps and shows "N seconds". The default is 6 seconds. The slider appears only in Standard mode.

## Turn on Adaptive mix

Adaptive mix matches beats and phrases, balances the blend, and shapes the handoff for each pair of songs. The analysis stays on your device.

1. Open **Settings**, then **Playback**.
2. Turn off the **Audio Engine** switch if it is on. Adaptive mix and the Audio Engine cannot run together.
3. Turn on the **Crossfade** switch.
4. Select **Adaptive mix**.

A status line under the mode buttons reports whether Adaptive mix is ready. When the next pair of songs plays without a blend, it reads "Adaptive mix ready · this pair plays without a blend". Select **Standard** to return to the fixed crossfade length.

## How Adaptive mix chooses a blend

Adaptive mix measures where each song's beat and vocals are, then picks the blend for each pair of songs:

- **Beat first.** If the next song starts with a beatless intro, Adaptive mix skips ahead so the new beat comes in during the blend. The new beat usually lands halfway through the blend, at the bass swap. If the current song's beat stops near its end, the blend starts while that beat is still playing.
- **Beat loops.** If the current song's beat stops before the song ends, Adaptive mix can repeat the last one or two bars of that beat under the blend until the next song's beat takes over. It loops only bars without vocals, and only when the loop keeps the beat going better than the song itself does.
- **Locked kicks.** Adaptive mix lines up the two songs' kick drums by listening to the audio, so the two beats hit together.
- **One bassline at a time.** Halfway through the blend, the old song's bass drops out and the new song's bass comes in. If the next song opens with kicks but no bassline, the swap lands where its full bass comes in.
- **Vocals.** Adaptive mix avoids two vocals at once and avoids starting the next song in the middle of a vocal line. When every possible blend would sing over a vocal, would swap one vocal line for another mid-line, or would fade the current singer out mid-line, the current song plays to its end and the next song starts from its beginning. This is common with R&B, where singing runs through most of the song.
- **Skipping.** Adaptive mix skips a beatless intro, but it avoids starting the next song far past its first beat.
- **Beatmatched or nothing.** Adaptive mix only blends songs whose beats it can line up, with tempos at most about 8% apart. Otherwise it does not crossfade: the current song plays to its end, and the next song starts from its beginning.
- **Different styles.** When two songs have very different production, for example 90s boom-bap and modern trap, Adaptive mix does not blend them. The current song plays to its end, and the next song starts from its beginning. Songs with somewhat different production blend only when the blend needs no filter. If the blend would put two vocals on top of each other, those songs also play without a blend.
- **Where blends happen.** Blends use the last minute of the current song and the first minute of the next song.

Adaptive mix picks each blend length from the songs.

## Why the Adaptive mix button is disabled

The **Adaptive mix** button is disabled in three cases:

- **Crossfade** is off. Turn it on.
- The **Audio Engine** is on. Turn the Audio Engine off, or keep Standard mode. The **Crossfade style** text reads "Adaptive mix is unavailable while the audio engine is on."
- **Streaming quality** is **MAX**. Songs from Qobuz can't be analyzed for a mix, so crossfades use Standard mode. The **Crossfade style** text reads "Adaptive mix is unavailable while streaming quality is MAX." Choose another quality to bring Adaptive mix back. See [Streaming quality](streaming-quality.md).

If Adaptive mix was selected earlier and the Audio Engine is on, turning Crossfade on switches the mode back to Standard.

## Adaptive mix requirements

Adaptive mix needs a GPU with WebGPU support and FFmpeg.

- Beat detection runs on the GPU and has no CPU fallback. Without a WebGPU device, songs change at their natural boundary without a mix.
- FFmpeg decodes the audio for analysis. Windows builds include FFmpeg. On Linux, install FFmpeg so it is on the PATH, or set the `ORCHARD_FFMPEG` environment variable to the FFmpeg executable.
- Adaptive mix was runtime-tested on Linux. Other desktop systems were not tested.

## Adaptive mix on Android

The Android app runs the same analysis, planner, and render code as the desktop app. The same two songs get the same mix on both. Android runs beat detection on the phone's CPU with smaller model weights, so a bar line can rarely land differently.

A failed Adaptive mix preparation never stops playback.

When the phone plays music and a computer is connected to it with Orchard Connect, the computer prepares the Adaptive mixes. The phone sends both songs to the computer, and the computer sends back the finished blend, which the phone plays at the right moment. If the computer disconnects or cannot prepare a mix, the phone prepares it itself. See [Orchard Connect](connect.md).

Android prepares an Adaptive mix in the background at low priority, so scrolling and animations stay smooth while it works. The mix models stay loaded for 10 minutes after the last mix. After that, or when the phone runs low on memory, Android frees them and loads them again for the next mix.

On Android, Standard crossfades run inside the audio pipeline. A busy screen cannot make the fade step or stutter. Pausing during a Standard crossfade pauses both songs.

On desktop, the player bar shows each mix. The cover slides to the next song's cover. The title and artist lift out as the next song's title and artist rise in. A glow rings the bar and a light sweeps along the progress bar until the mix ends.

On Android, the remaining time under the player's progress bar sparkles in rainbow colors when an Adaptive mix is ready. A seek keeps a ready mix. Seek to a point before the mix starts to hear it. A seek past the start of the mix skips it, and the song ends normally.

## Turn on gapless playback

Use the **Gapless playback** switch in the **Transitions** group. Its description reads "Preload the next song to minimize pauses between tracks." Gapless playback is off by default.

## Tell when a crossfade is happening

The queue panel labels the current song MIXING during a blend. The fullscreen player shows "Mixing" with the blend length. See [Play music](playback.md) and [Manage the queue](queue.md).

## Choose between Standard and Adaptive mix

Use **Standard** for a fixed, predictable blend length and for use with the Audio Engine. Use **Adaptive mix** when the songs have a clear beat and you want the blend placed on bar boundaries. See [Audio Engine and equalizer](audio-engine.md).
