---
title: Integrations
summary: Show what you play in Discord, send your listens to Last.fm, add Spotify Canvas loops, and connect Qobuz for MAX quality.
group: Customize
icon: plug
keywords:
  - discord
  - rich presence
  - activity text
  - last.fm
  - lastfm
  - scrobble
  - scrobbling
  - integrations
  - spotify
  - spotify canvas
  - sp_dc
  - qobuz
  - lossless
platforms:
  - desktop
order: 130
---

# Integrations

Integrations connect Orchard to other apps. Settings, Integrations has four cards: **Discord**, **Last.fm**, **Spotify**, and **Qobuz**.

## Turn on Discord Rich Presence

Discord Rich Presence shows your current song, artwork, and a link on your Discord profile. It is on by default.
The small circle over the cover shows the artist portrait from TheAudioDB when available, or the artist's YouTube profile image when Orchard has a matching artist page.

1. Start the Discord desktop app.
2. Open Orchard **Settings**, then **Integrations**.
3. In the **Discord** card, turn on the **Discord Rich Presence** switch.

Every other Discord control is dimmed and disabled while the switch is off. The Discord card ends with a status line:

- "Discord Rich Presence is off."
- "Connected to Discord."
- "Connecting to Discord…" while Orchard waits for a presence update response.
- "Discord is unavailable:" followed by the reason when the connection fails.

**Show Synced Lyrics** is off by default. Turn it on to show the active lyric line in Discord's state text when the track has synced lyrics. Discord activity updates are paced, so very short lines may be skipped. Empty and instrumental lines show the usual state text. The track title, artwork, and playback progress stay in their usual places.

During a crossfade or Adaptive mix, Discord's state text shows **Mixing into** the incoming track. It takes the place of a lyric line until the mix ends, then the active lyric or normal state returns.

## Change the text shown in Discord

Three text boxes shape what Discord shows. Each one accepts these variables: `{song}`, `{artist}`, `{album}`, `{platform}`, `{status}`, `{app}`, `{position}`, `{duration}`, and `{url}`.

- **Activity text template**: the main line. The default is `{song}`.
- **Secondary text template**: an optional second line, empty by default.
- **State template**: an optional state line, empty by default.

Example: set **Activity text template** to `{song} by {artist}` to show both in the main line.

## Choose the platform name shown in Discord

Pick **YouTube Music** or **Orchard V3** in the **Platform** list. Discord shows the selection as "Listening to" followed by the platform name. The default is YouTube Music.

## Change the Discord activity type

Pick **Listening**, **Playing**, **Watching**, or **Competing** in the **Activity type** list. The default is Listening.

## Choose what the Discord member list shows

Pick **Platform**, **Details**, or **State** in the **Member-list display** list. The default is Platform.

## Remove the Orchard project button from Discord

Turn off the **Orchard project button** switch. When it is on, Orchard adds a "View the Orchard Project" button as the optional second Discord button. It is on by default.

## Show animated artwork in Discord

Animated artwork in Discord needs an Orchard account.

1. Sign in to your Orchard account in Settings, General. See [Accounts and startup](accounts.md).
2. Open **Settings**, then **Integrations**.
3. In the **Discord** card, turn on **Animated artwork**.

Orchard converts motion artwork to animated WebP and shows it in Discord. The switch is disabled while you are signed out of the Orchard account. See [Appearance](appearance.md) for motion artwork itself.

## Connect Last.fm

Last.fm scrobbling sends now-playing updates and finished listens to your Last.fm profile.

1. Open **Settings**, then **Integrations**.
2. In the **Last.fm** card, select **Connect Last.fm**. Orchard opens Last.fm in your browser.
3. Approve Orchard on the Last.fm page.
4. Return to Orchard and select **Finish connection**.

While Orchard waits for approval, the card says "Approve Orchard on Last.fm, then finish the connection here." Select **Cancel** to stop connecting. When the connection works, the card shows your Last.fm user name and "Scrobbling as" followed by that name.

## Pause Last.fm scrobbling

Turn off the **Last.fm scrobbling** switch. The card then says "Connected as" followed by your user name and "Scrobbling is paused." The account stays connected. The switch is on by default. Orchard does not scrobble live streams.

## Disconnect Last.fm

Select **Disconnect** in the **Last.fm** card. The button appears while an account is connected.

## Show Spotify Canvas loops as moving artwork

Spotify Canvas loops are short looping videos that Spotify shows for some songs. Orchard can use them as moving artwork when the other artwork mirrors have none. Spotify only serves them to signed-in listeners, so this needs your Spotify login.

1. Open **Settings**, then **Integrations**.
2. In the **Spotify** card, select **Log in to Spotify**.
3. Log in to Spotify in the window that opens. The window closes when the login finishes.

The card then says "Connected. Canvas loops are tried after the other artwork mirrors." Select **Cancel** to stop logging in. Canvas loops are portrait videos. In the fullscreen player, the cover grows into a tall card and the whole loop plays. Everywhere else, Orchard crops the loop to the square cover. Animated artwork must be on. See [Appearance](appearance.md).

## Connect Spotify with an sp_dc cookie

Use this when the login window doesn't work, or on systems without one.

1. In a web browser signed in to open.spotify.com, copy the value of the `sp_dc` cookie.
2. In the **Spotify** card, select **Enter cookie**.
3. Paste the value into the box and select **Save**.

A whole Cookie header also works; Orchard keeps only the `sp_dc` part. Orchard stores the cookie in the system keychain.

## Disconnect Spotify

Select **Disconnect** in the **Spotify** card. Orchard removes the saved cookie from the keychain and stops asking Spotify for Canvas loops.

## Connect Qobuz

Connecting Qobuz unlocks the **MAX** streaming quality, which plays lossless and Hi-Res audio from your own Qobuz subscription.

1. Open **Settings**, then **Integrations**.
2. In the **Qobuz** card, select **Connect Qobuz**. Orchard opens the Qobuz sign-in page in your browser.
3. Sign in to Qobuz and approve Orchard.
4. Return to Orchard.

When the connection finishes, the card says "Qobuz is connected, and streaming quality is now MAX." Select **Cancel** to stop connecting. Orchard stores the Qobuz sign-in in the system keychain. This uses Qobuz's private web API, so it may stop working when Qobuz changes its web player. See [Streaming quality](streaming-quality.md) for what MAX does.

## Disconnect Qobuz

Select **Disconnect** in the **Qobuz** card. Orchard removes the Qobuz sign-in from this device and switches MAX back to High.
