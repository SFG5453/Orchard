/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard, licensed under the GNU Affero General Public License v3 or later.
 */
package dev.sfg.orchard.mobile.download

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import java.util.TreeSet

/** LRU eviction with a limit that can change while the cache is open. */
@OptIn(UnstableApi::class)
internal class AdjustableCacheEvictor : CacheEvictor {
    private val spans = TreeSet<CacheSpan> { a, b ->
        val time = a.lastTouchTimestamp.compareTo(b.lastTouchTimestamp)
        if (time != 0) time else a.compareTo(b)
    }
    // Wait for persisted settings before removing anything from an existing cache.
    private var maxBytes = Long.MAX_VALUE
    private var currentBytes = 0L

    fun setLimit(cache: Cache, bytes: Long) = synchronized(cache) {
        maxBytes = bytes.coerceAtLeast(0L)
        evict(cache, 0)
    }

    override fun requiresCacheSpanTouches() = true
    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        evict(cache, if (length == C.LENGTH_UNSET.toLong()) 0 else length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        if (spans.add(span)) currentBytes += span.length
        evict(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        if (spans.remove(span)) currentBytes -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    private fun evict(cache: Cache, incoming: Long) {
        val allowed = (maxBytes - incoming.coerceAtMost(maxBytes)).coerceAtLeast(0)
        while (currentBytes > allowed && spans.isNotEmpty()) {
            cache.removeSpan(spans.first())
        }
    }
}
