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

import org.json.JSONObject
import java.io.IOException
import java.nio.ByteBuffer
import java.util.Base64
import kotlin.math.roundToLong

/** A failure worth showing as is; the player's wrappers bury it otherwise. */
internal class AppleMusicPlaybackException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * One Apple Music song as a plain encrypted MP4. Apple's HLS playlist names byte ranges of a
 * single fragmented MP4; the served file is Apple's init segment with a Widevine pssh in its
 * moov and a sidx after it, then Apple's fragments unchanged. ExoPlayer and MediaExtractor
 * both decrypt CENC fragments from the moov's pssh, and the sidx makes the file seekable
 * without reading every fragment header.
 */
internal class AppleMusicStream(
    /** The provider's `playback.stream` reply, which the license request repeats. */
    val source: JSONObject,
    val media: String,
    /** Start and size of each range of Apple's file; the first is the init segment. */
    val ranges: List<Pair<Long, Long>>,
    val durations: List<Double>,
    val pssh: ByteArray,
    val expiresAtMs: Long,
) {
    val duration: Double = durations.sum()
    val appleSize: Long = ranges.last().first + ranges.last().second

    /** Init, pssh and sidx as served; set once the init segment is fetched. */
    @Volatile var head: ByteArray? = null

    /** Bytes the head adds before Apple's first fragment. */
    val shift: Long get() = head!!.size - ranges.first().second
    val servedSize: Long get() = appleSize + shift
    val bitrateKbps: Int get() = if (duration > 0) (servedSize * 8 / duration / 1000).toInt() else 0

    /** The served head for Apple's raw init segment; null when it names no track timing. */
    fun buildHead(init: ByteArray): ByteArray? {
        val withPssh = withPssh(init, pssh) ?: return null
        val index = segmentIndex(withPssh) ?: return null
        return withPssh + index
    }

    private fun segmentIndex(init: ByteArray): ByteArray? {
        val trackId = field(init, "tkhd") ?: return null
        val timescale = field(init, "mdhd") ?: return null
        val box = ByteBuffer.allocate(32 + 12 * durations.size)
        box.putInt(box.capacity()).put("sidx".toByteArray()).putInt(0)
        // Version 0: earliest presentation time and first offset stay 0, so the first
        // fragment follows the index directly.
        box.putInt(trackId.toInt()).putInt(timescale.toInt()).putInt(0).putInt(0)
        box.putShort(0).putShort(durations.size.toShort())
        var elapsed = 0.0
        var boundary = 0L
        durations.forEachIndexed { i, seconds ->
            elapsed += seconds
            val next = (elapsed * timescale).roundToLong()
            box.putInt((ranges[i + 1].second and 0x7fffffffL).toInt())
            box.putInt((next - boundary).toInt())
            // Starts with a SAP of type 1: every AAC frame decodes on its own.
            box.putInt(0x90000000.toInt())
            boundary = next
        }
        return box.array()
    }

    companion object {
        private val WIDEVINE_SYSTEM = hex("edef8ba979d64acea3c827dcd51d21ed")

        /** Reads the provider's reply; throws when its layout is unusable. */
        fun parse(json: JSONObject, lifetimeMs: Long, now: Long = System.currentTimeMillis()): AppleMusicStream {
            val rangesJson = json.optJSONArray("ranges") ?: throw IOException("no byte ranges")
            val ranges = (0 until rangesJson.length()).map {
                val range = rangesJson.getJSONArray(it)
                range.getLong(0) to range.getLong(1)
            }
            val durationsJson = json.optJSONArray("durations") ?: throw IOException("no segment durations")
            val durations = (0 until durationsJson.length()).map(durationsJson::getDouble)
            if (ranges.size < 2 || durations.size != ranges.size - 1) throw IOException("segments do not match ranges")
            val pssh = psshFromKeyUri(json.optString("keyUri")) ?: throw IOException("no Widevine key")
            val media = json.optString("media").takeIf { it.startsWith("https://") } ?: throw IOException("no media URL")
            return AppleMusicStream(json, media, ranges, durations, pssh, now + lifetimeMs)
        }

        /** A key id or a whole Widevine pssh box from the EXT-X-KEY data URI. */
        fun psshFromKeyUri(uri: String): ByteArray? {
            val comma = uri.indexOf(',')
            if (!uri.startsWith("data:") || comma < 0) return null
            val data = runCatching { Base64.getDecoder().decode(uri.substring(comma + 1)) }.getOrNull() ?: return null
            if (data.size == 16) return widevinePssh(data)
            val isPssh = data.size >= 32 && String(data, 4, 4, Charsets.US_ASCII) == "pssh" &&
                data.copyOfRange(12, 28).contentEquals(WIDEVINE_SYSTEM)
            return data.takeIf { isPssh }
        }

        /** A version 0 Widevine pssh box naming one key id. */
        fun widevinePssh(keyId: ByteArray): ByteArray =
            // WidevinePsshData { key_ids: keyId }
            psshBox(byteArrayOf(0x12, keyId.size.toByte()) + keyId)

        /** A version 0 Widevine pssh box around [data]. */
        fun psshBox(data: ByteArray): ByteArray =
            ByteBuffer.allocate(32 + data.size)
                .putInt(32 + data.size).put("pssh".toByteArray()).putInt(0)
                .put(WIDEVINE_SYSTEM).putInt(data.size).put(data)
                .array()

        /** [init] with [pssh] as the moov's last child, unless the moov has a Widevine pssh. */
        fun withPssh(init: ByteArray, pssh: ByteArray): ByteArray? {
            var at = 0
            while (at + 8 <= init.size) {
                val size = ByteBuffer.wrap(init, at, 4).int.toLong() and 0xffffffffL
                if (size < 8 || at + size > init.size) return null
                if (String(init, at + 4, 4, Charsets.US_ASCII) != "moov") {
                    at += size.toInt()
                    continue
                }
                val end = at + size.toInt()
                if (hasWidevinePssh(init, at + 8, end)) return init
                val moovSize = ByteBuffer.allocate(4).putInt(size.toInt() + pssh.size).array()
                return init.copyOfRange(0, at) + moovSize + init.copyOfRange(at + 4, end) + pssh +
                    init.copyOfRange(end, init.size)
            }
            return null
        }

        private fun hasWidevinePssh(bytes: ByteArray, from: Int, end: Int): Boolean {
            var at = from
            while (at + 8 <= end) {
                val size = ByteBuffer.wrap(bytes, at, 4).int
                if (size < 8 || at + size > end) return false
                if (String(bytes, at + 4, 4, Charsets.US_ASCII) == "pssh" && size >= 28 &&
                    bytes.copyOfRange(at + 12, at + 28).contentEquals(WIDEVINE_SYSTEM)
                ) return true
                at += size
            }
            return false
        }

        // Track id from tkhd and timescale from mdhd; both sit after creation and
        // modification times whose width depends on the box version.
        private fun field(init: ByteArray, type: String): Long? {
            val at = indexOf(init, type.toByteArray())
            if (at < 4 || at + 5 > init.size) return null
            val offset = at + if (init[at + 4].toInt() == 1) 24 else 16
            if (offset + 4 > init.size) return null
            return (ByteBuffer.wrap(init, offset, 4).int.toLong() and 0xffffffffL).takeIf { it != 0L }
        }

        private fun indexOf(bytes: ByteArray, needle: ByteArray): Int {
            outer@ for (i in 0..bytes.size - needle.size) {
                for (j in needle.indices) if (bytes[i + j] != needle[j]) continue@outer
                return i
            }
            return -1
        }

        private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
