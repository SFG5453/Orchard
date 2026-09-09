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
import android.content.ContextWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CacheManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private class TestContext(
        private val files: File,
        private val cache: File,
        private val externalCache: File? = null,
    ) : ContextWrapper(null) {
        override fun getFilesDir(): File = files
        override fun getCacheDir(): File = cache
        override fun getExternalCacheDir(): File? = externalCache
        override fun getApplicationContext(): Context = this
    }

    @Test
    fun `formatStorageSize handles zero and negative values`() {
        assertEquals("0 B", CacheManager.formatStorageSize(0L))
        assertEquals("0 B", CacheManager.formatStorageSize(-100L))
    }

    @Test
    fun `formatStorageSize formats raw bytes`() {
        assertEquals("1 B", CacheManager.formatStorageSize(1L))
        assertEquals("512 B", CacheManager.formatStorageSize(512L))
        assertEquals("1023 B", CacheManager.formatStorageSize(1023L))
    }

    @Test
    fun `formatStorageSize formats kilobytes`() {
        assertEquals("1.0 KB", CacheManager.formatStorageSize(1024L))
        assertEquals("50.0 KB", CacheManager.formatStorageSize(50 * 1024L))
        assertEquals("150 KB", CacheManager.formatStorageSize(150 * 1024L))
    }

    @Test
    fun `formatStorageSize formats megabytes and gigabytes`() {
        assertEquals("1.0 MB", CacheManager.formatStorageSize(1024L * 1024L))
        assertEquals("25.5 MB", CacheManager.formatStorageSize((25.5 * 1024 * 1024).toLong()))
        assertEquals("1.0 GB", CacheManager.formatStorageSize(1024L * 1024L * 1024L))
        assertEquals("2.5 GB", CacheManager.formatStorageSize((2.5 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun `calculateCacheSizeBytes calculates size across cache directories and stream cache`() {
        val root = tempFolder.newFolder("app_storage")
        val filesDir = File(root, "files").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }
        val externalCacheDir = File(root, "ext_cache").apply { mkdirs() }

        // Cache dir files
        File(cacheDir, "coil_image.tmp").writeBytes(ByteArray(1024))
        File(cacheDir, "nested").apply { mkdirs() }.resolve("temp.bin").writeBytes(ByteArray(2048))

        // External cache dir file
        File(externalCacheDir, "ext.tmp").writeBytes(ByteArray(512))

        // Stream cache in filesDir
        val streamDir = File(filesDir, CacheManager.STREAM_CACHE_DIR).apply { mkdirs() }
        File(streamDir, "track1.vbr").writeBytes(ByteArray(4096))

        // Downloads in filesDir (must NOT be counted)
        val downloadsDir = File(filesDir, "downloads").apply { mkdirs() }
        File(downloadsDir, "offline_song.mp3").writeBytes(ByteArray(16384))

        val context = TestContext(filesDir, cacheDir, externalCacheDir)
        val totalSize = CacheManager.calculateCacheSizeBytes(context)

        // 1024 + 2048 + 512 + 4096 = 7680 bytes
        assertEquals(7680L, totalSize)
    }

    @Test
    fun `clearAllCache removes cached files but preserves offline downloads and library files`() {
        val root = tempFolder.newFolder("app_storage_clear")
        val filesDir = File(root, "files").apply { mkdirs() }
        val cacheDir = File(root, "cache").apply { mkdirs() }

        // Cache files
        val cacheSubDir = File(cacheDir, "image_http_cache").apply { mkdirs() }
        val cachedImage = File(cacheSubDir, "artwork.jpg").apply { writeBytes(ByteArray(4096)) }

        // Stream cache
        val streamDir = File(filesDir, CacheManager.STREAM_CACHE_DIR).apply { mkdirs() }
        val cachedStream = File(streamDir, "span.exo").apply { writeBytes(ByteArray(8192)) }

        // Offline downloads and library data (MUST BE PRESERVED)
        val downloadsDir = File(filesDir, "downloads").apply { mkdirs() }
        val offlineSong = File(downloadsDir, "song.m4a").apply { writeBytes(ByteArray(32768)) }
        val libraryJson = File(filesDir, "library-cache.json").apply { writeText("{\"likes\": []}") }

        val context = TestContext(filesDir, cacheDir)
        val freedBytes = CacheManager.clearAllCache(context, graph = null)

        assertTrue(freedBytes >= 12288L)
        assertFalse(cachedImage.exists())
        assertFalse(cachedStream.exists())

        // Ensure downloads and library are untouched
        assertTrue(offlineSong.exists())
        assertEquals(32768L, offlineSong.length())
        assertTrue(libraryJson.exists())
        assertEquals("{\"likes\": []}", libraryJson.readText())

        // Cache size after should be 0
        assertEquals(0L, CacheManager.calculateCacheSizeBytes(context))
    }
}
