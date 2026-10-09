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

package dev.sfg.orchard.mobile.playback.smart

import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Times one song through Best Mix's decode and analysis on the device.
 * adb push song.webm /data/local/tmp/orchard-bench.audio, then run with
 * -e class dev.sfg.orchard.mobile.playback.smart.BestMixBenchmark
 */
class BestMixBenchmark {
    @Test
    fun decodeAndAnalyze() {
        val path = InstrumentationRegistry.getArguments().getString("audio") ?: "/data/local/tmp/orchard-bench.audio"
        val file = File(path)
        assumeTrue("no benchmark audio at $path", file.canRead())
        repeat(2) { round ->
            val started = System.nanoTime()
            val song = FileMediaDataSource(file).use { SongDecoder.decode(it) }!!
            val decoded = System.nanoTime()
            val result = song.use { JSONObject(MixNative.nativeBestMixAnalyze(it.handle, it.duration)) }
            val analyzed = System.nanoTime()
            Log.i(TAG, "round $round: ${"%.1f".format(song.duration)} s song, decode " +
                "${(decoded - started) / 1_000_000} ms, analysis ${(analyzed - decoded) / 1_000_000} ms, " +
                "bpm ${result.optJSONObject("tail")?.optDouble("bpm")} key ${result.optJSONObject("head")?.optString("key")} " +
                "beats ${result.optJSONObject("head")?.optJSONArray("beats")?.let { b -> (0 until 3).map { b.getDouble(it) } }} " +
                "${result.optJSONObject("head")?.optJSONArray("beats")?.length()} ${result.optString("error")}")
        }
    }

    /**
     * Sorts /data/local/tmp/bestmix (manifest.txt of id|duration, then <id>.audio) as Best Mix
     * would, first song playing; logs features and order for comparison with desktop.
     */
    @Test
    fun sortParity() {
        val dir = File("/data/local/tmp/bestmix")
        val manifest = File(dir, "manifest.txt")
        assumeTrue(manifest.canRead())
        val songs = manifest.readLines().filter { it.isNotBlank() }.map { it.substringBefore('|') to it.substringAfter('|').toDouble() }
        val features = songs.associate { (id, duration) ->
            val song = FileMediaDataSource(File(dir, "$id.audio")).use { SongDecoder.decode(it) }!!
            id to song.use { JSONObject(MixNative.nativeBestMixAnalyze(it.handle, duration)) }
        }
        features.forEach { (id, f) -> Log.i(TAG, "features $id ${f.optJSONObject("headSummary")} ${f.optJSONObject("tailSummary")}") }
        val current = songs.first().first
        val snapshot = songs.drop(1).map { it.first }
        val summaries = JSONArray(snapshot.map {
            JSONObject().put("head", features[it]!!.getJSONObject("headSummary")).put("tail", features[it]!!.getJSONObject("tailSummary"))
        })
        val reply = JSONObject(MixNative.nativeBestMixSort(summaries.toString(),
            features[current]!!.getJSONObject("tailSummary").toString()) { left, right ->
            val l = if (left == -1) current else snapshot[left]
            JSONObject().put("left", features[l]!!.getJSONObject("tail"))
                .put("right", features[snapshot[right]]!!.getJSONObject("head")).toString()
        })
        val order = reply.getJSONArray("order")
        Log.i(TAG, "order ${(0 until order.length()).map { snapshot[order.getInt(it)] }}")
    }

    /** Writes the Kopus decode of orchard-bench.audio as raw interleaved f32 for diffing on desktop. */
    @Test
    fun dumpOpus() {
        val file = File("/data/local/tmp/orchard-bench.audio")
        assumeTrue(file.canRead())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(context.getExternalFilesDir(null), "opus.raw").outputStream().buffered()
        eu.buney.kopus.OpusLoader.load()
        var decoder: eu.buney.kopus.OpusDecoder? = null
        var channels = 0
        var skip = 0
        var packets = 0
        val pcm = FloatArray(5760 * 2)
        val bytes = java.nio.ByteBuffer.allocate(5760 * 2 * 4).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        FileMediaDataSource(file).use { source ->
            OpusPackets.read(source, onFormat = { format ->
                channels = format.channelCount
                skip = format.initializationData[0].let { (it[10].toInt() and 0xFF) or ((it[11].toInt() and 0xFF) shl 8) }
                Log.i(TAG, "format $format skip $skip init ${format.initializationData.map { it.size }}")
                decoder = eu.buney.kopus.OpusDecoder(48_000, channels)
                true
            }) { packet, size, discardNs ->
                packets++
                if (discardNs > 0) Log.i(TAG, "discard $discardNs ns at packet $packets")
                val frames = decoder!!.decode(packet, 0, size, pcm, 0, 5760, false) - ((discardNs * 48_000 + 500_000_000L) / 1_000_000_000L).toInt()
                bytes.clear()
                for (i in 0 until frames * channels) bytes.putFloat(pcm[i])
                out.write(bytes.array(), 0, frames * channels * 4)
                true
            }
        }
        out.close()
        Log.i(TAG, "packets $packets channels $channels")
    }

    private companion object {
        const val TAG = "OrchardBestMixBench"
    }
}
