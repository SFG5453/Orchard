/* Copyright (C) 2026 SFG545. Licensed under the GNU Affero General Public License v3 or later. */
package dev.sfg.orchard.mobile.playback.apple

import java.io.IOException
import java.net.URI
import java.util.Base64

internal data class AtmosPlaylist(val text: String, val initPath: String, val keys: List<ByteArray>)
internal data class AtmosVariant(val url: String, val bitrateKbps: Int)

/** Selects the first Atmos variant, ordered by Apple's preference. */
internal fun atmosVariant(master: String, url: String): AtmosVariant {
    val lines = master.lines().map(String::trim)
    val groups = lines.filter { it.startsWith("#EXT-X-MEDIA:") && it.contains("16/JOC") }
        .mapNotNull { Regex("GROUP-ID=\"([^\"]+)\"").find(it)?.groupValues?.get(1) }.toSet()
    val index = lines.indexOfFirst { line -> line.startsWith("#EXT-X-STREAM-INF:") &&
        groups.any { line.contains("AUDIO=\"$it\"") } }
    if (index < 0 || index + 1 >= lines.size || lines[index + 1].startsWith('#'))
        throw IOException("Apple Music has no Atmos rendition for this song")
    val bandwidth = Regex("(?:^|,)AVERAGE-BANDWIDTH=(\\d+)").find(lines[index].substringAfter(':'))
        ?: Regex("(?:^|,)BANDWIDTH=(\\d+)").find(lines[index].substringAfter(':'))
    val bitrate = bandwidth?.groupValues?.get(1)?.toLongOrNull()?.div(1000)?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: 0
    return AtmosVariant(URI(url).resolve(lines[index + 1]).toString(), bitrate)
}

/** Keeps HLS segment keys and makes media URLs absolute for the synthetic playlist URI. */
internal fun atmosPlaylist(media: String, url: String): AtmosPlaylist {
    val lines = media.lines().map(String::trim)
    val keyUris = lines.filter { it.startsWith("#EXT-X-KEY:") && it.contains("edef8ba9", ignoreCase = true) }
        .mapNotNull { Regex("URI=\"(data:[^\"]+)\"").find(it)?.groupValues?.get(1) }.distinct()
    val keys = keyUris.map { uri ->
        val bytes = runCatching { Base64.getDecoder().decode(uri.substringAfter(',')) }.getOrNull()
        bytes?.let { runCatching { psshKeyId(it) }.getOrNull() } ?: throw IOException("Invalid Atmos key identifier")
    }
    if (keys.isEmpty()) throw IOException("Atmos playlist has no Widevine key")
    val map = lines.firstOrNull { it.startsWith("#EXT-X-MAP:") } ?: throw IOException("Atmos playlist has no init segment")
    val init = Regex("URI=\"([^\"]+)\"").find(map)?.groupValues?.get(1) ?: throw IOException("Invalid Atmos init segment")
    if (!map.contains("BYTERANGE=\"")) throw IOException("Atmos init segment has no byte range")
    val base = URI(url)
    val text = lines.joinToString("\n", postfix = "\n") { line ->
        when {
            line.startsWith("#EXT-X-MAP:") -> line.replace(Regex("URI=\"([^\"]+)\"")) { "URI=\"${base.resolve(it.groupValues[1])}\"" }
            line.isNotEmpty() && !line.startsWith('#') -> base.resolve(line).toString()
            else -> line
        }
    }
    return AtmosPlaylist(text, base.resolve(init).toString().substringBefore('?'), keys)
}

/** Apple puts zero key IDs in tenc and associates its sample descriptions with playlist keys. */
internal fun patchAtmosInit(bytes: ByteArray, keys: List<ByteArray>): ByteArray {
    val offsets = mutableListOf<Int>()
    for (i in 4..bytes.size - 28) {
        if (bytes[i] != 't'.code.toByte() || bytes[i + 1] != 'e'.code.toByte() ||
            bytes[i + 2] != 'n'.code.toByte() || bytes[i + 3] != 'c'.code.toByte()) continue
        val size = java.nio.ByteBuffer.wrap(bytes, i - 4, 4).int
        if (size < 32 || i - 4 + size > bytes.size) throw IOException("Invalid Atmos tenc box")
        offsets += i + 12
    }
    if (offsets.size != keys.size || keys.any { it.size != 16 }) throw IOException("Unsupported Atmos key layout")
    return bytes.copyOf().also { out ->
        offsets.forEachIndexed { index, at ->
            if ((at until at + 16).any { out[it] != 0.toByte() }) throw IOException("Unexpected Atmos default key")
            keys[index].copyInto(out, at)
        }
    }
}
