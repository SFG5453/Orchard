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
  - apple music station
  - radio
  - home source
  - recommendations
  - widevine
  - memory
  - adaptive mix apple music
  - apple music videos
  - song video
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

Orchard does not include a Qobuz app. You need your own Qobuz API app ID and app secret from Qobuz, and a Qobuz subscription. You also need a user ID and auth token that Qobuz issued to that same app. Use the sign-in that Qobuz gives your app to get them.

1. Open **Settings**, then **Integrations**.
2. In the **Qobuz** card, select **Connect Qobuz**.
3. Enter the **App ID** and **App secret**.
4. Enter the **User ID** and the auth token, then select **Sign in**.

Orchard checks all four with Qobuz before saving them. When the check passes, the card says "Qobuz is connected, and streaming quality is now MAX." Select **Cancel** to stop. Orchard stores the sign-in in the system keychain. Treat the app secret and the token like passwords.

Orchard signs each stream request with your app secret and plays the FLAC file that the Qobuz API (`track/getFileUrl`) returns. See [Streaming quality](streaming-quality.md) for what MAX does.

Errors:
- "Qobuz rejected this user ID and token": the token expired, the user ID is wrong, or the token belongs to another app.
- "Qobuz rejected the app ID": the app ID is not valid.
- "Qobuz rejected the app secret": the secret does not match the app ID.
- "Qobuz returned a preview clip": the account cannot stream that track in full.

A Qobuz sign-in saved by an older Orchard without an app ID and secret does not restore. Connect again with your app.

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

The music source decides Home, search, artist, album and playlist pages on both platforms. Orchard never mixes the two services and never falls back to the other one. With **Apple** selected, Home shows Apple Music recommendations or charts, the sidebar search uses Apple Music, and an artist named on a YouTube Music song opens its Apple Music page. A YouTube Music album or playlist shows a message asking you to switch to YouTube Music; an Apple Music page shows the matching message under **Normal**. If Apple Music is disconnected or fails, the page shows an error instead of YouTube Music. Changing the source returns to Home on desktop. On desktop, the sidebar and Library show your Apple Music library. On Android, the **Library** tab and Home's **Listen now** grid and **Your playlists** show your Apple Music library: its songs, albums, artists and playlists. **Your daily discover** starts with Apple's personal mixes (such as Your Essentials, New Music, Chill and Heavy Rotation). Apple's stations, such as Discovery Station, appear as cards. See [Play Apple Music stations](integrations.md#play-apple-music-stations). Home's **Latest Songs** and **Recent** in the Library tab show your Apple Music listening history plus Apple Music songs you played in Orchard. Songs you played from YouTube Music are left out.

With **Apple** selected, the like button loves the song in Apple Music and saves it to your Apple Music library. **New playlist** under **Create** makes the playlist in your Apple Music library, and **Add to playlist** lists only Apple Music playlists you made. Songs from YouTube Music are matched to the same song on Apple Music first. Orchard cannot remove songs from your Apple Music library or delete Apple Music playlists, so un-loving a song keeps it in your library; remove it in the Apple Music app.

## Play Apple Music songs

Apple Music songs play from Apple Music when **Music source** is **Apple**. They are not matched to YouTube Music. Under **Normal**, an Apple Music song shows "Choose Apple as the music source under Settings, General to play Apple Music songs." Your YouTube Music session is still required to play YouTube Music songs.

Orchard streams Apple Music songs itself and decrypts them in a small separate process, `orchard-widevine-host`, which uses about 80 MB of memory while songs play. It starts with the first Apple Music song and quits two minutes after playback stops, or when you switch **Music source** to **Normal** or disconnect Apple Music. Decrypted audio stays in memory and is never written to disk.

