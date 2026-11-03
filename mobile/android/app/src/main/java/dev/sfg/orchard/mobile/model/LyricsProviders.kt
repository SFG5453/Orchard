/*
 * Copyright (C) 2026 SFG545
 * SPDX-License-Identifier: AGPL-3.0-or-later
 */

package dev.sfg.orchard.mobile.model

enum class LyricsProvider(val key: String, val label: String) {
    APPLE("apple", "Apple Music"),
    BINI("bini", "Bini Lyrics"),
    BETTER_LYRICS("betterlyrics", "BetterLyrics"),
    LYRICS_PLUS("lyricsplus", "LyricsPlus"),
    LRCLIB("lrclib", "LRCLIB"),
    YOUTUBE("youtube", "YouTube Music"),
}

data class LyricsProviderConfig(val provider: LyricsProvider, val enabled: Boolean = true)

fun normalizeLyricsProviders(values: List<LyricsProviderConfig>): List<LyricsProviderConfig> {
    val saved = values.distinctBy { it.provider }
    val missing = LyricsProvider.entries.filterNot { provider -> saved.any { it.provider == provider } }
    val apple = missing.filter { it == LyricsProvider.APPLE }.map(::LyricsProviderConfig)
    return apple + saved + missing.filter { it != LyricsProvider.APPLE }.map(::LyricsProviderConfig)
}

fun visibleLyricsProviders(
    values: List<LyricsProviderConfig>,
    appleMusicConnected: Boolean,
): List<LyricsProviderConfig> = normalizeLyricsProviders(values).filter {
    when (it.provider) {
        LyricsProvider.APPLE -> appleMusicConnected
        LyricsProvider.BINI, LyricsProvider.BETTER_LYRICS, LyricsProvider.LYRICS_PLUS -> !appleMusicConnected
        else -> true
    }
}
