/* Copyright (C) 2026 SFG545. Licensed under AGPL-3.0-or-later. */
package dev.sfg.orchard.mobile.download

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheSpan
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(UnstableApi::class)
class AdjustableCacheEvictorTest {
    @Test
    fun `limit changes trim least recently used spans and leave newest spans`() {
        val evictor = AdjustableCacheEvictor()
        val removed = mutableListOf<String>()
        val cache = Proxy.newProxyInstance(Cache::class.java.classLoader, arrayOf(Cache::class.java)) { proxy, method, args ->
            when (method.name) {
                "removeSpan" -> {
                    val span = args!![0] as CacheSpan
                    removed += span.key
                    evictor.onSpanRemoved(proxy as Cache, span)
                    null
                }
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === args!![0]
                else -> null
            }
        } as Cache
        fun span(key: String, time: Long) = CacheSpan(key, 0, 10, time, File(key))
        val first = span("first", 1)
        val second = span("second", 2)
        val third = span("third", 3)
        listOf(first, second, third).forEach { evictor.onSpanAdded(cache, it) }

        evictor.setLimit(cache, 20)
        assertEquals(listOf("first"), removed)
        evictor.onSpanTouched(cache, second, span("second", 4))
        evictor.setLimit(cache, 10)
        assertEquals(listOf("first", "third"), removed)
        evictor.onStartFile(cache, "new", 0, 10)
        assertEquals(listOf("first", "third", "second"), removed)
    }
}
