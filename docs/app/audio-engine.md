---
title: Audio Engine and equalizer
summary: Turn on the equalizer, balance, volume leveling, per-song gain, and pick an audio output device.
group: Sound
icon: sliders-horizontal
keywords:
  - audio engine
  - equalizer
  - eq
  - automatic eq
  - preset
  - bass boost
  - balance
  - dynamic leveling
  - volume normalization
  - output device
  - track gain
  - reset engine
platforms:
  - desktop
order: 80
---

# Audio Engine and equalizer

The Audio Engine processes Orchard's audio with an equalizer, balance, gain, and volume leveling. With the Audio Engine off, audio plays untouched. The Audio Engine is on by default, with Automatic EQ and Manual equalizer both off, and its status line reads "Direct".

All Audio Engine controls are in Settings, Playback, in the **Audio Engine** group.

## Turn the Audio Engine on or off

Use the **Audio Engine** switch at the top of the **Audio Engine** group. The status label in the spectrum display shows one of: "Bypassed" (engine off), "Automatic EQ active", "Manual EQ active", or "Direct" (engine on, no EQ).

The Audio Engine cannot be turned on while Adaptive mix is on. See [Crossfade and gapless playback](crossfade.md).

## Turn on Automatic EQ

Automatic EQ gently balances bass, mids, and treble as each song plays.

1. Open **Settings**, then **Playback**.
2. Turn on the **Automatic EQ** switch. It needs the Audio Engine switch on.

Automatic EQ and Manual equalizer exclude each other. Turning one on turns the other off.

## Use an equalizer preset

1. Turn on the **Audio Engine** switch.
2. Turn on the **Manual equalizer** switch.
3. Select a preset button: **Flat**, **Bass boost**, **Electronic**, **Rock**, **Vocal**, **Acoustic**, or **Bright**.

Selecting a preset sets all ten band sliders at once, and the selected preset is highlighted. The preset buttons are disabled until the Audio Engine and Manual equalizer are on.

## Shape the equalizer by hand

Turn on **Manual equalizer**, then drag the band sliders. There are ten bands: 31 Hz, 62 Hz, 125 Hz, 250 Hz, 500 Hz, 1 kHz, 2 kHz, 4 kHz, 8 kHz, and 16 kHz. Each band goes from -12 dB to +12 dB in 0.5 dB steps and starts at 0 dB.

**EQ preamp** sets the level before the bands. It goes from -12 dB to +6 dB and starts at 0 dB. The band sliders and EQ preamp need the Audio Engine and Manual equalizer on.

## Change the width of the equalizer bands

Drag the **Band width** slider. It shows a Q value from 0.4 to 2.4 in steps of 0.1 and starts at Q 1.1. It needs the Audio Engine on.

## Shift the stereo balance

Drag the **Balance** slider. It reads "Center" in the middle, "Left" with a percentage to the left, and "Right" with a percentage to the right. It needs the Audio Engine on.

## Change the overall level with Global gain

Drag the **Global gain** slider. It goes from -24 dB to +6 dB in 0.5 dB steps and starts at 0 dB.

## Reduce sudden volume jumps with Dynamic leveling

Turn on **Dynamic leveling**. Its description reads "Reduce sudden volume jumps and control loud peaks." It is off by default.

## Adjust the level of one song with Track gain

Drag the **Track gain** slider while the song plays. It goes from -12 dB to +12 dB in 0.5 dB steps. Orchard remembers the value for that song and applies it each time the song plays. A value of 0 dB removes the saved setting. The slider needs the Audio Engine on and a loaded song.

## Choose the audio output device

Pick a device in the **Output device** list. Its description reads "Route Orchard to a specific system audio output." The default is the system default output.

## Reset the Audio Engine

Select **Reset engine** at the bottom of the group. Reset engine turns the Audio Engine on, turns Automatic EQ, Manual equalizer, and Dynamic leveling off, sets every band, preamp, Global gain, and balance to their starting values, sets Band width to Q 1.1, clears every saved Track gain, and returns the output device to the system default.
