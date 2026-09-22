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

package dev.sfg.orchard.mobile.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import dev.sfg.orchard.mobile.model.Track

/** Converts provider-neutral tracks to Media3 items without persisting stream URLs. */
object MediaItemMapper {
    private const val SCHEME = "orchard"
    private const val AUDIO_AUTHORITY = "stream"
    private const val VIDEO_AUTHORITY = "video"
    private const val TRACK_JSON = "orchard.track.json"

    fun toMediaItem(track: Track, videoId: String = ""): MediaItem {
        val storedTrack = track.copy(musicVideoId = track.musicVideoId.ifBlank { videoId })
        val extras = Bundle().apply {
            putString(TRACK_JSON, dev.sfg.orchard.mobile.model.CatalogJson.track(storedTrack).toString())
            putBoolean("explicit", track.explicit)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .setArtworkUri(track.artworkUrl.takeIf(String::isNotBlank)?.let(Uri::parse))
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setExtras(extras)
            .build()
        val uri = Uri.Builder()
            .scheme(SCHEME)
            .authority(if (videoId.isBlank()) AUDIO_AUTHORITY else VIDEO_AUTHORITY)
            .appendPath(videoId.ifBlank { track.id })
            .build()
        val requestMetadata = MediaItem.RequestMetadata.Builder()
            .setMediaUri(uri)
            .build()
        return MediaItem.Builder()
            .setMediaId(track.id)
            .setUri(uri)
            .setRequestMetadata(requestMetadata)
            .setMediaMetadata(metadata)
            .build()
    }

    fun toTrack(item: MediaItem): Track {
        val json = item.mediaMetadata.extras?.getString(TRACK_JSON)
        if (!json.isNullOrBlank()) {
            runCatching { return dev.sfg.orchard.mobile.model.CatalogJson.track(org.json.JSONObject(json)) }
        }
        val extras = item.mediaMetadata.extras
        val explicitFromExtras = extras?.getBoolean("explicit") ?: false
        return Track(
            id = item.mediaId,
            title = item.mediaMetadata.title?.toString().orEmpty(),
            artist = item.mediaMetadata.artist?.toString().orEmpty(),
            album = item.mediaMetadata.albumTitle?.toString().orEmpty(),
            artworkUrl = item.mediaMetadata.artworkUri?.toString().orEmpty(),
            explicit = explicitFromExtras,
        )
    }

    fun isOrchardUri(uri: Uri): Boolean =
        uri.scheme == SCHEME && (uri.host == AUDIO_AUTHORITY || uri.host == VIDEO_AUTHORITY)

    fun isVideoUri(uri: Uri?): Boolean = uri?.scheme == SCHEME && uri.host == VIDEO_AUTHORITY

    fun sourceId(uri: Uri): String = uri.lastPathSegment.orEmpty()

    /** Replaces only the source; media id, metadata, and queue position remain unchanged. */
    fun asVideo(item: MediaItem, videoId: String): MediaItem {
        require(videoId.isNotBlank()) { "A music video id is required" }
        val uri = Uri.Builder().scheme(SCHEME).authority(VIDEO_AUTHORITY).appendPath(videoId).build()
        return item.buildUpon()
            .setUri(uri)
            .setMimeType(null)
            .setRequestMetadata(item.requestMetadata.buildUpon().setMediaUri(uri).build())
            .build()
    }

    fun withMusicVideoId(item: MediaItem, videoId: String): MediaItem {
        if (videoId.isBlank()) return item
        val track = toTrack(item).copy(musicVideoId = videoId)
        val extras = Bundle(item.mediaMetadata.extras ?: Bundle()).apply {
            putString(TRACK_JSON, dev.sfg.orchard.mobile.model.CatalogJson.track(track).toString())
        }
        return item.buildUpon()
            .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build())
            .build()
    }

    fun asAudio(item: MediaItem): MediaItem {
        val uri = Uri.Builder().scheme(SCHEME).authority(AUDIO_AUTHORITY).appendPath(item.mediaId).build()
        return item.buildUpon()
            .setUri(uri)
            .setMimeType(null)
            .setRequestMetadata(item.requestMetadata.buildUpon().setMediaUri(uri).build())
            .build()
    }

    fun requiresAuthenticatedHls(uri: Uri): Boolean =
        isOrchardUri(uri) && uri.getQueryParameter(AUTHENTICATED_HLS) == "1"

    fun requiresAuthenticatedDirect(uri: Uri): Boolean =
        isOrchardUri(uri) && uri.getQueryParameter(AUTHENTICATED_DIRECT) == "1"

    /** Retries a stream that was actually age-gated through the signed-in direct path. */
    fun asAuthenticatedDirectFallback(item: MediaItem): MediaItem {
        val original = item.localConfiguration?.uri ?: return item
        val uri =
            original.buildUpon()
                .clearQuery()
                .appendQueryParameter(AUTHENTICATED_DIRECT, "1")
                .build()
        return item.buildUpon()
            .setUri(uri)
            .setMimeType(MimeTypes.VIDEO_MP4)
            .setRequestMetadata(item.requestMetadata.buildUpon().setMediaUri(uri).build())
            .build()
    }

    /** Switches a failed direct authenticated stream to the slower, broadly compatible HLS path. */
    fun asAuthenticatedHlsFallback(item: MediaItem): MediaItem {
        val original = item.localConfiguration?.uri ?: return item
        val uri =
            original.buildUpon()
                .clearQuery()
                .appendQueryParameter(AUTHENTICATED_HLS, "1")
                .build()
        return item.buildUpon()
            .setUri(uri)
            .setMimeType(MimeTypes.APPLICATION_M3U8)
            .setRequestMetadata(item.requestMetadata.buildUpon().setMediaUri(uri).build())
            .build()
    }

    private const val AUTHENTICATED_DIRECT = "authenticated_direct"
    private const val AUTHENTICATED_HLS = "authenticated_hls"
}
