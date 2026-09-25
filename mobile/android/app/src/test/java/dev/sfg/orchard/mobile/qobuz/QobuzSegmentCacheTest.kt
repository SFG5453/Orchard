package dev.sfg.orchard.mobile.qobuz

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class QobuzSegmentCacheTest {
    @Test
    fun `budget is shared across sessions and eviction respects recent access`() {
        val cache = QobuzSegmentCache(8)
        val a = cache.getOrLoad("a", 1) { ByteArray(4) }
        cache.getOrLoad("b", 1) { ByteArray(4) }
        assertSame(a, cache.getOrLoad("a", 1) { error("cache miss") })
        cache.getOrLoad("c", 1) { ByteArray(4) }
        assertSame(a, cache.getOrLoad("a", 1) { error("cache miss") })
        var reloads = 0
        cache.getOrLoad("b", 1) { reloads++; ByteArray(4) }
        assertEquals(1, reloads)
    }

    @Test
    fun `oversized segments are served without being retained`() {
        val cache = QobuzSegmentCache(4)
        var loads = 0
        repeat(2) { cache.getOrLoad("a", 1) { loads++; ByteArray(5) } }
        assertEquals(2, loads)
    }

    @Test
    fun `failed downloads can be retried`() {
        val cache = QobuzSegmentCache(4)
        assertThrows(IOException::class.java) {
            cache.getOrLoad("a", 1) { throw IOException("disconnected") }
        }
        assertEquals(4, cache.getOrLoad("a", 1) { ByteArray(4) }.size)
    }

    @Test
    fun `parallel range requests download a segment only once`() {
        val cache = QobuzSegmentCache(4)
        val workers = Executors.newFixedThreadPool(4)
        val ready = CountDownLatch(4)
        val start = CountDownLatch(1)
        val loads = AtomicInteger()
        try {
            val results = (1..4).map {
                workers.submit<ByteArray> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    cache.getOrLoad("a", 1) { loads.incrementAndGet(); ByteArray(4) }
                }
            }
            check(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val first = results.first().get(5, TimeUnit.SECONDS)
            results.forEach { assertSame(first, it.get(5, TimeUnit.SECONDS)) }
            assertEquals(1, loads.get())
        } finally {
            workers.shutdownNow()
        }
    }
}
