---
title: Integrations
summary: Show what you play in Discord, send your listens to Last.fm, add Spotify Canvas loops, connect Qobuz for MAX quality, and connect Apple Music for browsing, Home and playback.
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
  - qobuz token
  - auth token
  - app id
  - app secret
  - apple music
  - musickit
  - home source
  - recommendations
  - widevine
  - memory
  - adaptive mix apple music
  - scan
platforms:
  - desktop
order: 130
---

# Integrations

Integrations connect Orchard to other apps. Settings, Integrations has cards for **Discord**, **Last.fm**, **Spotify**, **Qobuz**, and **Apple Music**.

## Turn on Discord Rich Presence

Discord Rich Presence shows your current song, artwork, and a link on your Discord profile. It is on by default.
The small circle over the cover shows the artist portrait from TheAudioDB when available, or the artist's YouTube profile image when Orchard has a matching artist page.

1. Start the Discord desktop app.
2. Open Orchard **Settings**, then **Integrations**.
3. In the **Discord** card, turn on the **Rich Presence** switch.

Every other Discord control is dimmed and disabled while the switch is off. The line under **Rich Presence** shows the status:

- "Show your current track in Discord." while the switch is off.
- "Connected to Discord."
- "Connecting to Discord…" while Orchard waits for a presence update response.
- "Discord is unavailable:" followed by the reason when the connection fails.

**Show Synced Lyrics** is off by default. Turn it on to show the active lyric line in Discord's state text when the track has synced lyrics. Discord activity updates are paced, so very short lines may be skipped. Empty and instrumental lines show the usual state text. The track title, artwork, and playback progress stay in their usual places.

During a crossfade or Adaptive mix, Discord's state text shows **Mixing into** the incoming track. It takes the place of a lyric line until the mix ends, then the active lyric or normal state returns.

## Change the text shown in Discord

Select **Customize text and display** in the Discord card to open a window with the text boxes and lists. Three text boxes shape what Discord shows. Each one accepts these variables: `{song}`, `{artist}`, `{album}`, `{platform}`, `{status}`, `{app}`, `{position}`, `{duration}`, and `{url}`.

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

Turn off the **Scrobbling** switch. The card then says "Connected as" followed by your user name and "Scrobbling is paused." The account stays connected. The switch is on by default. Orchard does not scrobble live streams.

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

## Connect Qobuz with a token

Use this when the browser sign-in does not work for you.

1. Sign in at play.qobuz.com in a web browser.
2. Open the browser's developer tools and find the local storage entry named `localuser`. Copy its `id` (your user ID) and `token` (your auth token).
3. In Orchard, open **Settings**, then **Integrations**.
4. In the **Qobuz** card, select **Use a token**.
5. Enter the user ID and the auth token, then select **Sign in**.

### Use your own Qobuz app ID and secret

The token form also has optional **App ID** and **App secret** fields. Fill in both or neither. With them, Orchard signs requests with your app secret and plays the FLAC file that the Qobuz API (`track/getFileUrl`) returns. Without them, Orchard uses the web player's credentials.

The auth token must come from the same app ID. A token copied from play.qobuz.com only works without your own app ID. "Qobuz rejected the app secret" means the secret does not match the app ID. "Qobuz returned a preview clip" means the account cannot stream that track in full.

Orchard checks the token with Qobuz before saving it. "Qobuz rejected this user ID and token" means the token expired or the user ID is wrong. Signing out of the Qobuz web player can end the token. Treat the token like a password.

## Disconnect Qobuz

Select **Disconnect** in the **Qobuz** card. Orchard removes the Qobuz sign-in from this device and switches MAX back to High.

## Connect Apple Music

Connect Apple Music to browse Apple Music artist pages, use personal recommendations on Home, and play Apple Music songs. An Apple Music subscription and an Orchard account are required.

1. Sign in to your Orchard account under **Settings**, **General**.
2. Open **Settings**, then **Integrations**.
3. In the **Apple Music** card, select **Connect Apple Music**.
4. Finish signing in in the Apple Music window. The window closes when sign-in finishes.

Select **Cancel** to stop connecting. Orchard stores the Apple Music token in the system keychain. The sign-in button is disabled when the build cannot open Apple Music sign-in or the Orchard account is signed out.

On Android, open **Settings**, **Connections**, **Apple Music**, then select **Sign in to Apple Music**. Sign in to the Orchard account under **Connections**, **Orchard Account** first.

## Choose Apple Music as the music source

On desktop, open **Settings**, **General** and set **Music source** to **Apple**. **Normal**, the default, uses YouTube Music. On Android, open **Settings**, **Appearance**, **Home screen layout**, then choose **Apple Music** under **Music source** on the **Online** tab.

The music source decides Home, search, artist, album and playlist pages on both platforms. Orchard never mixes the two services and never falls back to the other one. With **Apple** selected, Home shows Apple Music recommendations or charts, the sidebar search uses Apple Music, and an artist named on a YouTube Music song opens its Apple Music page. A YouTube Music album or playlist shows a message asking you to switch to YouTube Music; an Apple Music page shows the matching message under **Normal**. If Apple Music is disconnected or fails, the page shows an error instead of YouTube Music. Changing the source returns to Home on desktop. Your library and the sidebar playlists still come from YouTube Music.

