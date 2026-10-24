---
title: Appearance
summary: Pick an accent color and popup background, adjust interface scaling, show the bitrate, set how the fullscreen player behaves, play music videos behind artist pages, turn animated artwork on or off, set the artwork source order, and tune the immersive background.
group: Customize
icon: palette
keywords:
  - appearance
  - accent color
  - theme
  - custom colors
  - popup background
  - menu color
  - interface scale
  - display scaling
  - high DPI
  - 4K
  - animated artwork
  - motion artwork
  - immersive background
  - artwork background
  - bitrate indicator
  - fullscreen player
  - hide controls
  - pulse
  - artist page video
  - music video background
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

Appearance settings control Orchard's colors, the player layout, the player bar bitrate indicator, the fullscreen player, lyrics motion, animated artwork, and the immersive background. They are in Settings, Appearance.

## Pick an accent color

Open **Settings**, then **Appearance**. In **Colors**, the accent color is used for switches, sliders, the selected page, and highlights across Orchard. Choose **Pick a color** and click a swatch, or click **Custom color** to choose any color. Choose **Match album art** to have the accent follow the cover of the song that is playing. When nothing is playing, Orchard uses the color you picked.

## Change the menu and popup background

In **Colors**, **Menu and popup background** sets the color behind menus, dialogs, and the Settings window. Click a swatch or **Custom color**. **See-through frosted popups** controls how much of the music shows through frosted popups such as release notes and Connect. Menus, Docs, and bug reports stay solid so they are easy to read.

Click **Reset colors** to return to the default accent and popup background.

## Make the interface larger or smaller

Open **Settings**, then **Appearance**. In **Display scaling**, adjust **Interface scale** from 75% to 250% in 5% steps. The slider resizes text, buttons, artwork, and menus together. Quit and reopen Orchard to apply the selected scale. If closing the window keeps Orchard in the system tray, use the tray's exit command to quit fully.

The default, 100%, follows your system display scaling. Orchard multiplies the selected scale by each monitor's system scale, including when you move the window between monitors. For example, 125% in Orchard with 200% system scaling gives 250% overall scaling. On a 4K display where controls look too small, try 125% or 150%.

Select **Reset scale** to return to 100%, then restart Orchard. **Reset controls** in the immersive background section does not change interface scaling.

## Choose a layout

Under **Layout**, **Player layout** picks where the player bar lives.

- **Glade** floats the player bar at the bottom of the window.
- **Canopy** puts a slimmer player bar at the top.

Both layouts keep a compact search field at the top of the sidebar. In a narrow window, select the search icon to open Spotlight.

Glade is the default.

## Show the audio bitrate

Turn on **Show audio bitrate** in the **Player bar** group. Orchard then shows the streaming bitrate in kbps in the player bar. The setting is off by default. See [Streaming quality](streaming-quality.md).

## Keep the fullscreen player in the app window

Turn on **Keep player windowed** in the **Fullscreen player** group. Clicking the player bar artwork or pressing F opens the same player layout inside the app window, keeping its size and position. You can move and resize the window while using the player. Press Esc, press F, or select the player's close button to return to the main view.

The setting is off by default and applies when you open the player. It is saved between launches.

## Stop the fullscreen controls from hiding

Turn off **Hide controls when idle** in the **Fullscreen player** group. The fullscreen player then keeps its controls and cursor on screen. The setting is on by default. See [Play music](playback.md).

## Stop the fullscreen player from pulsing with the music

Turn off **Pulse with the music** in the **Fullscreen player** group. The glow behind the cover then breathes slowly instead of brightening on each bass hit, and the background stays steady. The setting is on by default.

## Play a music video behind artist pages

Turn on **Play a music video behind the artist** in the **Artist pages** group. The top of each artist page then plays the first video from the artist's **Videos** shelf, muted, in place of the artist photo. The setting is off by default and uses extra network bandwidth.

- The photo shows until the video has loaded, then the video fades in.
- The video starts about a fifth of the way in, skipping most intros, and loops.
- **Now showing**, at the right end of the row under the artist name, names the video. Select the title (tooltip "Watch video") to open it in the music video player with sound.
- While the video plays, the whole window behind the artist page, including the sidebar and the top bar, takes its colors from the video and shifts with each scene. This replaces the artwork background on artist pages, whether or not **Use artwork background** is on. Black bars baked into the video are left out of the colors.
- The video pauses when you scroll past the top of the page, open a full shelf, leave the page, or minimize Orchard. The song that is playing is not affected.
- Artists without a **Videos** shelf, and videos that fail to load, keep the photo.

## Turn animated artwork on or off

Animated artwork streams moving cover art for supported songs and albums.

1. Open **Settings**.
2. Select **Appearance**.
3. In the **Animated artwork** group, use the **Enable animated artwork** switch.

Animated artwork is on by default and uses extra network bandwidth. Not every song or album has moving artwork.

On an artist page, hold the pointer over an album card for about half a second. If that album has moving artwork, the cover starts playing in place. Move the pointer away and the cover goes back to the still image. Singles, videos, and playlists do not animate on hover. With the switch off, nothing animates. The Artwork source controls and Mirror query order list are disabled while the switch is off.

On Android, the full player uses the tall version of an album's moving artwork when one exists. Some tall versions zoom into the cover and blur its lower part. To fix one, open the **⋯** menu in the full player and choose **Show Whole Cover**. Orchard plays the square version instead, or the still cover if there is no square version. The choice is saved for that album's artwork. Choose **Show Tall Cover** to undo it.

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

## Change how lyrics move

Synced lyrics highlight the line being sung. Three switches in the **Lyrics** group of **Settings**, **Appearance** change how the lines around it look. All three are on by default.

- **Blur distant lines**: lines blur more the further they are from the current line. Turn it off if lyrics make the app feel slow, because blur uses extra GPU power.
- **Fade distant lines**: lines dim the further they are from the current line.
- **Staggered scrolling**: when the lyrics advance, each line glides into place a moment after the one above it. With it off, the lyrics scroll as one block.

Blur and fade apply only while the lyrics follow the song. Scroll the lyrics by hand and every line shows sharp and bright until the lyrics catch up again. Hovering a line also shows it sharp. During an instrumental gap, three dots fill from left to right as the gap passes.

**Reset controls** turns all three switches back on.

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

Select **Reset controls**. The button restores Speed, Intensity, Saturation, and Brightness to their defaults. It also turns **Hide controls when idle**, **Pulse with the music**, the three **Lyrics** switches, and **Enable animated artwork** back on, sets the artwork source to Apple Music, restores the default mirror order, and turns **Play a music video behind the artist** off. It leaves **Use artwork background** and **Show audio bitrate** as they are.