- Platforms: Linux, Windows, and Android. On other systems, an Apple Music song shows "Apple Music playback is not available on this system yet."
- Android: the phone's own Widevine decrypts Apple Music songs inside Orchard's player, so no extra app or process is needed. Choose **Apple Music** under **Music source** first; otherwise an Apple Music song shows "Choose Apple Music as the music source in Settings to play Apple Music songs." Without a connected account it shows "Connect Apple Music in Settings to play this song." Apple Music songs do not play on a Cast device. They play on an Orchard Connect computer that is connected to Apple Music; otherwise the phone shows "Sign in to Apple Music on the other device to play Apple Music songs there." See [Orchard Connect](connect.md). The stream cache keeps encrypted stereo files, so a cached stereo song still needs a network connection for its license. Atmos streams are not cached. Each source keeps its own cached copies, so changing **Streaming quality** or connecting Qobuz does not download cached Apple Music songs again. Best Mix sorts Apple Music songs from Apple's own analysis, as on desktop.
- Android Atmos: choose **Atmos** under **Wi-Fi quality** or **Mobile data quality** in **Settings → Audio & playback**. The choice appears when Apple Music is selected and connected and the phone has a compatible decoder. It applies to the next song. Orchard selects Atmos when Apple advertises it; other songs play in stereo. If Atmos playback fails, Orchard continues in stereo. Apple Music crossfade is off while Atmos is selected for the active network. Atmos output depends on the phone and the connected speakers or headphones.
- Widevine: Apple Music audio is protected, so Linux needs the Widevine module from an installed Google Chrome, Chromium with Widevine, Brave, or Firefox. If none is found, the song shows "Apple Music playback needs Widevine. Install Google Chrome or Chromium with Widevine, then restart Orchard." Windows uses the Widevine module that Microsoft Edge downloads, or the one from Google Chrome, Chromium, Brave, or Firefox. If none is found, the song shows "Apple Music playback needs Widevine. Open Microsoft Edge or Google Chrome once so it downloads Widevine, then restart Orchard." Set `ORCHARD_WIDEVINE_PATH` to a `libwidevinecdm.so` or `widevinecdm.dll` file to use another copy.
- Quality: Apple Music plays 256 kbps AAC. With **Streaming quality** set to **Atmos**, songs that have a Dolby Atmos version play that version, rendered for headphones. See [Streaming quality](streaming-quality.md). Other settings do not change Apple Music songs.
- Apple Music songs play through the same player as YouTube Music songs, so the Audio Engine, crossfade, gapless playback and Adaptive mix work as described in [Crossfade and gapless playback](crossfade.md). Adaptive mix analyses Apple's own audio.
- Autoplay continues an Apple Music queue with songs from the last song's Apple Music station. **Start mix** on an Apple Music song uses that song's station, and it does not need a YouTube Music sign-in.
- AI-music scans do not apply to Apple Music songs.
- Volume, play, pause, seek, next and previous work as usual, including from media keys and the system media controls.
- Errors: when Apple refuses a song, Orchard shows Apple's own reason, such as a missing subscription, or "Apple Music did not offer this song for playback." On Android, "Sign in to Apple Music again." means the saved sign-in expired; connect Apple Music again.

## Play Apple Music stations

With **Music source** set to **Apple**, Apple Music stations appear as cards on Home and in the **Station** shelf at the end of an artist page.

1. Select a station card. Its page lists the station's next songs.
2. Select **Play**, or select a song in the list.

The queue keeps adding songs from the station, ten at a time, while it plays. This works with Autoplay off. Playing another song, album or playlist ends the station.

- Live radio, such as Apple Music 1, is not shown. Orchard cannot play live streams.
- On Android, the station only continues on the phone. Apple Music songs do not play on a Cast device.

## Watch Apple Music videos

With **Music source** set to **Apple**, Apple Music videos (for example on an album's **Music Videos** shelf) play from Apple Music in the music video player. You hear the video's own soundtrack, also after you return to the regular player.

An Apple Music song's video button also plays Apple's own video of that song, when Apple has one. When Apple has no video for the song, Orchard looks for a matching YouTube video instead. You hear the video's soundtrack while the video plays, and the song's audio again when you go back to the song.

- Picture quality on Linux: up to 486p. Apple licenses its 576p, 720p and 1080p versions only to players that report protected (HDCP) video output, and Linux cannot report it.
- Picture quality on Windows: up to 1080p when every connected display has HDCP (most HDMI and DisplayPort monitors, and built-in laptop screens). Orchard asks Windows to turn HDCP on. If a display lacks it, Orchard plays the 486p version for the rest of the session. Screens shared over Miracast count as unprotected.
- The quality menu lists the heights Orchard can play.
- Memory: `orchard-widevine-host` decodes the picture as well, a few seconds ahead of the playhead, and Orchard holds those frames. Expect about 100 MB more while the video player is open at 486p, and several hundred MB more at 1080p.
- Picture quality on Android: up to 1080p on phones with hardware-protected playback (Widevine L1), which most phones have. Other phones play up to 486p.
- Platforms: Linux, Windows and Android. On Android, videos play on the phone only.

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
