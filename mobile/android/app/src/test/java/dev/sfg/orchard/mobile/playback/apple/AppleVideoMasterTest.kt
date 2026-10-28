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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppleVideoMasterTest {
    private val url = "https://play-edge.itunes.apple.com/hls/master.m3u8?a=1"
    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio-stereo-128",NAME="English",URI="audio_128.m3u8"
        #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=94218,RESOLUTION=672x376,URI="iframes.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=2204144,AUDIO="audio-stereo-128",RESOLUTION=1024x574,ALLOWED-CPC="com.microsoft.playready:SL2000,urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed:WIDEVINE_HARDWARE"
        https://mvod.itunes.apple.com/v/574.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=296930,AUDIO="audio-stereo-128",RESOLUTION=478x268,ALLOWED-CPC="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed:WIDEVINE_SOFTWARE"
        v/268.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=1618968,AUDIO="audio-stereo-128",RESOLUTION=864x484,ALLOWED-CPC="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed:WIDEVINE_SOFTWARE"
        v/484.m3u8
    """.trimIndent()

    @Test
    fun softwareWidevineKeepsSoftwareRenditionsWithAbsoluteUris() {
        val filtered = filterMaster(master, url, hardware = false, maxHeight = 0)
        assertFalse("574.m3u8" in filtered)
        assertFalse("I-FRAME" in filtered)
        assertTrue("https://play-edge.itunes.apple.com/hls/v/268.m3u8" in filtered)
        assertTrue("https://play-edge.itunes.apple.com/hls/v/484.m3u8" in filtered)
        assertTrue("URI=\"https://play-edge.itunes.apple.com/hls/audio_128.m3u8\"" in filtered)
    }

    @Test
    fun hardwareWidevineAddsHdAndHeightCaps() {
        assertTrue("574.m3u8" in filterMaster(master, url, hardware = true, maxHeight = 0))
        val capped = filterMaster(master, url, hardware = true, maxHeight = 480)
        assertEquals(listOf("https://play-edge.itunes.apple.com/hls/v/268.m3u8"), capped.lines().filter { it.startsWith("https") })
        val below = filterMaster(master, url, hardware = true, maxHeight = 144)
        assertTrue("v/268.m3u8" in below)
        assertFalse("v/484.m3u8" in below)
    }

    // Apple's Widevine key for "bad guy" (version 0 pssh, WidevinePsshData key_id 00000000317effc76330202020202020).
    private val media = """
        #EXTM3U
        #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://itunes.apple.com/p830406599/c1",KEYFORMAT="com.apple.streamingkeydelivery",KEYFORMATVERSIONS="1"
        #EXT-X-KEY:METHOD=SAMPLE-AES,URI="data:text/plain;base64,AAAAOHBzc2gAAAAA7e+LqXnWSs6jyCfc1R0h7QAAABgSEAAAAAAxfv/HYzAgICAgICBI88aJmwY=",KEYFORMAT="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed",KEYFORMATVERSIONS="1"
        #EXT-X-MAP:URI="P830_video_--0.mp4"
        #EXTINF:6.0,
        P830_video_--1.m4s
    """.trimIndent()

    @Test
    fun initSegmentKeyComesFromThePlaylistPssh() {
        val (init, keyId) = initSegmentKey(media, "https://mvod.itunes.apple.com/v/variant.m3u8?accessKey=x")!!
        assertEquals("https://mvod.itunes.apple.com/v/P830_video_--0.mp4", init)
        assertEquals("00000000317effc76330202020202020", keyId.joinToString("") { "%02x".format(it) })
    }

    @Test
    fun withKeyIdReplacesOnlyAnAllZeroTencKey() {
        val keyId = ByteArray(16) { (it + 1).toByte() }
        // size, "tenc", version/flags, two reserved bytes, isProtected, IV size, zero key id.
        val tenc = byteArrayOf(0, 0, 0, 32) + "tenc".toByteArray() + ByteArray(4) + byteArrayOf(0, 0, 1, 0) + ByteArray(16)
        val init = ByteArray(8) + tenc
        val patched = withKeyId(init, keyId)
        assertTrue(patched.copyOfRange(init.size - 16, init.size).contentEquals(keyId))
        assertTrue(withKeyId(patched, ByteArray(16)).contentEquals(patched))
    }
}
