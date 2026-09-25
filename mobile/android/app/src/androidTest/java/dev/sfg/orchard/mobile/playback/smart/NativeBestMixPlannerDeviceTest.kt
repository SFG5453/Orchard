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

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.sfg.orchard.mobile.model.Track
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeBestMixPlannerDeviceTest {
    private fun fixture(): JSONObject = InstrumentationRegistry.getInstrumentation().context.assets
        .open("native_bestmix_parity.json").bufferedReader().use { JSONObject(it.readText()) }

    @Test fun nativePairScoreMatchesDesktopOnEmulator() {
        assertTrue("C++ scorer did not load", NativeBestMixPlanner.available)
        val pairs = fixture().getJSONArray("pairs")
        for (index in 0 until pairs.length()) {
            val item = pairs.getJSONObject(index)
            val expected = item.getJSONObject("expected")
            val actual = checkNotNull(NativeBestMixPlanner.score(
                item.getJSONObject("analysis"), item.getJSONObject("nextAnalysis")))
            val name = item.getString("name")
            assertEquals(name, expected.getString("transitionClass"), actual.getString("transitionClass"))
            assertEquals(name, expected.getDouble("confidence"), actual.getDouble("confidence"), 1e-8)
            assertEquals(name, expected.getDouble("quality"), actual.getDouble("quality"), 1e-8)
            assertEquals(name, expected.optString("selectedId"), actual.optString("selectedId"))
            assertEquals(name, expected.getDouble("cost"), actual.getDouble("cost"), 1e-8)
        }
    }

    @Test fun nativeQueueOrderMatchesDesktopOnEmulator() {
        val queue = fixture().getJSONObject("queue")
        val analyses = queue.getJSONArray("analyses")
        val tracks = (0 until analyses.length()).map { index ->
            Track(id = index.toString(), title = "Track $index", artist = "Test", durationMs = 120_000)
        }
        val features = tracks.associate { track ->
            track.id to TrackFeatures.parse(analyses.getJSONObject(track.id.toInt()))
        }
        val actual = checkNotNull(NativeBestMixPlanner.sort(tracks, features, null)).map { it.id.toInt() }
        val expectedArray = queue.getJSONArray("expected")
        val expected = (0 until expectedArray.length()).map(expectedArray::getInt)
        assertEquals(expected, actual)
        assertEquals(expected, BestMixSorter.sort(tracks, features).map { it.id.toInt() })
    }

    @Test fun realV3PairReportsTimeAndPssWithoutRunningAudioModels() {
        val pairs = fixture().getJSONArray("pairs")
        val real = (0 until pairs.length()).map(pairs::getJSONObject)
            .first { it.getString("name") == "real-v3" }
        val before = Debug.getPss()
        val times = mutableListOf<Long>()
        repeat(5) {
            val start = SystemClock.elapsedRealtimeNanos()
            checkNotNull(NativeBestMixPlanner.score(
                real.getJSONObject("analysis"), real.getJSONObject("nextAnalysis")))
            times += (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
        }
        val after = Debug.getPss()
        Log.i("NativeBestMixProbe", "real-v3 timesMs=$times pssKiB=$before->$after")
    }

    @Test fun twentyTrackQueueReportsTimeAndPssWithoutRunningAudioModels() {
        val pairs = fixture().getJSONArray("pairs")
        val real = (0 until pairs.length()).map(pairs::getJSONObject)
            .first { it.getString("name") == "real-v3" }
        val outgoing = TrackFeatures.parse(real.getJSONObject("analysis"))
        val incoming = TrackFeatures.parse(real.getJSONObject("nextAnalysis"))
        val tracks = (0 until 20).map { index ->
            Track(id = index.toString(), title = "Track $index", artist = "Test", durationMs = 180_000)
        }
        val features = tracks.associate { track ->
            track.id to (if (track.id.toInt() % 2 == 0) outgoing else incoming)
        }
        val before = Debug.getPss()
        val start = SystemClock.elapsedRealtimeNanos()
        val sorted = checkNotNull(NativeBestMixPlanner.sort(tracks, features, null))
        val elapsed = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000
        val after = Debug.getPss()
        assertEquals(tracks.map(Track::id).toSet(), sorted.map(Track::id).toSet())
        Log.i("NativeBestMixProbe", "queue20 elapsedMs=$elapsed pssKiB=$before->$after")
    }
}
