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

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

val QBZ_INIT_UUID: ByteArray = hexToBytes("c7c75df0fdd951e98fc22971e4acf8d2")
val QBZ_SEGMENT_UUID: ByteArray = hexToBytes("3b42129256f35f75923663b69a1f52b2")

private const val FLAC_METADATA_SEEKTABLE = 3
private const val FLAC_SEEK_POINT_BYTES = 18

fun hexToBytes(hex: String): ByteArray {
    val clean = hex.trim()
    val bytes = ByteArray(clean.length / 2)
    for (i in bytes.indices) {
        val index = i * 2
        bytes[i] = clean.substring(index, index + 2).toInt(16).toByte()
    }
    return bytes
}

fun decodeBase64Url(value: String): ByteArray =
    Base64.getUrlDecoder().decode(value.trim())

data class Mp4Box(
    val offset: Int,
    val size: Int,
    val headerSize: Int,
    val type: String,
) {
    val end: Int get() = offset + size
}

fun parseMp4Boxes(data: ByteArray): List<Mp4Box> {
    val list = mutableListOf<Mp4Box>()
    var offset = 0
    val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
    while (offset + 8 <= data.size) {
        val rawSize = buf.getInt(offset).toLong() and 0xFFFFFFFFL
        var headerSize = 8
        val size: Int
        if (rawSize == 1L) {
            if (offset + 16 > data.size) break
            val ext = buf.getLong(offset + 8)
            if (ext > Int.MAX_VALUE || ext < 0) break
            size = ext.toInt()
            headerSize = 16
        } else if (rawSize == 0L) {
            size = data.size - offset
        } else {
            size = rawSize.toInt()
        }
        if (size < headerSize || offset + size > data.size) break
        val typeBytes = ByteArray(4)
        System.arraycopy(data, offset + 4, typeBytes, 0, 4)
        val type = String(typeBytes, Charsets.US_ASCII)
        list.add(Mp4Box(offset, size, headerSize, type))
        offset += size
    }
    return list
}

data class SegmentTableEntry(
    val byteLength: Int,
    val sampleCount: Int,
    var byteOffset: Int = 0,
    var sampleOffset: Long = 0L,
)

data class SeekPoint(
    val sampleNumber: Long,
    val streamOffset: Long,
    val frameSamples: Int,
)

data class ParsedInitSegment(
    val flacHeader: ByteArray,
    val segmentTable: List<SegmentTableEntry>,
    val totalLength: Long,
    val sampleRate: Int,
    val channels: Int,
    val bitDepth: Int,
    val totalSamples: Long,
    val seekPoints: List<SeekPoint>,
    val tableSamples: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ParsedInitSegment) return false
        return totalLength == other.totalLength && flacHeader.contentEquals(other.flacHeader)
    }

    override fun hashCode(): Int = totalLength.hashCode()
}

fun seekableFlacHeader(
    streamInfo: ByteArray,
    segmentTable: List<SegmentTableEntry>,
    totalSamples: Long,
): Triple<ByteArray, List<SeekPoint>, Long> {
    val header = streamInfo.copyOf()
    val buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
    val maxBlockSize = buf.getShort(10).toInt() and 0xFFFF
    val frameSamples = if (maxBlockSize >= 16) maxBlockSize else 0

    val points = mutableListOf<SeekPoint>()
    var sampleOffset = 0L

    for (entry in segmentTable) {
        entry.sampleOffset = sampleOffset
        if (entry.sampleCount > 0 && sampleOffset < totalSamples) {
            points.add(
                SeekPoint(
                    sampleNumber = sampleOffset,
                    streamOffset = entry.byteOffset.toLong(),
                    frameSamples = frameSamples,
                )
            )
        }
        sampleOffset += entry.sampleCount
    }

    if (points.isEmpty()) {
        header[4] = (header[4].toInt() or 0x80).toByte()
        return Triple(header, emptyList(), sampleOffset)
    }

    // STREAMINFO is not the final metadata block; synthesized SEEKTABLE follows it
    header[4] = (header[4].toInt() and 0x7F).toByte()
    val seekTableBytes = ByteArray(4 + points.size * FLAC_SEEK_POINT_BYTES)
    val tableBuf = ByteBuffer.wrap(seekTableBytes).order(ByteOrder.BIG_ENDIAN)
    tableBuf.put((0x80 or FLAC_METADATA_SEEKTABLE).toByte())
    val length = points.size * FLAC_SEEK_POINT_BYTES
    tableBuf.put((length shr 16).toByte())
    tableBuf.put((length shr 8).toByte())
    tableBuf.put(length.toByte())

    for (point in points) {
        tableBuf.putLong(point.sampleNumber)
        tableBuf.putLong(point.streamOffset)
        tableBuf.putShort(point.frameSamples.toShort())
    }

    val combined = ByteArray(header.size + seekTableBytes.size)
    System.arraycopy(header, 0, combined, 0, header.size)
    System.arraycopy(seekTableBytes, 0, combined, header.size, seekTableBytes.size)
    return Triple(combined, points, sampleOffset)
}

