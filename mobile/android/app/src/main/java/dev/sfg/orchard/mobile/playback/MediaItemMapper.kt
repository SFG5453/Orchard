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
import dev.sfg.orchard.mobile.model.Track

/** Converts provider-neutral tracks to Media3 items without persisting stream URLs. */
object MediaItemMapper {
    private const val SCHEME = "orchard"
    private const val TRACK_JSON = "orchard.track.json"

    fun toMediaItem(track: Track): MediaItem {
        val extras = Bundle().apply {
            putString(TRACK_JSON, dev.sfg.orchard.mobile.model.CatalogJson.track(track).toString())
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
        val uri = Uri.Builder().scheme(SCHEME).authority("stream").appendPath(track.id).build()
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

    fun isOrchardUri(uri: Uri): Boolean = uri.scheme == SCHEME && uri.host == "stream"
}
