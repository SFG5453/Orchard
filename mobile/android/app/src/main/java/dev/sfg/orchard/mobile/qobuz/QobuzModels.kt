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

package dev.sfg.orchard.mobile.qobuz

enum class QobuzQuality(val id: String, val label: String, val formatId: Int) {
    AUTO("auto", "Best available", 27),
    LOSSLESS("lossless", "CD lossless", 6),
    HIRES("hires", "Hi-Res", 27);

    companion object {
        fun fromId(id: String?): QobuzQuality =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: AUTO
    }
}

data class QobuzSession(
    val token: String,
    val userId: Long,
)

data class QobuzStatus(
    val status: String = "disconnected",
    val enabled: Boolean = false,
    val quality: QobuzQuality = QobuzQuality.AUTO,
    val lastError: String = "",
) {
    val isConnected: Boolean get() = status == "connected"
}

fun formatPlaybackQualityLabel(
    playbackSource: String?,
    hires: Boolean,
    bitDepth: Int?,
    sampleRate: Int?,
): String {
    if (playbackSource != "qobuz") return ""
    val depth = bitDepth ?: 0
    val rawRate = (sampleRate ?: 0).toDouble()
    val rateKhz = if (rawRate >= 1000) rawRate / 1000.0 else rawRate
    val tier = if (hires || depth > 16 || rateKhz > 48.0) "Qobuz Hi-Res" else "Qobuz Lossless"
    val parts = listOfNotNull(
        if (depth > 0) "$depth-bit" else null,
        if (rateKhz > 0) "${if (rateKhz % 1.0 == 0.0) rateKhz.toInt().toString() else "%.1f".format(rateKhz)} kHz" else null,
    )
    val format = parts.joinToString(" / ")
    return if (format.isNotBlank()) "$tier · $format" else tier
}