fun parseQobuzInitSegment(input: ByteArray): ParsedInitSegment {
    val boxes = parseMp4Boxes(input)
    val box = boxes.firstOrNull { candidate ->
        if (candidate.type != "uuid") return@firstOrNull false
        val uuidStart = candidate.offset + candidate.headerSize
        if (uuidStart + 16 > candidate.end) return@firstOrNull false
        val uuid = input.copyOfRange(uuidStart, uuidStart + 16)
        uuid.contentEquals(QBZ_INIT_UUID)
    } ?: throw IllegalStateException("Qobuz init segment does not contain its stream descriptor")

    var cursor = box.offset + box.headerSize + 16
    if (cursor + 28 > box.end) throw IllegalStateException("Qobuz init stream descriptor is truncated")

    cursor += 26
    val buf = ByteBuffer.wrap(input).order(ByteOrder.BIG_ENDIAN)
    val rawLength = buf.getShort(cursor).toInt() and 0xFFFF
    cursor += 2
    val rawEnd = min(box.end, cursor + rawLength)
    val raw = input.copyOfRange(cursor, rawEnd)
    cursor = rawEnd

    val flacMarker = "fLaC".toByteArray(Charsets.US_ASCII)
    var flacOffset = -1
    for (i in 0..raw.size - 4) {
        if (raw[i] == flacMarker[0] && raw[i + 1] == flacMarker[1] &&
            raw[i + 2] == flacMarker[2] && raw[i + 3] == flacMarker[3]
        ) {
            flacOffset = i
            break
        }
    }
    if (flacOffset < 0 || flacOffset + 42 > raw.size) {
        throw IllegalStateException("Qobuz init segment does not contain complete FLAC stream info")
    }
    val streamInfo = raw.copyOfRange(flacOffset, flacOffset + 42)

    val segmentTable = mutableListOf<SegmentTableEntry>()
    if (cursor < box.end) {
        val keyIdLength = input[cursor].toInt() and 0xFF
        cursor += 1 + keyIdLength
        if (cursor + 2 <= box.end) {
            val count = buf.getShort(cursor).toInt() and 0xFFFF
            cursor += 2
            var index = 0
            while (index < count && cursor + 8 <= box.end) {
                val byteLen = buf.getInt(cursor)
                val sampleCnt = buf.getInt(cursor + 4)
                segmentTable.add(SegmentTableEntry(byteLength = byteLen, sampleCount = sampleCnt))
                cursor += 8
                index++
            }
        }
    }
    if (segmentTable.isEmpty()) {
        throw IllegalStateException("Qobuz init segment has no audio segment table")
    }

    var byteOffset = 0
    for (entry in segmentTable) {
        entry.byteOffset = byteOffset
        byteOffset += entry.byteLength
    }

    val streamInfoBuf = ByteBuffer.wrap(streamInfo).order(ByteOrder.BIG_ENDIAN)
    val packed = streamInfoBuf.getLong(18)
    val totalSamples = packed and 0xFFFFFFFFFL
    val (flacHeader, seekPoints, tableSamples) = seekableFlacHeader(streamInfo, segmentTable, totalSamples)

    val sampleRate = (packed ushr 44).toInt()
    val channels = ((packed ushr 41) and 0x7L).toInt() + 1
    val bitDepth = ((packed ushr 36) and 0x1FL).toInt() + 1

    return ParsedInitSegment(
        flacHeader = flacHeader,
        segmentTable = segmentTable,
        totalLength = flacHeader.size.toLong() + byteOffset.toLong(),
        sampleRate = sampleRate,
        channels = channels,
        bitDepth = bitDepth,
        totalSamples = totalSamples,
        seekPoints = seekPoints,
        tableSamples = tableSamples,
    )
}

data class AudioFrame(
    val size: Int,
    val skip: Int,
    val flags: Int,
    val iv: ByteArray,
)

data class ParsedAudioSegment(
    val data: ByteArray,
    val dataOffset: Int,
    val frames: List<AudioFrame>,
    val mediaEnd: Int,
)

