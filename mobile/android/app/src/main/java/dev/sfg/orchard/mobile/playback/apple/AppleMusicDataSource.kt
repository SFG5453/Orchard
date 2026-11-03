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

package dev.sfg.orchard.mobile.playback.apple

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import dev.sfg.orchard.mobile.playback.MediaItemMapper
import java.io.EOFException

/**
 * Serves an `orchard://stream/am.songs.<id>` URI as the file [AppleMusicStream] describes:
 * the head from memory, then Apple's fragments by byte range. The bytes stay encrypted, so
 * the stream cache above holds only ciphertext.
 */
@UnstableApi
internal class AppleMusicDataSource(
    private val streams: AppleMusicStreams,
    private val upstream: DataSource,
    private val onStream: (Uri, AppleMusicStream) -> Unit,
) : BaseDataSource(true) {
    private var spec: DataSpec? = null
    private var stream: AppleMusicStream? = null
    private var position = 0L
    private var remaining = 0L
    private var upstreamOpen = false

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val stream = streams.stream(MediaItemMapper.sourceId(dataSpec.uri))
        val size = stream.servedSize
        if (dataSpec.position > size) throw DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE)
        spec = dataSpec
        this.stream = stream
        position = dataSpec.position
        remaining = if (dataSpec.length == C.LENGTH_UNSET.toLong()) size - position else minOf(dataSpec.length, size - position)
        onStream(dataSpec.uri, stream)
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val stream = stream!!
        val head = stream.head!!
        val wanted = minOf(length.toLong(), remaining).toInt()
        val read = if (position < head.size) {
            val count = minOf(wanted, head.size - position.toInt())
            System.arraycopy(head, position.toInt(), buffer, offset, count)
            count
        } else {
            if (!upstreamOpen) {
                upstream.open(DataSpec.Builder()
                    .setUri(stream.media)
                    .setPosition(position - stream.shift)
                    .setLength(remaining)
                    .build())
                upstreamOpen = true
            }
            upstream.read(buffer, offset, wanted).also { if (it == C.RESULT_END_OF_INPUT) throw EOFException() }
        }
        position += read
        remaining -= read
        bytesTransferred(read)
        return read
    }

    override fun getUri(): Uri? = spec?.uri

    override fun close() {
        try {
            if (upstreamOpen) upstream.close()
        } finally {
            upstreamOpen = false
            if (spec != null) {
                spec = null
                stream = null
                transferEnded()
            }
        }
    }

    /** Sends Apple Music URIs to [AppleMusicDataSource] and everything else to [other]. */
    class Router(private val other: DataSource, private val apple: () -> DataSource) : DataSource {
        private val listeners = mutableListOf<TransferListener>()
        private var appleSource: DataSource? = null
        private var current: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
            other.addTransferListener(transferListener)
            appleSource?.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val target = if (isApple(dataSpec.uri)) {
                appleSource ?: apple().also { source ->
                    listeners.forEach(source::addTransferListener)
                    appleSource = source
                }
            } else other
            current = target
            return target.open(dataSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int = current!!.read(buffer, offset, length)

        override fun getUri(): Uri? = current?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

        override fun close() {
            current?.close()
            current = null
        }
    }

    companion object {
        fun isApple(uri: Uri?): Boolean =
            uri != null && MediaItemMapper.isOrchardUri(uri) && !MediaItemMapper.isVideoUri(uri) &&
                AppleMusicStreams.isAppleSource(MediaItemMapper.sourceId(uri))
    }
}
