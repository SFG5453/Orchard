/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 *
 * Orchard is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR
 * A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.sfg.orchard.mobile.ui.screens

import dev.sfg.orchard.mobile.i18n.tr

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Waves
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.ui.graphics.vector.ImageVector

/** Pages under the settings hub. Home has no title bar blurb. */
internal enum class SettingsPage(val title: String, val blurb: String, val icon: ImageVector?) {
    Home("Settings", "", null),
    Playback("Playback", "Quality, sound, queue and casting.", Icons.Rounded.GraphicEq),
    Mixing("Mixing & skipping", "Crossfade, talking intros and AI-generated songs.", Icons.Rounded.Waves),
    Appearance("Appearance", "Colours, background, Home and the player.", Icons.Rounded.Palette),
    Artwork("Artwork", "Animated covers, quality and sources.", Icons.Rounded.Image),
    Lyrics("Lyrics", "Motion, providers and translation.", Icons.Rounded.Lyrics),
    Connections("Connections", "Connect Orchard to other apps.", Icons.Rounded.Link),
    Storage("Storage", "Cache and downloads.", Icons.Rounded.Storage),
    About("About", "Version and updates.", Icons.Rounded.Info),
}

/** One searchable setting; [keywords] catches words the title does not contain. */
internal data class SettingsEntry(
    val title: String,
    val subtitle: String,
    val page: SettingsPage,
    val keywords: String = "",
)

private val entries = listOf(
    SettingsEntry(tr("Audio quality"), "Uses the least data to highest bitrate", SettingsPage.Playback, "bitrate stream data saver"),
    SettingsEntry(tr("Audio equalizer"), "Shape the sound with presets or bands", SettingsPage.Playback, "eq bass treble"),
    SettingsEntry(tr("Show audio bitrate"), "Streaming bitrate under the scrubber", SettingsPage.Playback),
    SettingsEntry(tr("Volume normalization"), "Even out volume between songs", SettingsPage.Playback, "loudness replaygain"),
    SettingsEntry(tr("Exponential volume"), "Finer control at low volumes", SettingsPage.Playback, "quiet media buttons"),
    SettingsEntry(tr("Autoplay"), "Keep playing related music", SettingsPage.Playback, "queue radio"),
    SettingsEntry(tr("Crossfade"), "Blend the end of a track into the next", SettingsPage.Mixing, "adaptive mix smart overlap transition"),
    SettingsEntry(tr("Chromecast"), "Cast to speakers and TVs", SettingsPage.Playback, "cast tv speaker"),
    SettingsEntry(tr("Home screen layout"), "Reorder and hide sections on Home", SettingsPage.Appearance, "sections home"),
    SettingsEntry(tr("Player gestures"), "Swipe to skip and tap to like", SettingsPage.Appearance),
    SettingsEntry(tr("Use system colours"), "Match your wallpaper", SettingsPage.Appearance, "theme material you accent"),
    SettingsEntry(tr("Animated artwork"), "Move artwork while music plays", SettingsPage.Artwork, "motion cover"),
    SettingsEntry(tr("Artwork source order"), "Choose which animated artwork source is tried first", SettingsPage.Artwork, "mirror m8tec boidu spotify canvas"),
    SettingsEntry(tr("Download animated artwork"), "Save motion covers offline", SettingsPage.Artwork),
    SettingsEntry(tr("Animated background"), "Cover colours drift behind the app", SettingsPage.Appearance, "kawarp wallpaper"),
    SettingsEntry(tr("Blur distant lines"), "Blur lyrics further from the current line", SettingsPage.Lyrics, "lyrics focus motion"),
    SettingsEntry(tr("Fade distant lines"), "Dim lyrics further from the current line", SettingsPage.Lyrics, "lyrics opacity motion"),
    SettingsEntry(tr("Staggered scrolling"), "Lyric lines glide into place one after another", SettingsPage.Lyrics, "lyrics scroll animation cascade"),
    SettingsEntry(tr("Translate lyrics"), "Show foreign lyrics in English", SettingsPage.Lyrics, "translation language english korean japanese"),
    SettingsEntry(tr("Lyrics providers"), "Enable sources and change their priority", SettingsPage.Lyrics, "lyrics source order betterlyrics bini lyricsplus lrclib youtube"),
    SettingsEntry(tr("Translation quality"), "Standard or High translation models", SettingsPage.Lyrics, "lyrics model size"),
    SettingsEntry(tr("Translation source"), "Local, OpenAI, Claude, Gemini, or a custom API", SettingsPage.Lyrics, "lyrics api provider"),
    SettingsEntry(tr("Discord"), "Share what you are listening to", SettingsPage.Connections, "presence"),
    SettingsEntry(tr("Last.fm"), "Now-playing updates and listens", SettingsPage.Connections, "scrobble"),
    SettingsEntry("ListenBrainz", "Send listens with a user token", SettingsPage.Connections, "scrobble"),
    SettingsEntry(tr("Spotify"), "Spotify Canvas animated artwork", SettingsPage.Connections, "canvas cookie"),
    SettingsEntry(tr("Qobuz"), "Lossless and Hi-Res audio", SettingsPage.Connections, "hires streaming"),
    SettingsEntry(tr("Orchard Account"), "Sign in with Google and manage devices", SettingsPage.Connections, "account devices google"),
    SettingsEntry(tr("YouTube Music language"), "Choose the language for song titles and catalog information", SettingsPage.Playback, "system korean metadata"),
    SettingsEntry(tr("Save plays to YouTube Music history"), "Add played songs to your YouTube Music history", SettingsPage.Playback, "listening history tracking"),
    SettingsEntry(tr("Save plays to Apple Music history"), "Add played songs to your Apple Music history", SettingsPage.Playback, "listening history tracking recently played"),
    SettingsEntry(tr("Cached audio"), "Cache limit for played tracks", SettingsPage.Storage, "size limit"),
    SettingsEntry(tr("Clear cache"), "Free temporary audio and artwork", SettingsPage.Storage, "storage delete"),
    SettingsEntry(tr("Check for updates"), "Look for newer releases", SettingsPage.About, "version upgrade"),
    SettingsEntry(tr("Release notes"), "What changed in this version", SettingsPage.About, "changelog"),
    SettingsEntry(tr("Beta channel"), "Get beta builds from GitHub", SettingsPage.About),
    SettingsEntry(tr("Skip non-music parts"), "Talking intros, skits and applause", SettingsPage.Mixing, "sponsorblock intro skit"),
    SettingsEntry(tr("AI-generated music"), "Label or skip AI songs", SettingsPage.Mixing, "slop ai"),
    SettingsEntry(tr("Downloads on SD card"), "Store downloads on removable storage", SettingsPage.Storage, "sd card offline"),
    SettingsEntry(tr("Apple Music"), "Connect your Apple Music subscription", SettingsPage.Connections, "apple musickit account sign in"),
)

/** Case-insensitive match on every word of [query] against title, subtitle and keywords. */
internal fun searchSettings(query: String): List<SettingsEntry> {
    val words = query.trim().lowercase().split(' ').filter { it.isNotEmpty() }
    if (words.isEmpty()) return emptyList()
    return entries.filter { entry ->
        val haystack = "${entry.title} ${entry.subtitle} ${entry.keywords}".lowercase()
        words.all { it in haystack }
    }
}
