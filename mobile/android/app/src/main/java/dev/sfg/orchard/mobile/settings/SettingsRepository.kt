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
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.sfg.orchard.mobile.model.AudioQuality
import dev.sfg.orchard.mobile.model.EqualizerConfig
import dev.sfg.orchard.mobile.model.OrchardSettings
import dev.sfg.orchard.mobile.model.BuiltInHomeSection
import dev.sfg.orchard.mobile.model.HomeSectionConfig
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
                frostedGlass = values[FROSTED_GLASS] ?: false,
                crossfadeEnabled = values[CROSSFADE_ENABLED] ?: false,
                crossfadeSeconds = values[CROSSFADE_SECONDS] ?: OrchardSettings.DEFAULT_CROSSFADE_SECONDS,
                smartCrossfade = values[SMART_CROSSFADE] ?: false,
                bestMixSupabaseSync = values[BEST_MIX_SUPABASE_SYNC] ?: false,
                cacheSizeMb = values[CACHE_SIZE_MB] ?: OrchardSettings.DEFAULT_CACHE_SIZE_MB,
                onboardingCompleted = values[ONBOARDING_COMPLETED] ?: false,
                discordPresenceEnabled = values[DISCORD_PRESENCE_ENABLED] ?: true,
                discordAnimatedArtwork = values[DISCORD_ANIMATED_ARTWORK] ?: true,
                showBitrate = values[SHOW_BITRATE] ?: false,
                spotifySpdc = values[SPOTIFY_SPDC] ?: "",
                spotifyCanvasEnabled = values[SPOTIFY_CANVAS_ENABLED] ?: true,
                volumeNormalizationEnabled = values[VOLUME_NORMALIZATION_ENABLED] ?: false,
                autoplayEnabled = values[AUTOPLAY_ENABLED] ?: true,
                equalizerConfig = EqualizerConfig(
                    enabled = values[EQUALIZER_ENABLED] ?: false,
                    presetId = values[EQUALIZER_PRESET] ?: "flat",
                    gains = decodeFloatList(values[EQUALIZER_GAINS].orEmpty(), 10),
                    preampDb = values[EQUALIZER_PREAMP] ?: 0f,
                    bassBoost = values[EQUALIZER_BASS_BOOST] ?: 0f,
                ),
                playerGesturesEnabled = values[PLAYER_GESTURES_ENABLED] ?: true,
                homeLayoutOnline = decodeHomeLayout(values[HOME_LAYOUT_ONLINE], true),
                homeLayoutOffline = decodeHomeLayout(values[HOME_LAYOUT_OFFLINE], false),
                customDeviceName = values[CUSTOM_DEVICE_NAME] ?: "",
                betaChannelEnabled = values[BETA_CHANNEL_ENABLED] ?: false,
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
                it[FROSTED_GLASS] = value.frostedGlass
                it[CROSSFADE_ENABLED] = value.crossfadeEnabled
                it[CROSSFADE_SECONDS] = value.crossfadeSeconds
                it[SMART_CROSSFADE] = value.smartCrossfade
                it[BEST_MIX_SUPABASE_SYNC] = value.bestMixSupabaseSync
                it[CACHE_SIZE_MB] = value.cacheSizeMb
                it[ONBOARDING_COMPLETED] = value.onboardingCompleted
                it[DISCORD_PRESENCE_ENABLED] = value.discordPresenceEnabled
                it[DISCORD_ANIMATED_ARTWORK] = value.discordAnimatedArtwork
                it[SHOW_BITRATE] = value.showBitrate
                it[SPOTIFY_SPDC] = value.spotifySpdc
                it[SPOTIFY_CANVAS_ENABLED] = value.spotifyCanvasEnabled
                it[VOLUME_NORMALIZATION_ENABLED] = value.volumeNormalizationEnabled
                it[AUTOPLAY_ENABLED] = value.autoplayEnabled
                it[EQUALIZER_ENABLED] = value.equalizerConfig.enabled
                it[EQUALIZER_PRESET] = value.equalizerConfig.presetId
                it[EQUALIZER_GAINS] = value.equalizerConfig.gains.joinToString(",")
                it[EQUALIZER_PREAMP] = value.equalizerConfig.preampDb
                it[EQUALIZER_BASS_BOOST] = value.equalizerConfig.bassBoost
                it[PLAYER_GESTURES_ENABLED] = value.playerGesturesEnabled
                it[HOME_LAYOUT_ONLINE] = encodeHomeLayout(value.homeLayoutOnline)
                it[HOME_LAYOUT_OFFLINE] = encodeHomeLayout(value.homeLayoutOffline)
                it[CUSTOM_DEVICE_NAME] = value.customDeviceName
                it[BETA_CHANNEL_ENABLED] = value.betaChannelEnabled
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

    fun removeSearchHistoryItem(query: String) {
        val normalized = query.trim()
        if (normalized.isEmpty()) return
        scope.launch {
            store.edit { values ->
                val next = decodeSearchHistory(values[SEARCH_HISTORY].orEmpty())
                    .filterNot { it.equals(normalized, ignoreCase = true) }
                values[SEARCH_HISTORY] = JSONArray(next).toString()
            }
        }
    }

    private fun decodeHistory(value: String): List<String> = decodeSearchHistory(value)

    private fun decodeFloatList(value: String, expectedSize: Int): List<Float> {
        if (value.isBlank()) return List(expectedSize) { 0f }
        val parts = value.split(",")
        return List(expectedSize) { i -> parts.getOrNull(i)?.toFloatOrNull() ?: 0f }
    }

    private fun decodeHomeLayout(value: String?, online: Boolean): List<HomeSectionConfig> {
        if (value.isNullOrBlank()) {
            return if (online) OrchardSettings().homeLayoutOnline else OrchardSettings().homeLayoutOffline
        }
        return runCatching {
            val arr = JSONArray(value)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val sectionStr = obj.optString("section")
                    val enabled = obj.optBoolean("enabled", true)
                    runCatching { BuiltInHomeSection.valueOf(sectionStr) }.getOrNull()?.let { section ->
                        add(HomeSectionConfig(section, enabled))
                    }
                }
            }
        }.getOrDefault(if (online) OrchardSettings().homeLayoutOnline else OrchardSettings().homeLayoutOffline)
    }

    private fun encodeHomeLayout(layout: List<HomeSectionConfig>): String {
        val arr = JSONArray()
        layout.forEach { config ->
            val obj = org.json.JSONObject()
            obj.put("section", config.section.name)
            obj.put("enabled", config.enabled)
            arr.put(obj)
        }
        return arr.toString()
    }


    private companion object {
        val ANIMATED_ARTWORK = booleanPreferencesKey("animated_artwork")
        val AUDIO_QUALITY = stringPreferencesKey("audio_quality")
        val SYSTEM_COLORS = booleanPreferencesKey("system_colors")
        val ANIMATED_BACKGROUND = booleanPreferencesKey("animated_background")
        val FROSTED_GLASS = booleanPreferencesKey("frosted_glass")
        val CROSSFADE_ENABLED = booleanPreferencesKey("crossfade_enabled")
        val CROSSFADE_SECONDS = intPreferencesKey("crossfade_seconds")
        val SMART_CROSSFADE = booleanPreferencesKey("smart_crossfade")
        val BEST_MIX_SUPABASE_SYNC = booleanPreferencesKey("best_mix_supabase_sync")
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
        val EQUALIZER_ENABLED = booleanPreferencesKey("equalizer_enabled")
        val EQUALIZER_PRESET = stringPreferencesKey("equalizer_preset")
        val EQUALIZER_GAINS = stringPreferencesKey("equalizer_gains")
        val EQUALIZER_PREAMP = floatPreferencesKey("equalizer_preamp")
        val EQUALIZER_BASS_BOOST = floatPreferencesKey("equalizer_bass_boost")
        val PLAYER_GESTURES_ENABLED = booleanPreferencesKey("player_gestures_enabled")
        val HOME_LAYOUT_ONLINE = stringPreferencesKey("home_layout_online")
        val HOME_LAYOUT_OFFLINE = stringPreferencesKey("home_layout_offline")
        val CUSTOM_DEVICE_NAME = stringPreferencesKey("custom_device_name")
        val BETA_CHANNEL_ENABLED = booleanPreferencesKey("beta_channel_enabled")
    }
}

internal fun decodeSearchHistory(value: String): List<String> = runCatching {
    val values = JSONArray(value)
    buildList {
        for (index in 0 until values.length()) values.optString(index).takeIf(String::isNotBlank)?.let(::add)
    }
}.getOrDefault(emptyList())
