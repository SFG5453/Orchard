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
import dev.sfg.orchard.mobile.auth.YouTubeSessionProvider
import dev.sfg.orchard.mobile.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Application-scoped download queue controller supporting concurrent background downloads,
 * cancellation, deletion, and reactive state flows.
 */
class DownloadManager(
    context: Context,
    http: OkHttpClient,
    sessionProvider: YouTubeSessionProvider? = null,
    private val scope: CoroutineScope,
) {
    val store: DownloadStore = DownloadStore(context)
    private val downloader: TrackDownloader = TrackDownloader(http, sessionProvider, store)

    private val mutableDownloads = MutableStateFlow<Map<String, DownloadItem>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadItem>> = mutableDownloads.asStateFlow()

    private val mutableDownloadedIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadedTrackIds: StateFlow<Set<String>> = mutableDownloadedIds.asStateFlow()

    private val mutableDownloadingIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadingTrackIds: StateFlow<Set<String>> = mutableDownloadingIds.asStateFlow()

    private val downloadQueue = Channel<DownloadItem>(Channel.UNLIMITED)
    private val activeJobs = ConcurrentHashMap<String, Job>()

    init {
        // Load initial state from disk
        val initial = store.loadAll()
        mutableDownloads.value = initial
        updateDerivedStates(initial)

        // Start worker coroutines for queued downloads
        repeat(MAX_CONCURRENT_DOWNLOADS) {
            scope.launch {
                for (item in downloadQueue) {
                    coroutineScope {
                        val videoId = item.track.id
                        val job = launch { processDownload(item) }
                        activeJobs[videoId] = job
                        try {
                            job.join()
                        } finally {
                            activeJobs.remove(videoId, job)
                        }
                    }
                }
            }
        }
    }

    /** Enqueue a track for downloading. */
    fun downloadTrack(track: Track) {
        if (track.id.isBlank()) return
        val current = mutableDownloads.value[track.id]
        if (current?.status == DownloadStatus.COMPLETED || current?.isDownloading == true) {
            Log.d(TAG, "Track ${track.id} already downloaded or queued")
            return
        }

        val item = DownloadItem(
            track = track,
            status = DownloadStatus.QUEUED,
            progress = 0f,
        )
        updateItemState(item)
        downloadQueue.trySend(item)
    }

    /** Enqueue a list of tracks for downloading (e.g. playlist or album). */
    fun downloadTracks(tracks: List<Track>) {
        for (track in tracks) {
            downloadTrack(track)
        }
    }

    /** Cancel an active or queued download. */
    fun cancelDownload(videoId: String) {
        val job = activeJobs.remove(videoId)
        job?.cancel()
        val item = mutableDownloads.value[videoId]
        if (item != null && item.status != DownloadStatus.COMPLETED) {
            store.remove(videoId)
            val updated = mutableDownloads.value.toMutableMap()
            updated.remove(videoId)
            mutableDownloads.value = updated
            updateDerivedStates(updated)
        }
    }

    /** Remove a downloaded track and delete its file from disk. */
    fun removeDownload(videoId: String) {
        cancelDownload(videoId)
        store.remove(videoId)
        val updated = mutableDownloads.value.toMutableMap()
        updated.remove(videoId)
        mutableDownloads.value = updated
        updateDerivedStates(updated)
    }

    /** Remove a collection of downloaded tracks (e.g. removing an album or playlist). */
    fun removeDownloads(videoIds: List<String>) {
        val updated = mutableDownloads.value.toMutableMap()
        for (videoId in videoIds) {
            val job = activeJobs.remove(videoId)
            job?.cancel()
            store.remove(videoId)
            updated.remove(videoId)
        }
        mutableDownloads.value = updated
        updateDerivedStates(updated)
    }

    /** Check if a track is downloaded and verified on disk. */
    fun isDownloaded(videoId: String): Boolean = downloadedTrackIds.value.contains(videoId)

    /** Check if all tracks in a list are downloaded. */
    fun areTracksDownloaded(tracks: List<Track>): Boolean {
        if (tracks.isEmpty()) return false
        val downloaded = downloadedTrackIds.value
        return tracks.all { downloaded.contains(it.id) }
    }

    /** Total storage bytes consumed by completed downloads. */
    fun totalBytesUsed(): Long = store.totalBytesUsed()

    /** Returns the local file for a downloaded track, or null if not downloaded. */
    fun getDownloadedFile(videoId: String): File? {
        val item = mutableDownloads.value[videoId] ?: return null
        if (item.status != DownloadStatus.COMPLETED || item.filePath.isBlank()) return null
        val file = File(item.filePath)
        return if (file.exists() && file.length() > 0) file else null
    }

    private suspend fun processDownload(queuedItem: DownloadItem) {
        val videoId = queuedItem.track.id
        // Check if cancelled before running
        val current = mutableDownloads.value[videoId]
        if (current == null || current.status == DownloadStatus.COMPLETED) return

        val downloadingItem = queuedItem.copy(status = DownloadStatus.DOWNLOADING)
        updateItemState(downloadingItem)

        var result = downloadingItem
        for (attempt in 0 until MAX_DOWNLOAD_ATTEMPTS) {
            result = downloader.download(result.copy(status = DownloadStatus.DOWNLOADING)) { bytesDownloaded, totalBytes, progress ->
                val progressItem = downloadingItem.copy(
                    bytesDownloaded = bytesDownloaded,
                    totalBytes = totalBytes,
                    progress = progress,
                )
                updateItemState(progressItem)
            }
            if (result.status == DownloadStatus.COMPLETED) break
            if (attempt < MAX_DOWNLOAD_ATTEMPTS - 1) {
                Log.w(TAG, "Retrying download $videoId after attempt ${attempt + 1}: ${result.errorMessage}")
                delay(RETRY_BASE_DELAY_MS * (attempt + 1))
            }
        }

        updateItemState(result)
        if (result.status == DownloadStatus.COMPLETED) {
            store.save(result)
        }
    }

    private fun updateItemState(item: DownloadItem) {
        val map = mutableDownloads.value.toMutableMap()
        map[item.track.id] = item
        mutableDownloads.value = map
        updateDerivedStates(map)
    }

    private fun updateDerivedStates(map: Map<String, DownloadItem>) {
        mutableDownloadedIds.value = map.values
            .filter { it.status == DownloadStatus.COMPLETED && it.filePath.isNotBlank() }
            .map { it.track.id }
            .toSet()
        mutableDownloadingIds.value = map.values
            .filter { it.isDownloading }
            .map { it.track.id }
            .toSet()
    }

    companion object {
        private const val TAG = "DownloadManager"
        private const val MAX_CONCURRENT_DOWNLOADS = 2
        private const val MAX_DOWNLOAD_ATTEMPTS = 3
        private const val RETRY_BASE_DELAY_MS = 750L
    }
}