fun parseQobuzAudioSegment(input: ByteArray): ParsedAudioSegment {
    val boxes = parseMp4Boxes(input)
    var descriptor: Mp4Box? = null
    var mediaEnd = input.size

    for (box in boxes) {
        if (box.type == "mdat") mediaEnd = box.end
        if (box.type == "uuid") {
            val start = box.offset + box.headerSize
            if (start + 16 <= box.end) {
                val uuid = input.copyOfRange(start, start + 16)
                if (uuid.contentEquals(QBZ_SEGMENT_UUID)) descriptor = box
            }
        }
    }
    if (descriptor == null) throw IllegalStateException("Qobuz audio segment does not contain its frame descriptor")

    val desc = descriptor
    var cursor = desc.offset + desc.headerSize + 16
    if (cursor + 12 > desc.end) throw IllegalStateException("Qobuz audio frame descriptor is truncated")

    cursor += 4
    val buf = ByteBuffer.wrap(input).order(ByteOrder.BIG_ENDIAN)
    val dataOffset = desc.offset + buf.getInt(cursor)
    cursor += 4
    val ivSize = input[cursor].toInt() and 0xFF
    cursor += 1
    val frameCount = ((input[cursor].toInt() and 0xFF) shl 16) or
        ((input[cursor + 1].toInt() and 0xFF) shl 8) or
        (input[cursor + 2].toInt() and 0xFF)
    cursor += 3

    if (ivSize < 1 || ivSize > 16 || cursor + frameCount * (8 + ivSize) > desc.end) {
        throw IllegalStateException("Qobuz audio frame table has an unknown format")
    }

    val frames = mutableListOf<AudioFrame>()
    for (i in 0 until frameCount) {
        val size = buf.getInt(cursor)
        val skip = buf.getShort(cursor + 4).toInt() and 0xFFFF
        val flags = buf.getShort(cursor + 6).toInt() and 0xFFFF
        cursor += 8
        val iv = ByteArray(16)
        System.arraycopy(input, cursor, iv, 0, min(ivSize, 16))
        cursor += ivSize
        frames.add(AudioFrame(size, skip, flags, iv))
    }

    return ParsedAudioSegment(input, dataOffset, frames, mediaEnd)
}

fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    val effectiveSalt = if (salt.isNotEmpty()) salt else ByteArray(32)
    mac.init(SecretKeySpec(effectiveSalt, "HmacSHA256"))
    val prk = mac.doFinal(ikm)

    mac.init(SecretKeySpec(prk, "HmacSHA256"))
    var t = ByteArray(0)
    val okm = ByteArray(length)
    var offset = 0
    var round = 1

    while (offset < length) {
        mac.reset()
        if (t.isNotEmpty()) mac.update(t)
        mac.update(info)
        mac.update(round.toByte())
        t = mac.doFinal()
        val toCopy = min(t.size, length - offset)
        System.arraycopy(t, 0, okm, offset, toCopy)
        offset += toCopy
        round++
    }
    return okm
}

fun deriveQobuzSessionKey(infos: String, rngInit: String): ByteArray {
    val parts = infos.split(".")
    if (parts.size < 2 || !rngInit.matches(Regex("^[a-fA-F0-9]{32}$"))) {
        throw IllegalStateException("Qobuz session key data is incomplete")
    }
    val salt = decodeBase64Url(parts[0])
    val info = decodeBase64Url(parts[1])
    val ikm = hexToBytes(rngInit)
    return hkdfSha256(ikm, salt, info, 16)
}

fun unwrapQobuzContentKey(sessionKey: ByteArray, wrappedValue: String): ByteArray {
    val parts = wrappedValue.split(".")
    if (parts.size < 3 || parts[0] != "qbz-1") {
        throw IllegalStateException("Qobuz content key has an unknown format")
    }
    val encrypted = decodeBase64Url(parts[1])
    val iv = decodeBase64Url(parts[2])

    val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(sessionKey, "AES"), IvParameterSpec(iv))
    val decrypted = cipher.doFinal(encrypted)
    if (decrypted.size != 16) {
        throw IllegalStateException("Qobuz content key has an invalid length (${decrypted.size})")
    }
    return decrypted
}

fun decryptQobuzAudioSegment(input: ByteArray, contentKey: ByteArray?): ByteArray {
    val parsed = parseQobuzAudioSegment(input)
    var position = parsed.dataOffset
    val chunks = mutableListOf<ByteArray>()

    for (frame in parsed.frames) {
        val end = position + frame.size
        if (end > parsed.data.size) throw IllegalStateException("Qobuz audio segment ends inside a FLAC frame")
        var bytes = parsed.data.copyOfRange(position, end)
        if (frame.flags != 0) {
            if (contentKey == null) throw IllegalStateException("Qobuz encrypted audio segment has no content key")
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(contentKey, "AES"), IvParameterSpec(frame.iv))
            bytes = cipher.doFinal(bytes)
        }
        chunks.add(bytes)
        position = end
    }
    if (position < parsed.mediaEnd) {
        chunks.add(parsed.data.copyOfRange(position, parsed.mediaEnd))
    }

    val totalBytes = chunks.sumOf { it.size }
    val result = ByteArray(totalBytes)
    var dest = 0
    for (chunk in chunks) {
        System.arraycopy(chunk, 0, result, dest, chunk.size)
        dest += chunk.size
    }
    return result
}
