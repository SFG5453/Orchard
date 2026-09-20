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

import androidx.media3.cast.DefaultMediaItemConverter
import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaQueueItem
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/** Converts Orchard queue items to LAN URLs understood by the Cast receiver. */
class ChromecastMediaItemConverter(private val streamServer: ChromecastStreamServer) :
    MediaItemConverter {
    private val delegate = DefaultMediaItemConverter()
    private val originals = ConcurrentHashMap<String, MediaItem>()

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        originals[mediaItem.mediaId] = mediaItem
        val castUrl = streamServer.urlFor(mediaItem.mediaId)
        val castItem =
            mediaItem
                .buildUpon()
                .setUri(castUrl)
                // The response supplies the precise type. This is the receiver's up-front hint;
                // Orchard's preferred direct stream is Opus/WebM.
                .setMimeType(mediaItem.localConfiguration?.mimeType ?: DEFAULT_AUDIO_MIME_TYPE)
                .build()
        val defaultQueueItem = delegate.toMediaQueueItem(castItem)
        val defaultMedia = defaultQueueItem.media ?: return defaultQueueItem

        val track = MediaItemMapper.toTrack(mediaItem)
        val customData = JSONObject().apply {
            defaultMedia.customData?.let { existing ->
                val keys = existing.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    put(key, existing.get(key))
                }
            }
            if (track.animatedArtworkUrl.isNotBlank()) {
                put("animatedArtworkUrl", track.animatedArtworkUrl)
            }
            if (track.animatedArtworkVerticalUrl.isNotBlank()) {
                put("animatedArtworkVerticalUrl", track.animatedArtworkVerticalUrl)
            }
            if (track.title.isNotBlank()) put("title", track.title)
            if (track.artist.isNotBlank()) put("artist", track.artist)
            if (track.album.isNotBlank()) put("album", track.album)
            if (track.artworkUrl.isNotBlank()) put("artworkUrl", track.artworkUrl)
        }

        val enrichedMedia =
            MediaInfo.Builder(defaultMedia.contentId)
                // Keep the playable URL when rebuilding MediaInfo; contentId is only the track ID.
                .setContentUrl(castUrl)
                .setStreamType(defaultMedia.streamType)
                .setContentType(defaultMedia.contentType)
                .setMetadata(defaultMedia.metadata)
                .setStreamDuration(defaultMedia.streamDuration)
                .setCustomData(customData)
                .build()

        val builder =
            MediaQueueItem.Builder(enrichedMedia)
                .setAutoplay(defaultQueueItem.autoplay)
                .setStartTime(defaultQueueItem.startTime)
                .setPlaybackDuration(defaultQueueItem.playbackDuration)
                .setPreloadTime(defaultQueueItem.preloadTime)
                .setCustomData(customData)

        defaultQueueItem.activeTrackIds?.let { builder.setActiveTrackIds(it) }

        return builder.build()
    }

    override fun toMediaItem(mediaQueueItem: MediaQueueItem): MediaItem {
        mediaQueueItem.media?.contentId?.let { originals[it] }?.let { return it }
        val converted = delegate.toMediaItem(mediaQueueItem)
        return originals[converted.mediaId] ?: converted
    }

    /** The Cast timeline can contain metadata-only placeholders for queued items. */
    fun originalFor(mediaId: String): MediaItem? = originals[mediaId]

    private companion object {
        const val DEFAULT_AUDIO_MIME_TYPE = "audio/webm"
    }
}
