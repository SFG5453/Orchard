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

package dev.sfg.orchard.mobile.settings

import android.content.Context
import coil3.SingletonImageLoader
import dev.sfg.orchard.mobile.OrchardGraph
import java.io.File
import java.util.ArrayDeque
import java.util.Locale

object CacheManager {
    const val STREAM_CACHE_DIR = "stream-cache"

    /**
     * Calculates total bytes used by temporary caches:
     * - [Context.getCacheDir] (HTTP cache, Coil disk cache, transitions, etc.)
     * - [Context.getExternalCacheDir] (if present)
     * - `filesDir/stream-cache` (ExoPlayer stream cache spans)
     *
     * Offline downloads and library data are excluded.
     */
    fun calculateCacheSizeBytes(context: Context): Long {
        var size = 0L
        size += directorySize(context.cacheDir)
        context.externalCacheDir?.let { size += directorySize(it) }
        val streamCacheDir = File(context.filesDir, STREAM_CACHE_DIR)
        if (streamCacheDir.exists()) {
            size += directorySize(streamCacheDir)
        }
        return size
    }

    /**
     * Clears all temporary caches and returns the total bytes freed:
     * 1. Coil image memory and disk cache
     * 2. Stream cache (via [OrchardGraph.onClearStreamCache] if playback service is active,
     *    or by clearing the files in `filesDir/stream-cache` directly)
     * 3. Files inside [Context.getCacheDir] and [Context.getExternalCacheDir]
     *
     * Preserves:
     * - Offline downloads in `filesDir/downloads` or `orchard-downloads`
     * - Library cache in `filesDir/library-cache.json`
     * - Credentials and settings
     */
    fun clearAllCache(context: Context, graph: OrchardGraph? = null): Long {
        val sizeBefore = calculateCacheSizeBytes(context)

        // 1. Coil in-memory and disk cache
        runCatching {
            val imageLoader = SingletonImageLoader.get(context)
            imageLoader.memoryCache?.clear()
            imageLoader.diskCache?.clear()
        }

        // 2. Stream cache
        if (graph?.onClearStreamCache != null) {
            runCatching { graph.onClearStreamCache?.invoke() }
        } else {
            val streamCacheDir = File(context.filesDir, STREAM_CACHE_DIR)
            if (streamCacheDir.exists()) {
                deleteDirectoryContents(streamCacheDir)
            }
        }

        // 3. Application cache directories
        context.cacheDir?.let { deleteDirectoryContents(it) }
        context.externalCacheDir?.let { deleteDirectoryContents(it) }

        val sizeAfter = calculateCacheSizeBytes(context)
        return (sizeBefore - sizeAfter).coerceAtLeast(0L)
    }

    /**
     * Formats bytes into a human-readable storage string.
     */
    fun formatStorageSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        if (bytes < 1024L) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024.0) {
            return if (kb < 100.0) String.format(Locale.US, "%.1f KB", kb)
            else String.format(Locale.US, "%.0f KB", kb)
        }
        val mb = kb / 1024.0
        return if (mb >= 1000.0) {
            String.format(Locale.US, "%.1f GB", mb / 1024.0)
        } else {
            String.format(Locale.US, "%.1f MB", mb)
        }
    }

    private fun directorySize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.add(dir)
        while (stack.isNotEmpty()) {
            val current = stack.removeFirst()
            val children = current.listFiles() ?: continue
            for (child in children) {
                if (child.isDirectory) {
                    stack.add(child)
                } else {
                    total += child.length()
                }
            }
        }
        return total
    }

    private fun deleteDirectoryContents(dir: File) {
        dir.listFiles()?.forEach { file ->
            runCatching { file.deleteRecursively() }
        }
    }
}
