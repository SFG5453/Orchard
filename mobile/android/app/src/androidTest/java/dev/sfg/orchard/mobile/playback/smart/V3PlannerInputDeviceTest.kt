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

import android.media.MediaDataSource
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs

/** Real-song device probe of the same two bounded windows that orchardv3 plans on. */
@RunWith(AndroidJUnit4::class)
class V3PlannerInputDeviceTest {
    private class FileSource(file: File) : MediaDataSource() {
        private val source = RandomAccessFile(file, "r")
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            source.seek(position)
            return source.read(buffer, offset, size)
        }
        override fun getSize(): Long = source.length()
        override fun close() = source.close()
    }

    @Test
    fun pinkPantheressPlannerWindows() {
        assumeTrue("Requires the local PinkPantheress media probe",
            InstrumentationRegistry.getArguments().getString("pinkPantheressParity") == "true")
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val tracker = BeatTracker(context)
        val windows = listOf(
            Triple("illegal", 89.661, 149.661),
            Triple("girl_like_me", 0.0, 144.801),
        )
        val payloads = mutableMapOf<String, JSONObject>()
        try {
            for ((name, start, duration) in windows) {
                val file = File(context.cacheDir, "$name.webm")
                instrumentation.uiAutomation.executeShellCommand(
                    "cat /data/local/tmp/orchard-v3-match/$name.webm",
                ).use { descriptor ->
                    ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                        file.outputStream().use(input::copyTo)
                    }
                }
                assertTrue(file.length() > 100_000)
                val decoded = FileSource(file).use { source ->
                    AudioDecoder.decodeRegionStereo(source, start, start + 60.0, targetRate = 44_100)
                }
                assertNotNull(decoded)
                val (stereo, actualStart) = decoded!!
                val first = ((start - actualStart) * stereo.sampleRate).toInt().coerceAtLeast(0)
                val length = minOf((60 * stereo.sampleRate).toInt(), stereo.left.size - first)
                assertTrue(length > 40 * stereo.sampleRate)
                val mono = FloatArray(length) { index ->
                    (stereo.left[first + index] + stereo.right[first + index]) * 0.5f
                }
                val beatPcm = MelSpectrogram.resample(mono, stereo.sampleRate)!!
                val grid = tracker.track(beatPcm, start)!!
                val json = TrackFeatures.plannerWindow(mono, stereo.sampleRate, start, duration, grid)!!
                payloads[name] = JSONObject(json)
            }
            val result = JSONObject()
                .put("analysis", payloads.getValue("illegal"))
                .put("nextAnalysis", payloads.getValue("girl_like_me"))
                .put("duration", 149.661)
                .put("nextDuration", 144.801)
            val selected = DesktopTransitionPlanner.invoke("native", result)
            assertTrue("Expected the seven-second v3 bass swap: $selected",
                selected.optBoolean("ok") && selected.optString("strategy") == "bass_swap" &&
                    abs(selected.optDouble("overlapSeconds") - 7.0) < 0.1 &&
                    abs(selected.optDouble("transitionStart") - 132.701) < 0.25 &&
                    abs(selected.optDouble("incomingCueTime") - 44.553) < 0.25)
            result.put("selectedPlan", selected)
            val output = File(context.getExternalFilesDir(null), "v3-planner-input-device.json")
            output.writeText(result.toString(2))
            assertTrue(output.length() > 1000)
        } finally {
            tracker.release()
        }
    }
}
