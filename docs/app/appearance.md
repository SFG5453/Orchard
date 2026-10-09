---
title: Appearance
summary: Show the bitrate, turn animated artwork on or off, set the artwork source order, and tune the immersive background.
group: Customize
icon: palette
keywords:
  - appearance
  - animated artwork
  - motion artwork
  - immersive background
  - artwork background
  - bitrate indicator
  - layout
  - glade
  - canopy
  - mirror order
  - speed
  - saturation
  - brightness
platforms:
  - desktop
order: 110
---

# Appearance

Appearance settings control the player layout, the player bar bitrate indicator, animated artwork, and the immersive background. They are in Settings, Appearance.

## Choose a layout

Under **Layout**, **Player layout** picks where the player bar lives.

- **Glade** floats the player bar at the bottom of the window. Search is the wide field at the top.
- **Canopy** puts a slimmer player bar at the top. Search is a small glass circle beside it that grows into a field when you select it, and shrinks back when you click away with nothing typed.

Glade is the default.

## Show the audio bitrate

Turn on **Show audio bitrate** in the **Player bar** group. Orchard then shows the streaming bitrate in kbps in the player bar. The setting is off by default. See [Streaming quality](streaming-quality.md).

## Turn animated artwork on or off

Animated artwork streams moving cover art for supported songs and albums.

1. Open **Settings**.
2. Select **Appearance**.
3. In the **Animated artwork** group, use the **Enable animated artwork** switch.

Animated artwork is on by default and uses extra network bandwidth. Not every song or album has moving artwork.

On an artist page, hold the pointer over an album card for about half a second. If that album has moving artwork, the cover starts playing in place. Move the pointer away and the cover goes back to the still image. Singles, videos, and playlists do not animate on hover. With the switch off, nothing animates. The Artwork source controls and Mirror query order list are disabled while the switch is off.

## Animate playlist collages

Playlists that Orchard shows with a four-cover collage can play moving artwork inside the collage. This is off by default because it can be system intensive: up to four videos play at once, which uses extra CPU, GPU, and network bandwidth.

1. Open **Settings**, then **Appearance**.
2. In the **Animated artwork** group, turn on **Animate playlist collages**.

The switch only works while **Enable animated artwork** is on. Orchard checks that each quarter of the cover looks like the artwork of the first four songs with different artwork, in order. A custom cover fails the check and stays still. Those four albums are looked up for motion artwork. Each album that has it plays in its own quarter of the cover. The other quarters stay still. Only the cover on the playlist page animates, not playlist cards.

## Choose the animated artwork source

The **Artwork source** setting says where motion artwork is fetched from. **Apple Music** is the only source.

## Change the mirror query order

Orchard asks artwork mirrors for motion art in order, from top to bottom. If a mirror has no motion art or times out, Orchard asks the next one. The default order is m8tec (artwork.m8tec.top), then boidu (artwork.boidu.dev), then Spotify Canvas (spclient.wg.spotify.com). Spotify Canvas is asked only for songs, and only when Spotify is connected in Settings, Integrations. See [Integrations](integrations.md).

1. Open **Settings**, then **Appearance**.
2. In **Mirror query order**, select the up arrow or down arrow on a mirror's row to move it.

Select **Reset order** (accessible name "Reset mirror order") to restore the default order.

## Turn on the immersive background

The immersive background paints flowing colors from the current song's artwork behind Orchard. It is off by default.

1. Open **Settings**, then **Appearance**.
2. In the **Immersive background** group, turn on **Use artwork background**.

The background motion pauses when playback pauses. Set **Speed** to zero for a still background.

## Tune the immersive background

Four sliders in the **Immersive background** group shape the effect. They are disabled while **Use artwork background** is off.

- **Speed**: from 0 to 5 times, default 1.38×.
- **Intensity**: from 0% to 100%, default 92%.
- **Saturation**: from 0% to 300%, default 124%.
- **Brightness**: from 0% to 200%, default 100%.

## Reset the immersive background controls

Select **Reset controls**. The button restores Speed, Intensity, Saturation, and Brightness to their defaults. It also turns **Enable animated artwork** back on, sets the artwork source to Apple Music, and restores the default mirror order. It leaves **Use artwork background** and **Show audio bitrate** as they are.
