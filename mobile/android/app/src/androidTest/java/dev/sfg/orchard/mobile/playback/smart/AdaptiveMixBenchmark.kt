/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

package dev.sfg.orchard.mobile.playback.smart

import android.os.Process
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Two consecutive adaptive mixes on the device, as playback prepares them: A into B, then B into C.
 * adb push the songs to /data/local/tmp/orchard-mix-out.audio, orchard-mix-in.audio and orchard-mix-next.audio.
 */
class AdaptiveMixBenchmark {
    @Test
    fun preparePairs() {
        val songs = listOf("out", "in", "next").map { File("/data/local/tmp/orchard-mix-$it.audio") }
        assumeTrue(songs.all(File::canRead))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Same deep stack and priority AdaptiveMixer gives the planner; the first pair loads the models.
        val thread = Thread(null, {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE)
            var models = 0L
            val provider = {
                if (models == 0L) models = AdaptiveMix.loadModels(context) ?: 0L
                models.takeIf { it != 0L }
            }
            // What MixEvidenceStore would hold: song B's evidence from the first pair serves the second.
            val measured = mutableMapOf<String, JSONObject>()
            for ((run, pair) in listOf("first pair" to 0, "next pair" to 1)) {
                val (outgoing, incoming) = songs[pair] to songs[pair + 1]
                val duration = FileMediaDataSource(outgoing).use { SongDecoder.decode(it) }!!.use { it.duration }
                val request = JSONObject().put("outgoingDuration", duration).put("position", duration - 120)
                    .put("currentTrack", JSONObject().put("id", "song$pair").put("title", "Song $pair"))
                    .put("nextTrack", JSONObject().put("id", "song${pair + 1}").put("title", "Song ${pair + 1}"))
                    .put("fadeSeconds", 6)
                measured["song$pair"]?.let { request.put("outgoingMeasured", it) }
                measured["song${pair + 1}"]?.let { request.put("incomingMeasured", it) }
                val started = System.nanoTime()
                val result = AdaptiveMix.prepare(provider, { FileMediaDataSource(outgoing) },
                    { FileMediaDataSource(incoming) }, request) { header ->
                    header.optJSONObject("outgoingMeasured")?.let { measured["song$pair"] = it }
                    header.optJSONObject("incomingMeasured")?.let { measured["song${pair + 1}"] = it }
                }
                val elapsed = (System.nanoTime() - started) / 1_000_000
                when (result) {
                    is PreparedMix.Ready -> Log.i(TAG, "$run ready in $elapsed ms: ${result.log}")
                    is PreparedMix.NoMix -> Log.i(TAG, "$run no mix in $elapsed ms: ${result.reason}")
                    is PreparedMix.Failed -> Log.i(TAG, "$run failed in $elapsed ms: ${result.reason}")
                }
            }
            if (models != 0L) AdaptiveMix.freeModels(models)
        }, "mix", 8L shl 20)
        thread.start()
        thread.join()
    }

    private companion object {
        const val TAG = "OrchardMixBench"
    }
}
