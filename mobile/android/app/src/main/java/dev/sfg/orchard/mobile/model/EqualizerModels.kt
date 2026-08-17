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

package dev.sfg.orchard.mobile.model

import kotlin.math.max
import kotlin.math.min

/** Frequency band definition for the 10-band equalizer. */
data class EqualizerBand(
    val index: Int,
    val frequencyHz: Int,
    val label: String,
)

/** Standard 10 ISO octave center frequencies matching Desktop Orchard. */
val EQ_BANDS: List<EqualizerBand> = listOf(
    EqualizerBand(0, 31, "31"),
    EqualizerBand(1, 62, "62"),
    EqualizerBand(2, 125, "125"),
    EqualizerBand(3, 250, "250"),
    EqualizerBand(4, 500, "500"),
    EqualizerBand(5, 1000, "1k"),
    EqualizerBand(6, 2000, "2k"),
    EqualizerBand(7, 4000, "4k"),
    EqualizerBand(8, 8000, "8k"),
    EqualizerBand(9, 16000, "16k"),
)

/** Equalizer preset with 10 band gains in dB (-12 dB to +12 dB). */
data class EqualizerPreset(
    val id: String,
    val label: String,
    val gains: List<Float>,
)

val EQ_PRESETS: List<EqualizerPreset> = listOf(
    EqualizerPreset("flat", "Flat", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
    EqualizerPreset("bass_boost", "Bass Boost", listOf(6f, 5f, 4f, 2f, 0f, -1f, -1f, 0f, 1f, 2f)),
    EqualizerPreset("electronic", "Electronic", listOf(5f, 4f, 1f, 0f, -2f, 1f, 2f, 3f, 4f, 4f)),
    EqualizerPreset("rock", "Rock", listOf(4f, 3f, 2f, 0f, -1f, 1f, 3f, 4f, 4f, 3f)),
    EqualizerPreset("vocal", "Vocal", listOf(-3f, -2f, -1f, 0f, 2f, 4f, 5f, 3f, 1f, 0f)),
    EqualizerPreset("acoustic", "Acoustic", listOf(2f, 2f, 1f, 0f, 2f, 3f, 3f, 2f, 2f, 1f)),
    EqualizerPreset("bright", "Bright", listOf(-2f, -1f, 0f, 0f, 1f, 2f, 3f, 4f, 5f, 5f)),
    EqualizerPreset("hiphop", "Hip-Hop", listOf(5f, 4f, 2f, 1f, -1f, -1f, 1f, 2f, 3f, 3f)),
    EqualizerPreset("pop", "Pop", listOf(-1f, 1f, 3f, 4f, 3f, 1f, -1f, 1f, 3f, 4f)),
    EqualizerPreset("classical", "Classical", listOf(4f, 3f, 2f, 1f, -1f, -1f, 0f, 2f, 3f, 4f)),
    EqualizerPreset("rnb", "R&B", listOf(5f, 6f, 3f, 1f, -1f, 0f, 2f, 2f, 3f, 4f)),
)

/** Complete configuration for the 10-band equalizer. */
data class EqualizerConfig(
    val enabled: Boolean = false,
    val presetId: String = "flat",
    val gains: List<Float> = List(10) { 0f },
    val preampDb: Float = 0f,
    val bassBoost: Float = 0f,
) {
    /** Clamped band gains within -12 dB to +12 dB. */
    val clampedGains: List<Float>
        get() = (0 until 10).map { index ->
            val gain = gains.getOrNull(index) ?: 0f
            max(-12f, min(12f, gain))
        }

    /** Clamped preamp gain within -12 dB to +6 dB. */
    val clampedPreampDb: Float
        get() = max(-12f, min(6f, preampDb))

    /** Clamped bass boost between 0.0 (off) and 1.0 (max). */
    val clampedBassBoost: Float
        get() = max(0f, min(1f, bassBoost))

    companion object {
        const val MIN_GAIN_DB = -12f
        const val MAX_GAIN_DB = 12f
        const val MIN_PREAMP_DB = -12f
        const val MAX_PREAMP_DB = 6f
    }
}
