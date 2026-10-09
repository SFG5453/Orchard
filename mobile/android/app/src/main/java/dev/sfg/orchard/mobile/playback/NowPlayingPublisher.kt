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

import dev.sfg.orchard.mobile.model.StreamDetail
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.sfg.orchard.mobile.OrchardGraph
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import okhttp3.Request

/**
 * What the rest of the phone learns about the playing song: the bitrate readout, artwork
 * embedded for watches, and scrobbles. Main thread unless noted.
 */
@UnstableApi
internal class NowPlayingPublisher(
    private val graph: OrchardGraph,
    private val handler: Handler,
    private val scope: CoroutineScope,
    private val cache: StreamCache,
    private val cachedStream: (stableUri: String) -> ResolvedStream?,
    /** The session's local player, or null before it exists. */
    private val player: () -> ExoPlayer?,
) {
    private var artworkHydrationId = ""
    private val youtubeHistory = YouTubeHistoryReporter(graph, graph.applicationScope)

    /**
     * Publishes the bitrate of what is actually playing, for the readout under the scrubber.
     *
     * Prefers the rate measured from the cached file, which is what the bytes on disk really are,
     * over the rate a resolver declared for the stream it opened, which is the encoder's nominal
     * target. Falls to 0 when neither is known, and 0 renders as nothing: the readout is allowed to
     * say what you are hearing or to say nothing, never to guess.
     */
    fun publishBitrate() {
        val player = player() ?: return
        val item = player.currentMediaItem
        val uri = item?.let { it.requestMetadata.mediaUri ?: it.localConfiguration?.uri }
        if (item == null || MediaItemMapper.isVideoUri(uri)) {
            publish(0, qobuz = false)
            return
        }
        val format = player.audioFormat
        val playerCodec = codecName(format?.sampleMimeType)
        val localTrack = MediaItemMapper.toTrack(item).takeIf { it.isLocal }
        if (localTrack != null) {
            // The container's own average, else what the decoder reports once the file is open.
            val decoded = listOfNotNull(format?.averageBitrate, format?.bitrate).firstOrNull { it > 0 }?.div(1000) ?: 0
            val codec = playerCodec.ifBlank { localTrack.codec.uppercase() }
            publish(if (localTrack.localBitrateKbps > 0) localTrack.localBitrateKbps else decoded, qobuz = false, StreamDetail(codec = codec))
            return
        }
        val resolved = uri?.let { cachedStream(it.toString()) }
        if (resolved != null && resolved.isQobuz) {
            publish(resolved.bitrateKbps, qobuz = true, resolved.qobuzDetail())
            return
        }
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        val measured = uri?.let { cache.cachedBitrateKbps(it, duration) } ?: 0
        val kbps = if (measured > 0) measured else graph.streams.knownBitrateKbps(item.mediaId)
        // A cache hit skips resolution, so the remembered format keeps the badge on its label.
        val qobuzSelected = graph.streamVariant().contains("|qobuz|")
        val remembered = if (qobuzSelected && resolved == null) {
            graph.qobuzTiers.detail(item.mediaId, graph.streamVariant())
        } else null
        if (remembered != null) {
            publish(kbps, qobuz = true, remembered)
        } else {
            publish(kbps, qobuz = false, StreamDetail(codec = playerCodec.ifBlank { codecName(resolved?.mimeType) }))
        }
    }

    private fun ResolvedStream.qobuzDetail() = StreamDetail(hires, bitDepth ?: 0, sampleRate ?: 0, "FLAC")

    private fun codecName(mime: String?): String {
        val m = mime.orEmpty().lowercase()
        return when {
            "flac" in m -> "FLAC"
            "opus" in m -> "Opus"
            "mp4a" in m || "aac" in m -> "AAC"
            "vorbis" in m -> "Vorbis"
            "mpeg" in m || "mp3" in m -> "MP3"
            else -> ""
        }
    }

    private fun publish(kbps: Int, qobuz: Boolean, detail: StreamDetail = StreamDetail()) {
        graph.activeBitrate.value = kbps
        graph.activeTrackIsQobuz.value = qobuz
        graph.activeStreamDetail.value = detail
    }

    /**
     * Any thread. Resolution also runs for queued items, so only a result for the current item
     * repaints the badge.
     */
    fun onResolved(stableUri: String, stream: ResolvedStream) {
        handler.post {
            val item = player()?.currentMediaItem ?: return@post
            val currentUri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
            if (currentUri?.toString() != stableUri) return@post
            if (stream.isQobuz) {
                val detail = stream.qobuzDetail()
                publish(stream.bitrateKbps, qobuz = true, detail)
            } else {
                publishBitrate()
            }
        }
    }

    /** Forces the next [hydrateArtwork] to fetch again, for a new current item. */
    fun resetArtwork() {
        artworkHydrationId = ""
    }

    /**
     * WearOS media controls do not reliably dereference remote artworkUri values. Embed the
     * compressed cover in the session metadata as well, which lets the watch render it directly.
     */
    fun hydrateArtwork() {
        val item = player()?.currentMediaItem ?: return
        val artworkUrl = item.mediaMetadata.artworkUri?.toString().orEmpty()
        if (artworkUrl.isBlank() || item.mediaMetadata.artworkData != null || artworkHydrationId == item.mediaId) return
        artworkHydrationId = item.mediaId
        scope.launch {
            val bytes = runCatching {
                if (artworkUrl.startsWith("file://")) {
                    // A cover on this phone: other processes cannot open a file:// URI, so it travels as bytes.
                    java.io.File(android.net.Uri.parse(artworkUrl).path.orEmpty()).readBytes().takeIf { it.size <= 2 * 1024 * 1024 }
                } else {
                    graph.http.newCall(Request.Builder().url(artworkUrl).build()).execute().use { response ->
                        if (!response.isSuccessful) return@use null
                        response.body.bytes().takeIf { it.size <= 2 * 1024 * 1024 }
                    }
                }
            }.getOrNull() ?: return@launch
            handler.post {
                val player = player() ?: return@post
                val index = player.currentMediaItemIndex
                if (index !in 0 until player.mediaItemCount) return@post
                val current = player.getMediaItemAt(index)
                if (current.mediaId != item.mediaId) return@post
                val metadata = current.mediaMetadata.buildUpon()
                    .setArtworkData(bytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
                    .setExtras(current.mediaMetadata.extras)
                    .build()
                player.replaceMediaItem(index, current.buildUpon().setMediaMetadata(metadata).build())
            }
        }
    }

    /**
     * Scrobbling belongs to the foreground playback service, not the activity: it must continue
     * when Android removes the UI while a local or Cast queue is still playing.
     */
    fun updateScrobbling(source: Player) {
        val track = source.currentMediaItem?.let(MediaItemMapper::toTrack)
        val duration = source.duration.takeUnless { it == C.TIME_UNSET }?.coerceAtLeast(0)
            ?: track?.durationMs?.coerceAtLeast(0)
            ?: 0
        val snapshot = PlaybackSnapshot(
            currentTrack = track,
            positionMs = source.currentPosition.coerceAtLeast(0),
            durationMs = duration,
            isPlaying = source.isPlaying,
        )
        graph.lastfm.updatePlayback(snapshot)
        graph.listenBrainz.updatePlayback(snapshot)
        youtubeHistory.update(source)
    }

    fun finishHistory() = youtubeHistory.finish()
}
