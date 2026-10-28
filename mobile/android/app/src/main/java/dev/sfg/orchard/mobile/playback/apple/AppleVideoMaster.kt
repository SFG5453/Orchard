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

import java.net.URI

private val QUOTED_URI = Regex("""URI="([^"]*)"""")
private val RESOLUTION = Regex("""RESOLUTION=\d+x(\d+)""")
private val ALLOWED_CPC = Regex("""ALLOWED-CPC="([^"]*)"""")

/**
 * Apple's master playlist with only the Widevine renditions this device may play, capped at
 * [maxHeight] lines (0 for no cap; the shortest survives a cap below all), and every URI
 * made absolute because the playlist is served from an orchard:// address.
 */
internal fun filterMaster(text: String, url: String, hardware: Boolean, maxHeight: Int): String {
    val base = URI(url)
    fun absolute(ref: String) = base.resolve(ref.trim()).toString()
    data class Variant(val info: String, val uri: String, val height: Int)
    val variants = mutableListOf<Variant>()
    val header = mutableListOf<String>()
    var info: String? = null
    for (raw in text.lines()) {
        val line = raw.trim()
        when {
            line.isEmpty() -> Unit
            line.startsWith("#EXT-X-STREAM-INF:") -> info = line
            !line.startsWith("#") -> {
                val stream = info ?: continue
                info = null
                if (widevinePlayable(stream, hardware)) {
                    val height = RESOLUTION.find(stream)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                    variants += Variant(stream, absolute(line), height)
                }
            }
            // Trick-play renditions include HDCP-only ones and are never shown here.
            line.startsWith("#EXT-X-I-FRAME-STREAM-INF:") -> Unit
            line.startsWith("#EXT-X-MEDIA:") -> header += line.replace(QUOTED_URI) { "URI=\"${absolute(it.groupValues[1])}\"" }
            else -> header += line
        }
    }
    require(variants.isNotEmpty()) { "Apple Music offers no rendition this device can play." }
    val capped = if (maxHeight <= 0) variants else variants.filter { it.height <= maxHeight }
        .ifEmpty { listOf(variants.minBy { it.height }) }
    return (header + capped.flatMap { listOf(it.info, it.uri) }).joinToString("\n", postfix = "\n")
}

private fun widevinePlayable(info: String, hardware: Boolean): Boolean {
    val allowed = ALLOWED_CPC.find(info)?.groupValues?.get(1) ?: return true
    return "WIDEVINE_SOFTWARE" in allowed || (hardware && "WIDEVINE_HARDWARE" in allowed)
}


private val WIDEVINE_KEY = Regex("""#EXT-X-KEY:.*URI="data:[^",]*,([^"]+)".*KEYFORMAT="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"""", RegexOption.IGNORE_CASE)
private val MAP_URI = Regex("""#EXT-X-MAP:.*URI="([^"]+)"""")

/**
 * The init segment of an Apple media playlist and the key it is encrypted with. Apple's init
 * segments name an all-zero key in `tenc`; only the playlist's Widevine pssh holds the real one.
 */
internal fun initSegmentKey(playlist: String, url: String): Pair<String, ByteArray>? {
    val pssh = WIDEVINE_KEY.find(playlist)?.groupValues?.get(1) ?: return null
    val map = MAP_URI.find(playlist)?.groupValues?.get(1) ?: return null
    val keyId = psshKeyId(java.util.Base64.getDecoder().decode(pssh)) ?: return null
    return segmentPath(URI(url).resolve(map.trim()).toString()) to keyId
}

/** Segment identity without the query, which Apple's signed URLs vary. */
internal fun segmentPath(url: String): String = url.substringBefore('?')

/** [init] with [keyId] in place of an all-zero `tenc` default key; other files come back unchanged. */
internal fun withKeyId(init: ByteArray, keyId: ByteArray): ByteArray {
    val type = "tenc".toByteArray()
    for (i in 4..init.size - 28) {
        if (init[i] != type[0] || init[i + 1] != type[1] || init[i + 2] != type[2] || init[i + 3] != type[3]) continue
        // size, type, version and flags, two reserved/pattern bytes, isProtected, IV size, then the key id.
        val at = i + 12
        if ((at until at + 16).any { init[it] != 0.toByte() }) return init
        return init.copyOf().also { keyId.copyInto(it, at) }
    }
    return init
}

/** The first key id in a Widevine pssh box: from a version 1 header, else the WidevinePsshData key_id field. */
private fun psshKeyId(box: ByteArray): ByteArray? {
    if (box.size < 32 || String(box, 4, 4, Charsets.US_ASCII) != "pssh") return null
    val version = box[8].toInt()
    var at = 28
    if (version == 1) {
        val count = readInt(box, at)
        if (count > 0 && box.size >= at + 20) return box.copyOfRange(at + 4, at + 20)
        at += 4 + 16 * count
    }
    val size = readInt(box, at)
    var p = at + 4
    val end = minOf(box.size, p + size)
    while (p < end) {
        val tag = box[p++].toInt() and 0xff
        when (tag and 7) {
            0 -> while (p < end && box[p++].toInt() and 0x80 != 0) Unit
            2 -> {
                val length = box[p++].toInt() and 0xff
                if (tag shr 3 == 2 && length == 16 && p + 16 <= end) return box.copyOfRange(p, p + 16)
                p += length
            }
            else -> return null
        }
    }
    return null
}

private fun readInt(bytes: ByteArray, at: Int): Int =
    (bytes[at].toInt() and 0xff shl 24) or (bytes[at + 1].toInt() and 0xff shl 16) or
        (bytes[at + 2].toInt() and 0xff shl 8) or (bytes[at + 3].toInt() and 0xff)
