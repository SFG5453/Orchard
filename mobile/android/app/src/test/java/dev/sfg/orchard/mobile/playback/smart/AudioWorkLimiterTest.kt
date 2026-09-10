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

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioWorkLimiterTest {
    @Test fun analysisAndRenderCannotHoldPcmConcurrently() {
        val pool = Executors.newFixedThreadPool(4)
        val start = CountDownLatch(1)
        val active = AtomicInteger()
        val peak = AtomicInteger()
        try {
            val jobs = (0 until 20).map {
                pool.submit {
                    start.await()
                    AudioWorkLimiter.run {
                        val count = active.incrementAndGet()
                        peak.updateAndGet { previous -> maxOf(previous, count) }
                        Thread.yield()
                        active.decrementAndGet()
                    }
                }
            }
            start.countDown()
            jobs.forEach { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, peak.get())
            assertEquals(0, active.get())
        } finally { pool.shutdownNow() }
    }

    @Test fun failedJobReleasesMemoryPermit() {
        try { AudioWorkLimiter.run { error("failed decode") } } catch (_: IllegalStateException) {}
        val pool = Executors.newSingleThreadExecutor()
        try {
            assertTrue(pool.submit<Boolean> { AudioWorkLimiter.run { true } }.get(5, TimeUnit.SECONDS))
        } finally { pool.shutdownNow() }
    }
}