## Play Apple Music songs

Apple Music songs play from Apple Music when **Music source** is **Apple**. They are not matched to YouTube Music. Under **Normal**, an Apple Music song shows "Choose Apple as the music source under Settings, General to play Apple Music songs." Your YouTube Music session is still required to play YouTube Music songs.

Orchard streams Apple Music songs itself and decrypts them in a small separate process, `orchard-widevine-host`, which uses about 80 MB of memory while songs play. It starts with the first Apple Music song and quits two minutes after playback stops, or when you switch **Music source** to **Normal** or disconnect Apple Music. Decrypted audio stays in memory and is never written to disk.

- Platforms: Linux only for now. On Windows, an Apple Music song shows "Apple Music playback is not available on this system yet." On Android, it shows "Apple Music playback is not available yet."
- Widevine: Apple Music audio is protected, so Linux needs the Widevine module from an installed Google Chrome, Chromium with Widevine, Brave, or Firefox. If none is found, the song shows "Apple Music playback needs Widevine." Set `ORCHARD_WIDEVINE_PATH` to a `libwidevinecdm.so` file to use another copy.
- Quality: Apple Music plays 256 kbps AAC. The **Streaming quality** setting does not apply.
- Apple Music songs play through the same player as YouTube Music songs, so the Audio Engine, crossfade, gapless playback and Adaptive mix work as described in [Crossfade and gapless playback](crossfade.md). Adaptive mix analyses Apple's own audio.
- Autoplay and AI-music scans do not apply to Apple Music songs.
- Volume, play, pause, seek, next and previous work as usual, including from media keys and the system media controls.
- Errors: "Apple Music is signed out" means the saved sign-in expired; connect Apple Music again. "Playing Apple Music songs needs an active Apple Music subscription" means the account has no subscription.
- Web player: set `ORCHARD_APPLE_WEB_PLAYER=1` to play Apple Music songs in Apple's web player (MusicKit) instead. It runs in a separate Orchard process that uses about 250 MB of memory, up to about 400 MB while the next song is buffered, and quits after five minutes without playback. The next section describes its transitions.

## Mix Apple Music songs in the web player

With `ORCHARD_APPLE_WEB_PLAYER=1`, crossfade, gapless playback and Adaptive mix work between two Apple Music songs inside Apple's player; Apple's audio never leaves it.

- **Standard crossfade:** the next song is buffered 20 seconds before the end, and the two songs blend over the crossfade length.
- **Gapless playback** (with **Crossfade** off): the next song is buffered 20 seconds before the end and starts as soon as the current one ends.
- Repeat One and songs shorter than the crossfade length start gaplessly.
- During any blend, the player bar, queue and full-screen player show the next song blending in, and the seek bar ends where the blend begins.
- Pausing during a blend pauses it; play continues the blend where it stopped. Seeking, skipping or choosing another song during a blend jumps to the next song.

### Adaptive mix with Apple Music

With **Crossfade style** set to **Adaptive mix**, Orchard looks up both songs on YouTube Music, plans and renders the blend from those uploads on this device, and plays that render between the two Apple songs. Apple's player finds the exact spot in its own audio by matching it against the YouTube Music audio, so the blend starts and hands over without a gap or a repeat.

- The lookup starts with the current song. The plan is made in the last two minutes, like YouTube Music songs.
- The blend itself (usually 5 to 15 seconds) is YouTube Music's audio. The rest of both songs is Apple's.
- Pausing just before a blend is fine: the mix waits and starts on time after play.
- When a pair plays without a blend, the status under **Crossfade style** says why, as for other songs. See [Crossfade and gapless playback](crossfade.md).
- When the two songs cannot be lined up (for example, the YouTube Music upload is a different edit), the pair falls back to the Standard crossfade.

### Songs without a YouTube Music match

The **Apple Music songs without a match** row appears in Settings, Playback, Transitions when **Crossfade style** is **Adaptive mix** and **Music source** is **Apple**. It decides what happens when either song has no YouTube Music upload. The status under **Crossfade style** then reads "Adaptive mix unavailable: No YouTube Music match for “Song” to plan from."

- **Standard** (default): the pair crossfades with Standard at the set length.
- **Scan**: Apple's player plays both songs muted at double speed to analyse them, plans the blend with the same engine, then captures the blend's few seconds again at normal speed. This needs:
  - about half the longer song's length plus about 80 seconds of the current song left when the scan starts, so it only works when the current song is played from near its start;
  - up to about 500 MB more memory while it runs, released afterwards;
  - extra plays on your Apple Music account: each scan plays both songs once more, muted.
  When the scan cannot finish in time, the pair falls back to the Standard crossfade.
- Scan is Linux only, and only in builds that include it. Otherwise the row reads "Scanning is not included in this build." and only Standard can be chosen.

## Disconnect Apple Music

Select **Disconnect** in the **Apple Music** card in **Settings**, **Integrations** on desktop or **Settings**, **Connections**, **Apple Music** on Android. Orchard removes the saved Apple Music user token and clears Apple's sign-in cookies and MusicKit page storage. If **Music source** is **Apple** on desktop, Home shows "Connect Apple Music in Settings to load Home." On Android, Home shows an Apple Music connection error until you reconnect or choose YouTube Music.
