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

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.ui.graphics.vector.ImageVector

/** Pages under the settings hub. Home has no title bar blurb. */
internal enum class SettingsPage(val title: String, val blurb: String, val icon: ImageVector?) {
    Home("Settings", "", null),
    Audio("Audio & playback", "Quality, equalizer and transitions between songs.", Icons.Rounded.GraphicEq),
    Appearance("Appearance", "Artwork, backgrounds, player details and lyrics.", Icons.Rounded.Palette),
    Connections("Connections", "Connect Orchard to other apps.", Icons.Rounded.Link),
    Storage("Storage & updates", "Cache, updates and version.", Icons.Rounded.Storage),
}

/** One searchable setting; [keywords] catches words the title does not contain. */
internal data class SettingsEntry(
    val title: String,
    val subtitle: String,
    val page: SettingsPage,
    val keywords: String = "",
)

private val entries = listOf(
    SettingsEntry("Audio quality", "Uses the least data to highest bitrate", SettingsPage.Audio, "bitrate stream data saver"),
    SettingsEntry("Audio equalizer", "Shape the sound with presets or bands", SettingsPage.Audio, "eq bass treble"),
    SettingsEntry("Show audio bitrate", "Streaming bitrate under the scrubber", SettingsPage.Audio),
    SettingsEntry("Volume normalization", "Even out volume between songs", SettingsPage.Audio, "loudness replaygain"),
    SettingsEntry("Exponential volume", "Finer control at low volumes", SettingsPage.Audio, "quiet media buttons"),
    SettingsEntry("Autoplay", "Keep playing related music", SettingsPage.Audio, "queue radio"),
    SettingsEntry("Crossfade", "Blend the end of a track into the next", SettingsPage.Audio, "adaptive mix smart overlap transition"),
    SettingsEntry("Chromecast", "Cast to speakers and TVs", SettingsPage.Audio, "cast tv speaker"),
    SettingsEntry("Home screen layout", "Reorder and hide sections on Home", SettingsPage.Appearance, "sections"),
    SettingsEntry("Player gestures", "Swipe to skip and tap to like", SettingsPage.Appearance),
    SettingsEntry("Use system colours", "Match your wallpaper", SettingsPage.Appearance, "theme material you accent"),
    SettingsEntry("Animated artwork", "Move artwork while music plays", SettingsPage.Appearance, "motion cover"),
    SettingsEntry("Artwork source order", "Choose which animated artwork source is tried first", SettingsPage.Appearance, "mirror m8tec boidu spotify canvas"),
    SettingsEntry("Download animated artwork", "Save motion covers offline", SettingsPage.Appearance),
    SettingsEntry("Animated background", "Cover colours drift behind the app", SettingsPage.Appearance, "kawarp wallpaper"),
    SettingsEntry("Translate lyrics", "Show foreign lyrics in English", SettingsPage.Appearance, "translation language english korean japanese"),
    SettingsEntry("Translation quality", "Standard or High translation models", SettingsPage.Appearance, "lyrics model size"),
    SettingsEntry("Translation source", "Local, OpenAI, Claude, Gemini, or a custom API", SettingsPage.Appearance, "lyrics api provider"),
    SettingsEntry("Discord", "Share what you are listening to", SettingsPage.Connections, "presence"),
    SettingsEntry("Last.fm", "Now-playing updates and listens", SettingsPage.Connections, "scrobble"),
    SettingsEntry("ListenBrainz", "Send listens with a user token", SettingsPage.Connections, "scrobble"),
    SettingsEntry("Spotify", "Spotify Canvas animated artwork", SettingsPage.Connections, "canvas cookie"),
    SettingsEntry("Qobuz", "Lossless and Hi-Res audio", SettingsPage.Connections, "hires streaming"),
    SettingsEntry("Orchard Account", "Sign in with Google and manage devices", SettingsPage.Connections, "account devices google"),
    SettingsEntry("Save plays to YouTube Music history", "Add played songs to your YouTube Music history", SettingsPage.Audio, "listening history tracking"),
    SettingsEntry("Cached audio", "Cache limit for played tracks", SettingsPage.Storage, "size limit"),
    SettingsEntry("Clear cache", "Free temporary audio and artwork", SettingsPage.Storage, "storage delete"),
    SettingsEntry("Check for updates", "Look for newer releases", SettingsPage.Storage, "version upgrade"),
    SettingsEntry("Release notes", "What changed in this version", SettingsPage.Storage, "changelog"),
    SettingsEntry("Beta channel", "Get beta builds from GitHub", SettingsPage.Storage),
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
