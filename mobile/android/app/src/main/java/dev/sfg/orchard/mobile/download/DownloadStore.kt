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

package dev.sfg.orchard.mobile.download

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * On-disk index and file store for offline downloaded tracks.
 */
class DownloadStore(
    context: Context? = null,
    baseDir: File? = null,
) {
    val downloadDir: File = baseDir ?: File(
        context?.getExternalFilesDir(null) ?: context?.filesDir ?: File(System.getProperty("java.io.tmpdir"), "orchard-downloads"),
        DOWNLOADS_FOLDER,
    ).apply { if (!exists()) mkdirs() }

    private val indexFile: File = File(downloadDir, INDEX_FILE_NAME)
    private val lock = Any()

    /** Returns all persisted completed/failed download entries. */
    fun loadAll(): Map<String, DownloadItem> = synchronized(lock) {
        if (!indexFile.exists()) return emptyMap()
        return runCatching {
            val text = indexFile.readText()
            val array = JSONArray(text)
            val result = mutableMapOf<String, DownloadItem>()
            for (i in 0 until array.length()) {
                val json = array.optJSONObject(i) ?: continue
                val item = DownloadItem.fromJson(json)
                if (item.track.id.isNotBlank()) {
                    // Check if file still exists on disk for completed downloads
                    if (item.status == DownloadStatus.COMPLETED && item.filePath.isNotBlank()) {
                        val file = File(item.filePath)
                        if (file.exists() && file.length() > 0) {
                            result[item.track.id] = item
                        }
                    } else if (item.status == DownloadStatus.FAILED) {
                        result[item.track.id] = item
                    }
                }
            }
            result
        }.onFailure {
            Log.w(TAG, "Failed to load downloads index", it)
        }.getOrDefault(emptyMap())
    }

    /** Save or update a download record. */
    fun save(item: DownloadItem) = synchronized(lock) {
        val current = loadAll().toMutableMap()
        current[item.track.id] = item
        writeIndex(current.values.toList())
    }

    /** Save multiple download records. */
    fun saveAll(items: List<DownloadItem>) = synchronized(lock) {
        val current = loadAll().toMutableMap()
        for (item in items) {
            current[item.track.id] = item
        }
        writeIndex(current.values.toList())
    }

    /** Remove a download entry and delete its file from disk. */
    fun remove(videoId: String): Boolean = synchronized(lock) {
        val current = loadAll().toMutableMap()
        val removed = current.remove(videoId)
        if (removed != null) {
            writeIndex(current.values.toList())
            if (removed.filePath.isNotBlank()) {
                val file = File(removed.filePath)
                if (file.exists()) {
                    file.delete()
                }
            }
            return true
        }
        return false
    }

    /** Gets target file path for a track. */
    fun getTargetFile(videoId: String, extension: String): File {
        val safeName = videoId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(downloadDir, "$safeName.$extension")
    }

    /** Gets temp download file path for a track. */
    fun getTempFile(videoId: String): File {
        val safeName = videoId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(downloadDir, "$safeName.tmp")
    }

    /** Calculate total bytes used by downloaded tracks. */
    fun totalBytesUsed(): Long = synchronized(lock) {
        loadAll().values
            .filter { it.status == DownloadStatus.COMPLETED }
            .sumOf { it.bytesDownloaded.coerceAtLeast(0L) }
    }

    private fun writeIndex(items: List<DownloadItem>) {
        runCatching {
            val array = JSONArray()
            for (item in items) {
                array.put(item.toJson())
            }
            val tempIndex = File(downloadDir, "$INDEX_FILE_NAME.tmp")
            tempIndex.writeText(array.toString(2))
            tempIndex.renameTo(indexFile)
        }.onFailure {
            Log.e(TAG, "Failed to write downloads index", it)
        }
    }

    companion object {
        private const val TAG = "DownloadStore"
        private const val DOWNLOADS_FOLDER = "offline_downloads"
        private const val INDEX_FILE_NAME = "downloads.json"
    }
}
