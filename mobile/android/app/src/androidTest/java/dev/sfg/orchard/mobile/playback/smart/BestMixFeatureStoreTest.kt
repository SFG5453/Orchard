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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.sfg.orchard.mobile.playback.smart

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test

class BestMixFeatureStoreTest {
    @Test
    fun sqliteResultSurvivesAStoreReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(BestMixFeatureStore.DATABASE_NAME)
        val expected = TrackFeatures.Features(
            duration = 210.0,
            bpm = 124.0,
            beatInterval = 60.0 / 124.0,
            firstBeat = 0.2,
            beatConfidence = 0.86,
            key = "A Minor",
            keyConfidence = 0.91,
            audibleStartTime = 0.1,
            pickupTime = 0.1,
            introEndTime = 12.0,
            outroStartTime = 190.0,
            contentEndTime = 209.0,
            mixInTime = 8.0,
            mixOutTime = 198.0,
            vocalProbability = 0.25,
            downbeats = listOf(0.2, 2.135),
            phraseBoundaries = listOf(0.2, 15.68),
            vocalActivityMask = listOf(0.1, 0.2),
            energyCurve = listOf(EnergySample(0.0, 0.4), EnergySample(1.0, 0.8)),
            lowEnergyCurve = listOf(EnergySample(0.0, 0.2), EnergySample(1.0, 0.5)),
            mixInCandidates = listOf(MixCandidate(8.0, 0.9, "main_drop")),
            mixOutCandidates = listOf(MixCandidate(198.0, 0.8, "outro_start")),
        )

        BestMixFeatureStore(context).use { store -> store.put("track", expected) }
        val restored = BestMixFeatureStore(context).use { store -> store.load(listOf("track"))["track"] }

        assertEquals(expected.bpm, restored?.bpm)
        assertEquals(expected.key, restored?.key)
        assertEquals(expected.energyCurve, restored?.energyCurve)
        context.deleteDatabase(BestMixFeatureStore.DATABASE_NAME)
    }
}
