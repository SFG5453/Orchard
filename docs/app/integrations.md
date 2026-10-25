---
title: Integrations
summary: Show what you play in Discord, send your listens to Last.fm, add Spotify Canvas loops, connect Qobuz for MAX quality, connect Apple Music for browsing, Home and playback, and control Orchard from local apps through the Local API.
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
  - apple music videos
  - scan
  - local api
  - stream deck
  - api port
platforms:
  - desktop
order: 130
---

# Integrations

Integrations connect Orchard to other apps. Settings, Integrations has cards for **Discord**, **Last.fm**, **Spotify**, **Qobuz**, **Apple Music**, and **Local API**.

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

Pick **YouTube Music / Apple Music** or **Orchard V3** in the **Platform** list. Discord shows the selection as "Listening to" followed by the platform name. The default is **YouTube Music / Apple Music**, which shows YouTube Music or Apple Music depending on the service playing the current song.

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

The music source decides Home, search, artist, album and playlist pages on both platforms. Orchard never mixes the two services and never falls back to the other one. With **Apple** selected, Home shows Apple Music recommendations or charts, the sidebar search uses Apple Music, and an artist named on a YouTube Music song opens its Apple Music page. A YouTube Music album or playlist shows a message asking you to switch to YouTube Music; an Apple Music page shows the matching message under **Normal**. If Apple Music is disconnected or fails, the page shows an error instead of YouTube Music. Changing the source returns to Home on desktop. On desktop, the sidebar and Library show your Apple Music library. On Android, the **Library** tab and Home's **Listen now** grid and **Your playlists** show your Apple Music library: its songs, albums, artists and playlists. **Your daily discover** starts with Apple's personal mixes (such as Your Essentials, New Music, Chill and Heavy Rotation). Apple's personal stations, such as Discovery Station, are not shown because Orchard cannot play stations. Home's **Latest Songs** and **Recent** in the Library tab show your Apple Music listening history plus Apple Music songs you played in Orchard. Songs you played from YouTube Music are left out.

With **Apple** selected, the like button loves the song in Apple Music and saves it to your Apple Music library. **New playlist** under **Create** makes the playlist in your Apple Music library, and **Add to playlist** lists only Apple Music playlists you made. Songs from YouTube Music are matched to the same song on Apple Music first. Orchard cannot remove songs from your Apple Music library or delete Apple Music playlists, so un-loving a song keeps it in your library; remove it in the Apple Music app.

## Play Apple Music songs

Apple Music songs play from Apple Music when **Music source** is **Apple**. They are not matched to YouTube Music. Under **Normal**, an Apple Music song shows "Choose Apple as the music source under Settings, General to play Apple Music songs." Your YouTube Music session is still required to play YouTube Music songs.

Orchard streams Apple Music songs itself and decrypts them in a small separate process, `orchard-widevine-host`, which uses about 80 MB of memory while songs play. It starts with the first Apple Music song and quits two minutes after playback stops, or when you switch **Music source** to **Normal** or disconnect Apple Music. Decrypted audio stays in memory and is never written to disk.

- Platforms: Linux, Windows, and Android. On other systems, an Apple Music song shows "Apple Music playback is not available on this system yet."
- Android: the phone's own Widevine decrypts Apple Music songs inside Orchard's player, so no extra app or process is needed. Choose **Apple Music** under **Music source** first; otherwise an Apple Music song shows "Choose Apple Music as the music source in Settings to play Apple Music songs." Without a connected account it shows "Connect Apple Music in Settings to play this song." Apple Music songs play only on the phone, not on a Cast device or an Orchard Connect device ("Apple Music songs play only on this phone."). The stream cache keeps the encrypted file, so a cached song still needs a network connection for its license. Each source keeps its own cached copies, so changing **Streaming quality** or the Qobuz setting does not download cached Apple Music songs again. Best Mix sorts Apple Music songs from Apple's own analysis, as on desktop.
- Widevine: Apple Music audio is protected, so Linux needs the Widevine module from an installed Google Chrome, Chromium with Widevine, Brave, or Firefox. If none is found, the song shows "Apple Music playback needs Widevine. Install Google Chrome or Chromium with Widevine, then restart Orchard." Windows uses the Widevine module that Microsoft Edge downloads, or the one from Google Chrome, Chromium, Brave, or Firefox. If none is found, the song shows "Apple Music playback needs Widevine. Open Microsoft Edge or Google Chrome once so it downloads Widevine, then restart Orchard." Set `ORCHARD_WIDEVINE_PATH` to a `libwidevinecdm.so` or `widevinecdm.dll` file to use another copy.
- Quality: Apple Music plays 256 kbps AAC. The **Streaming quality** setting does not apply.
- Apple Music songs play through the same player as YouTube Music songs, so the Audio Engine, crossfade, gapless playback and Adaptive mix work as described in [Crossfade and gapless playback](crossfade.md). Adaptive mix analyses Apple's own audio.
- Autoplay and AI-music scans do not apply to Apple Music songs.
- Volume, play, pause, seek, next and previous work as usual, including from media keys and the system media controls.
- Errors: when Apple refuses a song, Orchard shows Apple's own reason, such as a missing subscription, or "Apple Music did not offer this song for playback." On Android, "Sign in to Apple Music again." means the saved sign-in expired; connect Apple Music again.

## Watch Apple Music videos

With **Music source** set to **Apple**, Apple Music videos (for example on an album's **Music Videos** shelf) play from Apple Music in the music video player. You hear the video's own soundtrack, also after you return to the regular player.

- Picture quality: up to 486p. Apple licenses its 576p, 720p and 1080p versions only to players that report protected (HDCP) video output, which a desktop Widevine module cannot. The quality menu lists the heights Orchard can play.
- Memory: `orchard-widevine-host` decodes the picture as well, a few seconds ahead of the playhead, and Orchard holds those frames. Expect about 100 MB more while the video player is open.
- Platforms: Linux and Windows. On Android, Apple Music videos do not play yet.

## Disconnect Apple Music

Select **Disconnect** in the **Apple Music** card in **Settings**, **Integrations** on desktop or **Settings**, **Connections**, **Apple Music** on Android. Orchard removes the saved Apple Music user token and clears Apple's sign-in cookies and MusicKit page storage. If **Music source** is **Apple** on desktop, Home shows "Connect Apple Music in Settings to load Home." On Android, Home shows an Apple Music connection error until you reconnect or choose YouTube Music.

## Turn the Local API on or off

The Local API lets apps on this computer, such as the Orchard Stream Deck plugin, control playback, the queue, and search. It is on by default and only accepts connections from this computer.

1. Open **Settings**, then **Integrations**.
2. In the **Local API** card, turn the **Local API** switch on or off.

The change applies right away. Turning it off disconnects connected apps.

## Change the Local API port

The Local API uses port 32145 for HTTP and the next port, 32146, for WebSocket.

1. Open **Settings**, then **Integrations**.
2. In the **Local API** card, type a port from 1024 to 65534 in **Port**.
3. Select **Apply** or press Enter.

Orchard reconnects on the new port right away. If another app is using that port or the next one, the card shows "Ports 32145 and 32146 are unavailable. Choose another port." with your port numbers. Pick a different port. Apps that read Orchard's `api.json` file find the new port automatically.
