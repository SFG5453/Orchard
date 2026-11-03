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

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import java.nio.ByteBuffer
import java.util.Base64

class AppleMusicStreamTest {
    private val keyId = Base64.getDecoder().decode("AAAAACmOW8YAHbtJTWkWjg==")

    private fun box(type: String, body: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray()).put(body).array()

    // Version 0 full boxes with the field of interest 12 bytes after version and flags.
    private fun fullBox(type: String, value: Int) =
        box(type, ByteBuffer.allocate(16).putInt(0).putInt(0).putInt(0).putInt(value).array())

    private fun init(vararg moovExtra: ByteArray): ByteArray {
        val trak = box("trak", fullBox("tkhd", 1) + box("mdia", fullBox("mdhd", 44_100)))
        val moov = box("moov", moovExtra.fold(trak) { acc, extra -> acc + extra })
        return box("ftyp", "iso6".toByteArray()) + moov
    }

    private fun stream(): AppleMusicStream = AppleMusicStream.parse(JSONObject()
        .put("media", "https://aod-ssl.itunes.apple.com/x/song.mp4")
        .put("keyUri", "data:;base64,AAAAACmOW8YAHbtJTWkWjg==")
        .put("ranges", JSONArray().put(JSONArray().put(0).put(1006)).put(JSONArray().put(1006).put(381704))
            .put(JSONArray().put(382710).put(326173)))
        .put("durations", JSONArray().put(9.98458).put(9.98458)), lifetimeMs = 1000, now = 0)

    @Test
    fun buildsWidevinePsshFromKeyId() {
        val pssh = stream().pssh
        assertEquals(50, pssh.size)
        assertEquals("pssh", String(pssh, 4, 4))
        assertArrayEquals(byteArrayOf(0x12, 0x10) + keyId, pssh.copyOfRange(32, 50))
    }

    @Test
    fun addsPsshToTheMoovOnce() {
        val original = init()
        val pssh = AppleMusicStream.widevinePssh(keyId)
        val patched = AppleMusicStream.withPssh(original, pssh)!!
        assertEquals(original.size + pssh.size, patched.size)
        val moovAt = 12
        assertEquals(original.size - moovAt + pssh.size, ByteBuffer.wrap(patched, moovAt, 4).int)
        assertArrayEquals(pssh, patched.copyOfRange(patched.size - pssh.size, patched.size))
        assertSame(patched, AppleMusicStream.withPssh(patched, pssh))
    }

    @Test
    fun indexesEveryFragmentAfterTheHead() {
        val stream = stream()
        val head = stream.buildHead(init())!!
        stream.head = head
        val sidx = ByteBuffer.wrap(head, head.size - 56, 56)
        assertEquals(56, sidx.int)
        assertEquals("sidx", String(head, head.size - 52, 4))
        sidx.position(sidx.position() + 8)
        assertEquals(1, sidx.int)
        assertEquals(44_100, sidx.int)
        sidx.position(sidx.position() + 8)
        assertEquals(0, sidx.short.toInt())
        assertEquals(2, sidx.short.toInt())
        assertEquals(381704, sidx.int)
        assertEquals(440_320, sidx.int)
        assertEquals(0x90000000.toInt(), sidx.int)
        assertEquals(326173, sidx.int)
        assertEquals(head.size - 1006L, stream.shift)
        assertEquals(382710L + 326173L + stream.shift, stream.servedSize)
    }

    @Test
    fun refusesInitsWithoutTrackTiming() {
        assertNull(stream().buildHead(box("ftyp", "iso6".toByteArray()) + box("moov", ByteArray(0))))
    }
}
