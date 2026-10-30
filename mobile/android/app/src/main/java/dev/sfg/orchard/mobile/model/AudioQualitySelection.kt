/* Copyright (C) 2026 SFG545. Licensed under the GNU Affero General Public License v3 or later. */
package dev.sfg.orchard.mobile.model

internal fun atmosQualityAvailable(source: String, signedIn: Boolean, decoderAvailable: Boolean): Boolean =
    source == APPLE_MUSIC_SOURCE && signedIn && decoderAvailable

internal fun visibleAudioQuality(quality: AudioQuality, atmosAvailable: Boolean): AudioQuality =
    if (quality == AudioQuality.ATMOS && !atmosAvailable) AudioQuality.HIGH else quality

internal fun audioQualityOptions(atmosAvailable: Boolean): List<AudioQuality> =
    if (atmosAvailable) AudioQuality.entries else AudioQuality.entries.filterNot { it == AudioQuality.ATMOS }

internal fun storedAudioQuality(value: String?, legacyAtmos: Boolean): AudioQuality =
    if (legacyAtmos) AudioQuality.ATMOS else AudioQuality.entries.firstOrNull { it.name == value } ?: AudioQuality.HIGH

internal fun youtubeAudioQuality(quality: AudioQuality): AudioQuality =
    if (quality == AudioQuality.ATMOS) AudioQuality.HIGH else quality

internal fun OrchardSettings.audioQualityForNetwork(metered: Boolean): AudioQuality =
    if (metered) cellularAudioQuality else audioQuality
