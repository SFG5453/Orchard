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

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class QobuzCoreTest {

    private fun mp4Box(type: String, vararg parts: ByteArray): ByteArray {
        val totalPartSize = parts.sumOf { it.size }
        val header = ByteArray(8)
        val buf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        buf.putInt(8 + totalPartSize)
        buf.put(type.toByteArray(Charsets.US_ASCII), 0, 4)

        val result = ByteArray(8 + totalPartSize)
        System.arraycopy(header, 0, result, 0, 8)
        var offset = 8
        for (part in parts) {
            System.arraycopy(part, 0, result, offset, part.size)
            offset += part.size
        }
        return result
    }

    @Test
    fun `extractBootstrap parses private credentials from web bundle`() {
        val rawSeed = "0123456789abcdef0123456789abcdef".toByteArray(Charsets.UTF_8)
        val encoded = Base64.getEncoder().encodeToString(rawSeed)
        val seed = encoded.substring(0, 30)
        val info = "${encoded.substring(30)}${"x".repeat(14)}"
        val extras = "y".repeat(30)
        val bundle = listOf(
            """production:{api:{appId:"123456789",appSecret:"unused"""",
            """authenticate({privateKey:"runtime-oauth",code:value})""",
            """initialSeed("$seed",window.utimezone.berlin)""",
            """name:"Europe/Berlin",info:"$info",extras:"$extras""""
        ).joinToString(";")

        val result = QobuzBootstrapLoader.extractBootstrap(bundle, "/resources/test/bundle.js")
        assertEquals("123456789", result.appId)
        assertEquals("runtime-oauth", result.oauthPrivateKey)
        assertEquals("0123456789abcdef0123456789abcdef", result.rngInit)
    }

    @Test
    fun `qobuzRequestSignature sorts parameters deterministically`() {
        val sig1 = qobuzRequestSignature("fileurl", mapOf("track_id" to 42, "format_id" to 27, "intent" to "stream"), "123", "secret")
        val sig2 = qobuzRequestSignature("fileurl", mapOf("intent" to "stream", "format_id" to 27, "track_id" to 42), "123", "secret")
        assertEquals(sig1, sig2)
    }

    @Test
    fun `selectQobuzMatch prefers highest quality exact ISRC recording`() {
        val cand1 = JSONObject().apply {
            put("id", 1L)
            put("title", "Example Song")
            put("isrc", "USABC1200001")
            put("duration", 180)
            put("maximum_bit_depth", 16)
            put("maximum_sampling_rate", 44.1)
        }
        val cand2 = JSONObject().apply {
            put("id", 2L)
            put("title", "Example Song")
            put("isrc", "USABC1200001")
            put("duration", 180)
            put("hires", true)
            put("maximum_bit_depth", 24)
            put("maximum_sampling_rate", 192.0)
        }

        val match = selectQobuzMatch(
            targetTitle = "Example Song",
            targetArtists = listOf("Example Artist"),
            targetAlbum = "Example",
            targetDurationMs = 180_000L,
            targetIsrc = "USABC1200001",
            targetExplicit = false,
            candidates = listOf(cand1, cand2),
            method = "isrc",
        )

        assertNotNull(match)
        assertEquals(2L, match!!.qobuzTrackId)
        assertEquals("isrc", match.method)
        assertEquals(true, match.hires)
        assertEquals(24, match.bitDepth)
    }

    @Test
    fun `selectQobuzMatch rejects live substitute for studio version`() {
        val cand = JSONObject().apply {
            put("id", 3L)
            put("title", "Example Song (Live)")
            put("duration", 180)
            put("performer", JSONObject().put("name", "Example Artist"))
        }

        val match = selectQobuzMatch(
            targetTitle = "Example Song",
            targetArtists = listOf("Example Artist"),
            targetAlbum = "Example",
            targetDurationMs = 180_000L,
            targetIsrc = "",
            targetExplicit = false,
            candidates = listOf(cand),
        )

        assertNull(match)
    }

    @Test
    fun `parseQobuzInitSegment parses FLAC streaminfo and seektable`() {
        val flac = ByteArray(42)
        val flacBuf = ByteBuffer.wrap(flac).order(ByteOrder.BIG_ENDIAN)
        flacBuf.put("fLaC".toByteArray(Charsets.US_ASCII))
        flac[4] = 0
        flacBuf.putShort(8, 4096.toShort())
        flacBuf.putShort(10, 4096.toShort())
        val packed = (96000L shl 44) or (1L shl 41) or (23L shl 36) or 480000L
        flacBuf.putLong(18, packed)

        val segmentTablePayload = ByteArray(8)
        val segBuf = ByteBuffer.wrap(segmentTablePayload).order(ByteOrder.BIG_ENDIAN)
        segBuf.putInt(0, 5) // byte length
        segBuf.putInt(4, 10) // sample count

        val initPayloadParts = listOf(
            ByteArray(26),
            byteArrayOf(0, flac.size.toByte()),
            flac,
            byteArrayOf(0, 0, 1),
            segmentTablePayload,
        )
        val initPayloadTotal = initPayloadParts.sumOf { it.size }
        val initPayload = ByteArray(initPayloadTotal)
        var offset = 0
        for (part in initPayloadParts) {
            System.arraycopy(part, 0, initPayload, offset, part.size)
            offset += part.size
        }

        val initBox = mp4Box("uuid", QBZ_INIT_UUID, initPayload)
        val parsed = parseQobuzInitSegment(initBox)

        assertEquals(24, parsed.bitDepth)
        assertEquals(96000, parsed.sampleRate)
        assertEquals(2, parsed.channels)
        assertEquals(480000L, parsed.totalSamples)
        assertEquals(1, parsed.seekPoints.size)
        assertEquals(0L, parsed.seekPoints[0].sampleNumber)
        assertEquals(0L, parsed.seekPoints[0].streamOffset)
        assertEquals(4096, parsed.seekPoints[0].frameSamples)
    }

    @Test
    fun `decryptQobuzAudioSegment decrypts AES-128-CTR audio frames`() {
        val key = hexToBytes("00112233445566778899aabbccddeeff")
        val shortIv = hexToBytes("0123456789abcdef")
        val iv = ByteArray(16)
        System.arraycopy(shortIv, 0, iv, 0, 8)

        val plaintext = "frame".toByteArray(Charsets.UTF_8)
        val cipher = Cipher.getInstance("AES/CTR/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        val encrypted = cipher.doFinal(plaintext)

        val descriptorPayload = ByteArray(28)
        val descBuf = ByteBuffer.wrap(descriptorPayload).order(ByteOrder.BIG_ENDIAN)
        descBuf.putInt(4, 60)
        descriptorPayload[8] = 8 // ivSize
        descriptorPayload[9] = 0
        descriptorPayload[10] = 0
        descriptorPayload[11] = 1 // frameCount = 1
        descBuf.putInt(12, encrypted.size)
        descBuf.putShort(18, 1.toShort()) // flags = 1 (encrypted)
        System.arraycopy(shortIv, 0, descriptorPayload, 20, 8)

        val audio = mp4Box(
            "uuid",
            QBZ_SEGMENT_UUID,
            descriptorPayload
        ) + mp4Box("mdat", encrypted)

        val decrypted = decryptQobuzAudioSegment(audio, key)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test
    fun `formatPlaybackQualityLabel formats Hi-Res and Lossless tiers`() {
        assertEquals(
            "Qobuz Hi-Res · 24-bit / 96 kHz",
            formatPlaybackQualityLabel(playbackSource = "qobuz", hires = true, bitDepth = 24, sampleRate = 96000),
        )
        assertEquals(
            "Qobuz Lossless · 16-bit / 44.1 kHz",
            formatPlaybackQualityLabel(playbackSource = "qobuz", hires = false, bitDepth = 16, sampleRate = 44100),
        )
        assertEquals(
            "",
            formatPlaybackQualityLabel(playbackSource = "youtube", hires = false, bitDepth = null, sampleRate = null),
        )
    }
}
