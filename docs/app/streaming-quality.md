---
title: Streaming quality
summary: Choose Saver, Normal, High, or MAX audio quality, play lossless from Qobuz, and show the bitrate in the player bar.
group: Sound
icon: volume-2
keywords:
  - streaming quality
  - audio quality
  - bitrate
  - saver
  - normal
  - high
  - data usage
  - kbps
  - max
  - qobuz
  - lossless
  - hi-res
  - flac
platforms:
  - desktop
order: 70
---

# Streaming quality

Streaming quality sets how much audio detail Orchard requests. The default is **High**. **MAX** plays lossless audio from your own Qobuz subscription.

## Change the streaming quality

1. Open **Settings**.
2. Select **Playback**.
3. Under **Streaming quality**, select **Saver**, **Normal**, **High**, or **MAX**.

The selected button is highlighted. **MAX** is disabled until a Qobuz account is connected.

## What Saver, Normal, High, and MAX mean

- **Saver**: the lowest bitrate audio and 480p video.
- **Normal**: up to 128 kbps audio and 720p video.
- **High**: the best available audio and video from YouTube Music. High is the default.
- **MAX**: lossless and Hi-Res audio from Qobuz, up to 24-bit/192 kHz. Songs Qobuz can't match play at High.

## Use MAX quality with Qobuz

MAX needs a Qobuz account with an active subscription.

1. Connect Qobuz in Settings, Integrations. See [Integrations](integrations.md).
2. Orchard switches **Streaming quality** to **MAX** when the connection finishes.

With MAX on, Orchard looks up each song on Qobuz and plays the Qobuz recording when it finds a confident match. Otherwise the song plays from YouTube Music at High. YouTube Music stays the catalog for search, your library, and playlists. Songs played from Qobuz are not added to your YouTube Music history.

If your phone is signed in to Qobuz and connected to this computer with Orchard Connect, you can choose **MAX** without connecting Qobuz here. The phone streams the Qobuz audio to this computer. See [Orchard Connect](connect.md).

Choose another quality to stop playing from Qobuz. Disconnecting Qobuz switches MAX back to High. Adaptive mix is unavailable while MAX is on. See [Crossfade and gapless playback](crossfade.md).

## See whether a song plays from Qobuz

A pill next to the artist name in the player bar says **Hi-Res** or **Lossless** while a song plays from Qobuz. The fullscreen player shows the same tier with the bit depth and sample rate, for example "Hi-Res · 24-bit · 96 kHz". The pill appears even when **Show audio bitrate** is off.

## See which albums play in Hi-Res

With MAX on, an album page shows a **Hi-Res** or **Lossless** badge next to the album type and year on the left side of the page. The badge is the best quality Qobuz streams for that album. Hover over it to see the bit depth and sample rate. Albums Qobuz can't match show no badge.

## Show the audio bitrate in the player bar

Turn on the bitrate indicator to see the bitrate of the current stream.

1. Open **Settings**.
2. Select **Appearance**.
3. Under **Player bar**, turn on **Show audio bitrate**.

The indicator is off by default. It appears next to the artist name in the player bar as a small pill with the bitrate in kbps. The fullscreen player shows the same value. Songs from Qobuz show their quality tier instead. See [Appearance](appearance.md).

## Save data on a slow or metered connection

Select **Saver** under Settings, Playback, Streaming quality. Animated artwork also uses network bandwidth, so turn it off under Settings, Appearance, Animated artwork if data use matters. See [Appearance](appearance.md).
