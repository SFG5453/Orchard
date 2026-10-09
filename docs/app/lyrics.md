---
title: Lyrics
summary: Show lyrics for the current song, translate them to English, see where they come from, and handle missing lyrics.
group: Basics
icon: mic-vocal
keywords:
  - lyrics
  - synced lyrics
  - sing along
  - lyrics unavailable
  - lrclib
  - translate lyrics
  - translation
  - english lyrics
platforms:
  - desktop
order: 40
---

# Lyrics

Orchard shows lyrics for the song that is playing, in the queue panel and in the fullscreen player.

## Show lyrics in the queue panel

1. Select the **Queue** button in the player bar to open the queue panel.
2. Select the lyrics button in the panel header (accessible name "Show lyrics"). The header changes from "Queue" to "Lyrics".

Select the same button again ("Show queue") to go back to the queue.

## Show lyrics in the fullscreen player

1. Open the fullscreen player by clicking the cover art or pressing F.
2. Select the lyrics button. Its tooltip reads "Show lyrics" or "Hide lyrics".

## Tell synced lyrics from plain lyrics

The panel subtitle reads "Synced to the music" when the lyrics follow the song. It reads "Not time-synced" when the lyrics have no timing. Plain lyrics show the line "These lyrics aren't synced to the music."

## Lyrics during a crossfade

In the fullscreen player, the lyrics fade out halfway through a blend. Orchard looks up the next song's lyrics during the blend, and they appear when the blend ends.

## Find out where lyrics come from

The lyrics view shows "Lyrics from" followed by the source. The sources are am-lyrics, LRCLIB, and YouTube Music.

## Fix "Lyrics unavailable"

The lyrics view shows "Lyrics unavailable" when no source has lyrics for the song, or when the lookup failed.

1. Select **Try again**.
2. If lyrics still do not appear, the song most likely has no lyrics in any source.

## What "Play something to see its lyrics" means

The lyrics view shows "Play something to see its lyrics" when no song is loaded. Play a song and the lyrics load. The view shows "Loading lyrics" while it looks them up.

## Translate lyrics to English

Select the translate button (accessible name "Translate lyrics") at the top right of the lyrics view. Orchard detects the song's language and shows an English line above each lyric line. The original line stays below it in smaller text, and the word-by-word highlight keeps following the original words. Lines that are already in English, and lines in other languages, stay as they are.

Translation stays on until you select the button again ("Stop translating lyrics"). You can also turn it on or off in Settings > Downloads > Lyric translation with **Translate lyrics to English**.

**Local** is the default translation source. It runs on your computer. Lyrics are never sent anywhere, including lyrics for local files.

## Use an API for translation

In Settings > Downloads > Lyric translation, choose **OpenAI**, **Claude**, **Gemini**, or **Custom API** as the translation source. Enter a model ID offered by that provider, paste your API key, and select **Save**. Orchard stores the key in your operating system keychain. To revoke Orchard's copy, select **Remove**. A custom OpenAI compatible API needs a base URL; Orchard appends `/chat/completions` unless the URL already ends with that path. A keyless custom API can use HTTP; an API with a key needs HTTPS or localhost.

With an API selected, Orchard sends lyric lines to that service when translation is enabled and the lyrics are visible. This also applies to lyrics from local files. The service may charge for requests and process the lyrics under its own policies. Orchard caches translated lines for the selected model and endpoint. Switching back to **Local** stops API translation.

On Android, find the same choices in Settings > Appearance > Lyrics. API keys are encrypted with Android Keystore. The model and custom URL are saved separately from the key.

## Languages that can be translated

Local models translate Korean, Japanese, Chinese, Russian, Arabic, Greek, Turkish, French, German, Spanish, Italian, Dutch, and Catalan into English. For other languages, the lyrics view shows "[Language] lyrics can't be translated yet." An API model can handle other languages if that model supports them.

The translations come from small models. Use them to get the meaning of a line, not as an exact translation.

## Choose Standard or High translation quality

With **Local** selected, open Settings, select **Downloads**, and under **Lyric translation** pick **Standard** or **High** next to **Translation quality**.

- **Standard** uses small models: about 20 MB per language, a few seconds per song, and about 75 MB of memory while translating. Lines are rough.
- **High** uses larger models with noticeably better lines: about 85 MB per language, roughly 8 seconds per song, and about 300 MB of memory while translating.

Each quality downloads its own model, so switching to High downloads the High model the next time a song in that language is translated. Orchard frees the memory about a minute and a half after the last translation.

## What the translation status means

The label next to the translate button shows what Orchard is doing:

- "Downloading [Language] model" with a percentage: the first song in a new language downloads that language's model. Only the model for that language is downloaded.
- "Translating from [Language]": lines appear one by one, usually within a few seconds.
- "Translated from [Language]": every line that needs a translation has one.
- An error followed by **Try again**: select the label to retry.

Lines translated once are saved, so a song you play again shows its translation right away.

## Remove downloaded translation models

1. Open Settings and select **Downloads**.
2. Under **Lyric translation**, find the model, for example "Korean model" or "Korean model (High)", and its size.
3. Select **Remove**.

Orchard downloads the model again the next time a song in that language is translated.
