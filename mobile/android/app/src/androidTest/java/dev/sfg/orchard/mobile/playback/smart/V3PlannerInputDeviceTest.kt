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
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.util.Log
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

/** Real-song device probe of bounded playback windows and desktop vocal-risk evidence. */
@RunWith(AndroidJUnit4::class)
class V3PlannerInputDeviceTest {
    private var retainedHeapPressure: ByteArray? = null

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
        val pressureMb = InstrumentationRegistry.getArguments().getString("heapPressureMb")
            ?.toIntOrNull()?.coerceIn(0, 180) ?: 0
        val measureMemory = InstrumentationRegistry.getArguments().getString("measureMemory") == "true"
        val shortModelPath = InstrumentationRegistry.getArguments().getString("beatShortGpuModelPath")
        val shortFrames = InstrumentationRegistry.getArguments().getString("beatShortFrames")
            ?.toIntOrNull() ?: BeatTracker.GPU_CHUNK_FRAMES
        assertTrue("Invalid beat window length: $shortFrames", shortFrames in 128..BeatTracker.CHUNK_FRAMES)
        val shortModel = shortModelPath?.let(::File)
        if (shortModel != null) assertTrue("Missing probe model: $shortModel", shortModel.isFile)
        fun logMemory(stage: String) {
            if (!measureMemory) return
            val memory = Debug.MemoryInfo()
            Debug.getMemoryInfo(memory)
            val status = File("/proc/self/status").readLines()
            val rss = status.firstOrNull { it.startsWith("VmRSS:") }?.substringAfter(':')?.trim()
            Log.i("V3PlannerInputDeviceTest", "$stage pssKb=${memory.totalPss} " +
                "nativeHeapKb=${Debug.getNativeHeapAllocatedSize() / 1024} VmRSS=$rss")
        }
        retainedHeapPressure = ByteArray(pressureMb * 1024 * 1024).also { bytes ->
            // Commit every page; an untouched zero-filled array barely registers in process PSS.
            for (index in bytes.indices step 4096) bytes[index] = 1
        }
        logMemory("after committed heap pressure")
        val tracker = BeatTracker(context, shortModel, shortFrames)
        val windows = listOf(
            Triple("illegal", 89.661, 149.661),
            Triple("girl_like_me", 0.0, 144.801),
        )
        val payloads = mutableMapOf<String, JSONObject>()
        val beatRegions = mutableMapOf<String, Pair<AudioDecoder.Pcm, BeatTracker.Grid>>()
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
                    AudioDecoder.decodeRegion(source, start, start + 60.0, targetRate = 44_100)
                }
                assertNotNull(decoded)
                val (pcm, actualStart) = decoded!!
                val first = ((start - actualStart) * pcm.sampleRate).toInt().coerceAtLeast(0)
                val length = minOf((60 * pcm.sampleRate).toInt(), pcm.samples.size - first)
                assertTrue(length > 40 * pcm.sampleRate)
                val mono = if (first == 0 && length == pcm.samples.size) pcm.samples
                    else pcm.samples.copyOfRange(first, first + length)
                val beatPcm = MelSpectrogram.resample(mono, pcm.sampleRate)!!
                logMemory("$name before beat")
                val grid = tracker.track(beatPcm, start)!!
                logMemory("$name after beat")
                beatRegions[name] = AudioDecoder.Pcm(mono, pcm.sampleRate) to grid
            }
            tracker.release()
            logMemory("after beat batch model release")
            for ((name, start, duration) in windows) {
                val (pcm, grid) = beatRegions.getValue(name)
                val json = TrackFeatures.plannerWindow(
                    pcm.samples, pcm.sampleRate, start, duration, grid,
                )!!
                logMemory("$name after structural analysis")
                val payload = JSONObject(json)
                val frames = payload.getJSONArray("transitionFeatureFrames")
                assertTrue("Missing spectral vocal evidence for $name", frames.length() > 0)
                for (index in 0 until frames.length()) {
                    val vocal = frames.getJSONObject(index).optDouble("vocal", Double.NaN)
                    assertTrue("Invalid vocal evidence for $name frame $index", vocal in 0.0..1.0)
                }
                payloads[name] = payload
            }
            val result = JSONObject()
                .put("analysis", payloads.getValue("illegal"))
                .put("nextAnalysis", payloads.getValue("girl_like_me"))
                .put("duration", 149.661)
                .put("nextDuration", 144.801)
            val selected = DesktopTransitionPlanner.invoke("native", result)
            result.put("selectedPlan", selected)
            result.put("beatModelFrames", shortFrames)
            val outputName = if (shortModel == null) "v3-planner-input-device.json"
                else "v3-planner-input-device-$shortFrames.json"
            val output = File(context.getExternalFilesDir(null), outputName)
            output.writeText(result.toString(2))
            assertTrue(output.length() > 1000)
            val pairPlan = selected.getJSONObject("pairPlan")
            val reasonCounts = pairPlan.getJSONObject("diagnostics").getJSONObject("reasonCounts")
            assertTrue("Expected the vocal collision gate on this pair: $reasonCounts",
                reasonCounts.optInt("vocal-collision", 0) > 0)
        } finally {
            tracker.release()
            retainedHeapPressure = null
        }
    }
}
