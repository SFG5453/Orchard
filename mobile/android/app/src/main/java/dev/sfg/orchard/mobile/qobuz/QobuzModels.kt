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

const val QOBUZ_BASE_URL = "https://www.qobuz.com/api.json/0.2"
const val QOBUZ_PLAY_URL = "https://play.qobuz.com"
const val QOBUZ_USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"

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

data class QobuzBootstrap(
    val appId: String,
    val oauthPrivateKey: String,
    val rngInit: String,
    val bundlePath: String = "",
)

data class QobuzMatch(
    val qobuzTrackId: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationSeconds: Int,
    val isrc: String = "",
    val explicit: Boolean = false,
    val hires: Boolean = false,
    val bitDepth: Int? = null,
    val sampleRate: Int? = null,
    val method: String = "search",
    val confidence: Double = 1.0,
)

data class QobuzStreamSource(
    val playbackId: String,
    val trackId: Long,
    val quality: QobuzQuality,
    val formatId: Int,
    val durationSeconds: Int,
    val blob: String,
    val trackContextUuid: String,
    val urlTemplate: String,
    val contentKey: ByteArray?,
    val expiresAtMs: Long,
    val bitDepth: Int?,
    val sampleRate: Int?,
    val channels: Int,
    val hires: Boolean,
    val bitrateKbps: Int,
    val totalBytes: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QobuzStreamSource) return false
        return playbackId == other.playbackId
    }

    override fun hashCode(): Int = playbackId.hashCode()
}

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
