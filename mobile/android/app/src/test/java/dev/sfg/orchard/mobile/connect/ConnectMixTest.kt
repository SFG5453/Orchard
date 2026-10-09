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

package dev.sfg.orchard.mobile.connect

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ConnectMixTest {
    // 0.5 s at 44.1 kHz, as the desktop sends it for a phone whose song decodes at that rate.
    private val plan = JSONObject()
        .put("outgoingStart", 171.25).put("incomingCue", 3.0).put("incomingResume", 3.5)
        .put("duration", 0.5).put("strategy", "bass swap").put("targetBpm", 124.0)
        .put("rate", 44_100).put("incomingRate", 48_000)

    private fun header(codec: String, rate: Int = 44_100, timestamp: Double = 171.25) = JSONObject()
        .put("kind", "audio").put("codec", codec).put("sample_rate", rate).put("channels", 2).put("timestamp", timestamp)

    private fun s16(samples: ShortArray): ByteArray =
        ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { b -> samples.forEach { b.putShort(it) } }.array()

    @Test
    fun pcm16RenderBecomesAReadyMix() {
        val samples = ShortArray(44_100) { (it % 200 - 100).toShort() }
        val ready = ConnectMix.ready(plan, header("pcm_s16"), s16(samples))
        assertNotNull(ready)
        assertArrayEquals(samples, ready!!.pcm)
        assertEquals(171.25, ready.outgoingStart, 0.0)
        assertEquals(44_100, ready.rate)
        assertEquals(48_000, ready.incomingRate)
        assertEquals(0.5, ready.duration, 1e-9)
    }

    @Test
    fun floatRenderClampsLikeTheLocalMixer() {
        val floats = floatArrayOf(1.5f, -2f, 0.5f, 0f)
        val bytes = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { b -> floats.forEach { b.putFloat(it) } }.array()
        val short = JSONObject(plan.toString()).put("duration", 2.0 / 44_100)
        val ready = ConnectMix.ready(short, header("pcm_f32"), bytes)!!
        assertArrayEquals(shortArrayOf(32767, -32767, 16383, 0), ready.pcm)
    }

    @Test
    fun mismatchedRendersAreRejected() {
        val pcm = s16(ShortArray(44_100))
        // Rendered for another player rate, stamped at another media time, or cut short.
        assertNull(ConnectMix.ready(plan, header("pcm_s16", rate = 48_000), pcm))
        assertNull(ConnectMix.ready(plan, header("pcm_s16", timestamp = 170.0), pcm))
        assertNull(ConnectMix.ready(plan, header("pcm_s16"), pcm.copyOf(pcm.size / 2)))
        assertNull(ConnectMix.ready(plan, header("opus"), pcm))
    }
}
