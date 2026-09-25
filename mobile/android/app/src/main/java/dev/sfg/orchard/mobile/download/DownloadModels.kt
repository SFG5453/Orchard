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

package dev.sfg.orchard.mobile.download

import dev.sfg.orchard.mobile.model.Track
import org.json.JSONObject

/** Lifecycle status for an offline download request. */
enum class DownloadStatus {
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    PAUSED,
}

/** Active or completed download task state. */
data class DownloadItem(
    val track: Track,
    val status: DownloadStatus = DownloadStatus.QUEUED,
    val progress: Float = 0f,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = 0L,
    val filePath: String = "",
    val mimeType: String = "audio/webm",
    /** Cache-backed URI for the downloaded landscape/square motion cover, if available. */
    val cachedAnimatedArtworkUrl: String = "",
    /** Cache-backed URI for the downloaded portrait motion cover, if available. */
    val cachedAnimatedArtworkVerticalUrl: String = "",
    val animatedArtworkBytesDownloaded: Long = 0L,
    val downloadedAtMs: Long = 0L,
    val errorMessage: String = "",
) {
    val isFinished: Boolean get() = status == DownloadStatus.COMPLETED
    val isDownloading: Boolean get() = status == DownloadStatus.DOWNLOADING || status == DownloadStatus.QUEUED

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", track.id)
        put("title", track.title)
        put("artist", track.artist)
        put("artistId", track.artistId)
        put("album", track.album)
        put("albumId", track.albumId)
        put("artworkUrl", track.artworkUrl)
        put("animatedArtworkUrl", track.animatedArtworkUrl)
        put("animatedArtworkVerticalUrl", track.animatedArtworkVerticalUrl)
        put("durationMs", track.durationMs)
        put("status", status.name)
        put("bytesDownloaded", bytesDownloaded)
        put("totalBytes", totalBytes)
        put("filePath", filePath)
        put("mimeType", mimeType)
        put("cachedAnimatedArtworkUrl", cachedAnimatedArtworkUrl)
        put("cachedAnimatedArtworkVerticalUrl", cachedAnimatedArtworkVerticalUrl)
        put("animatedArtworkBytesDownloaded", animatedArtworkBytesDownloaded)
        put("downloadedAtMs", downloadedAtMs)
        put("errorMessage", errorMessage)
    }

    companion object {
        fun fromJson(json: JSONObject): DownloadItem {
            val track = Track(
                id = json.optString("id"),
                title = json.optString("title"),
                artist = json.optString("artist"),
                artistId = json.optString("artistId"),
                album = json.optString("album"),
                albumId = json.optString("albumId"),
                artworkUrl = json.optString("artworkUrl"),
                animatedArtworkUrl = json.optString("animatedArtworkUrl"),
                animatedArtworkVerticalUrl = json.optString("animatedArtworkVerticalUrl"),
                durationMs = json.optLong("durationMs"),
            )
            val statusStr = json.optString("status", DownloadStatus.COMPLETED.name)
            val status = runCatching { DownloadStatus.valueOf(statusStr) }.getOrDefault(DownloadStatus.COMPLETED)
            return DownloadItem(
                track = track,
                status = status,
                progress = 1.0f,
                bytesDownloaded = json.optLong("bytesDownloaded"),
                totalBytes = json.optLong("totalBytes"),
                filePath = json.optString("filePath"),
                mimeType = json.optString("mimeType", "audio/webm"),
                cachedAnimatedArtworkUrl = json.optString("cachedAnimatedArtworkUrl"),
                cachedAnimatedArtworkVerticalUrl = json.optString("cachedAnimatedArtworkVerticalUrl"),
                animatedArtworkBytesDownloaded = json.optLong("animatedArtworkBytesDownloaded"),
                downloadedAtMs = json.optLong("downloadedAtMs"),
                errorMessage = json.optString("errorMessage"),
            )
        }
    }
}
