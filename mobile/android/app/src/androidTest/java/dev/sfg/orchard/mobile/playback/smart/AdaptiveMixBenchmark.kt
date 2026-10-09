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

import android.os.Process
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * One real adaptive mix on the device, as playback prepares it.
 * adb push out.webm /data/local/tmp/orchard-mix-out.audio and in.webm to orchard-mix-in.audio.
 */
class AdaptiveMixBenchmark {
    @Test
    fun preparePair() {
        val outgoing = File("/data/local/tmp/orchard-mix-out.audio")
        val incoming = File("/data/local/tmp/orchard-mix-in.audio")
        assumeTrue(outgoing.canRead() && incoming.canRead())
        val duration = FileMediaDataSource(outgoing).use { SongDecoder.decode(it) }!!.use { it.duration }
        val request = JSONObject().put("outgoingDuration", duration).put("position", duration - 120)
            .put("currentTrack", JSONObject().put("id", "out").put("title", "Song A"))
            .put("nextTrack", JSONObject().put("id", "in").put("title", "Song B"))
            .put("fadeSeconds", 6)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Same deep stack and priority AdaptiveMixer gives the planner; cold run loads the models.
        val thread = Thread(null, {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE)
            var models = 0L
            val provider = {
                if (models == 0L) models = AdaptiveMix.loadModels(context) ?: 0L
                models.takeIf { it != 0L }
            }
            for (run in listOf("cold", "warm")) {
                val started = System.nanoTime()
                val result = AdaptiveMix.prepare(provider, { FileMediaDataSource(outgoing) },
                    { FileMediaDataSource(incoming) }, request)
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
