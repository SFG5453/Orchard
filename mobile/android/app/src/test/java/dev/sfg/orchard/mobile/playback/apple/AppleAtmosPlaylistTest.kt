package dev.sfg.orchard.mobile.playback.apple

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.Base64

class AppleAtmosPlaylistTest {
    private fun pssh(id: ByteArray): String = Base64.getEncoder().encodeToString(AppleMusicStream.widevinePssh(id))
    private fun key(id: ByteArray) = "#EXT-X-KEY:METHOD=SAMPLE-AES,URI=\"data:text/plain;base64,${pssh(id)}\",KEYFORMAT=\"urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed\""
    private val first = ByteArray(16) { 1 }
    private val second = ByteArray(16) { 2 }
    private val master = """
        #EXTM3U
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="stereo",CHANNELS="2"
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="atmos",CHANNELS="16/JOC"
        #EXT-X-STREAM-INF:BANDWIDTH=768000,CODECS="ec-3",AUDIO="atmos"
        variants/joc.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=256000,AUDIO="stereo"
        stereo.m3u8
    """.trimIndent()

    @Test fun selectsAtmosAndMapsRotatingKeys() {
        assertEquals(AtmosVariant("https://example.com/variants/joc.m3u8", 768), atmosVariant(master, "https://example.com/master.m3u8"))
        val average = master.replace("BANDWIDTH=768000", "AVERAGE-BANDWIDTH=770179,BANDWIDTH=771138")
        assertEquals(770, atmosVariant(average, "https://example.com/master.m3u8").bitrateKbps)
        val playlist = "#EXTM3U\n${key(first)}\n#EXT-X-MAP:URI=\"song.mp4\",BYTERANGE=\"100@0\"\n" +
            "#EXTINF:15,\n#EXT-X-BYTERANGE:10@100\nsong.mp4\n${key(second)}\n#EXTINF:15,\n#EXT-X-BYTERANGE:10@110\nsong.mp4\n"
        val parsed = atmosPlaylist(playlist, "https://example.com/variants/joc.m3u8")
        assertEquals("https://example.com/variants/song.mp4", parsed.initPath)
        assertTrue(parsed.text.contains("URI=\"https://example.com/variants/song.mp4\""))
        assertTrue(parsed.text.contains("\nhttps://example.com/variants/song.mp4\n"))
        assertArrayEquals(first, parsed.keys[0])
        assertArrayEquals(second, parsed.keys[1])
        val tenc = byteArrayOf(0, 0, 0, 32) + "tenc".toByteArray() + ByteArray(4) + byteArrayOf(0, 0, 1, 0) + ByteArray(16)
        val patched = patchAtmosInit(tenc + tenc, parsed.keys)
        assertArrayEquals(first, patched.copyOfRange(16, 32))
        assertArrayEquals(second, patched.copyOfRange(48, 64))
    }

    @Test fun rejectsUnexpectedKeysAndMissingAtmos() {
        assertThrows(IOException::class.java) { atmosVariant("#EXTM3U", "https://example.com/master") }
        assertThrows(IOException::class.java) { patchAtmosInit(ByteArray(32), listOf(first)) }
        assertThrows(IOException::class.java) { atmosPlaylist("#EXTM3U", "https://example.com/playlist") }
    }
}
