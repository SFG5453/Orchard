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

package dev.sfg.orchard.mobile.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.sfg.orchard.mobile.model.AudioQuality
import dev.sfg.orchard.mobile.model.OrchardSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray

private val Context.orchardDataStore: DataStore<Preferences> by preferencesDataStore(name = "orchard_settings")

/** Transactional settings and bounded search history backed by DataStore. */
class SettingsRepository(context: Context, private val scope: CoroutineScope) {
    private val store = context.applicationContext.orchardDataStore
    val settings: StateFlow<OrchardSettings> = store.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { values ->
            OrchardSettings(
                animatedArtwork = values[ANIMATED_ARTWORK] ?: true,
                audioQuality = runCatching { AudioQuality.valueOf(values[AUDIO_QUALITY].orEmpty()) }
                    .getOrDefault(AudioQuality.HIGH),
                useSystemColors = values[SYSTEM_COLORS] ?: false,
                animatedBackground = values[ANIMATED_BACKGROUND] ?: false,
                crossfadeEnabled = values[CROSSFADE_ENABLED] ?: false,
                crossfadeSeconds = values[CROSSFADE_SECONDS] ?: OrchardSettings.DEFAULT_CROSSFADE_SECONDS,
                smartCrossfade = values[SMART_CROSSFADE] ?: false,
                cacheSizeMb = values[CACHE_SIZE_MB] ?: OrchardSettings.DEFAULT_CACHE_SIZE_MB,
                onboardingCompleted = values[ONBOARDING_COMPLETED] ?: false,
                discordPresenceEnabled = values[DISCORD_PRESENCE_ENABLED] ?: true,
                discordAnimatedArtwork = values[DISCORD_ANIMATED_ARTWORK] ?: true,
                showBitrate = values[SHOW_BITRATE] ?: false,
                spotifySpdc = values[SPOTIFY_SPDC] ?: "",
                spotifyCanvasEnabled = values[SPOTIFY_CANVAS_ENABLED] ?: true,
                volumeNormalizationEnabled = values[VOLUME_NORMALIZATION_ENABLED] ?: false,
                autoplayEnabled = values[AUTOPLAY_ENABLED] ?: true,
            )
        }
        .stateIn(scope, SharingStarted.Eagerly, OrchardSettings())

    val searchHistory: StateFlow<List<String>> = store.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { decodeHistory(it[SEARCH_HISTORY].orEmpty()) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun updateSettings(value: OrchardSettings) {
        scope.launch {
            store.edit {
                it[ANIMATED_ARTWORK] = value.animatedArtwork
                it[AUDIO_QUALITY] = value.audioQuality.name
                it[SYSTEM_COLORS] = value.useSystemColors
                it[ANIMATED_BACKGROUND] = value.animatedBackground
                it[CROSSFADE_ENABLED] = value.crossfadeEnabled
                it[CROSSFADE_SECONDS] = value.crossfadeSeconds
                it[SMART_CROSSFADE] = value.smartCrossfade
                it[CACHE_SIZE_MB] = value.cacheSizeMb
                it[ONBOARDING_COMPLETED] = value.onboardingCompleted
                it[DISCORD_PRESENCE_ENABLED] = value.discordPresenceEnabled
                it[DISCORD_ANIMATED_ARTWORK] = value.discordAnimatedArtwork
                it[SHOW_BITRATE] = value.showBitrate
                it[SPOTIFY_SPDC] = value.spotifySpdc
                it[SPOTIFY_CANVAS_ENABLED] = value.spotifyCanvasEnabled
                it[VOLUME_NORMALIZATION_ENABLED] = value.volumeNormalizationEnabled
                it[AUTOPLAY_ENABLED] = value.autoplayEnabled
            }
        }
    }

    fun recordSearch(query: String) {
        val normalized = query.trim()
        if (normalized.isEmpty()) return
        scope.launch {
            store.edit { values ->
                val next = listOf(normalized) + decodeHistory(values[SEARCH_HISTORY].orEmpty())
                    .filterNot { it.equals(normalized, ignoreCase = true) }
                    .take(9)
                values[SEARCH_HISTORY] = JSONArray(next).toString()
            }
        }
    }

    fun clearSearchHistory() {
        scope.launch { store.edit { it.remove(SEARCH_HISTORY) } }
    }

    private fun decodeHistory(value: String): List<String> = runCatching {
        val values = JSONArray(value)
        buildList {
            for (index in 0 until values.length()) values.optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }.getOrDefault(emptyList())

    private companion object {
        val ANIMATED_ARTWORK = booleanPreferencesKey("animated_artwork")
        val AUDIO_QUALITY = stringPreferencesKey("audio_quality")
        val SYSTEM_COLORS = booleanPreferencesKey("system_colors")
        val ANIMATED_BACKGROUND = booleanPreferencesKey("animated_background")
        val CROSSFADE_ENABLED = booleanPreferencesKey("crossfade_enabled")
        val CROSSFADE_SECONDS = intPreferencesKey("crossfade_seconds")
        val SMART_CROSSFADE = booleanPreferencesKey("smart_crossfade")
        val CACHE_SIZE_MB = intPreferencesKey("cache_size_mb")
        val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val DISCORD_PRESENCE_ENABLED = booleanPreferencesKey("discord_presence_enabled")
        val DISCORD_ANIMATED_ARTWORK = booleanPreferencesKey("discord_animated_artwork")
        val SHOW_BITRATE = booleanPreferencesKey("show_bitrate")
        val SPOTIFY_SPDC = stringPreferencesKey("spotify_spdc")
        val SPOTIFY_CANVAS_ENABLED = booleanPreferencesKey("spotify_canvas_enabled")
        val VOLUME_NORMALIZATION_ENABLED = booleanPreferencesKey("volume_normalization_enabled")
        val AUTOPLAY_ENABLED = booleanPreferencesKey("autoplay_enabled")
        val SEARCH_HISTORY = stringPreferencesKey("search_history")
    }
}
