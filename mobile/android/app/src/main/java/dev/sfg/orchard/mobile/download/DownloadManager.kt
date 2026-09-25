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
import dev.sfg.orchard.mobile.artwork.TrackArtwork
import dev.sfg.orchard.mobile.auth.YouTubeSessionProvider
import dev.sfg.orchard.mobile.model.AudioQuality
import dev.sfg.orchard.mobile.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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

internal fun DownloadItem.completedFileOrNull(): File? {
    if (status != DownloadStatus.COMPLETED || filePath.isBlank()) return null
    return File(filePath).takeIf { it.exists() && it.length() > 0L }
}

/**
 * Application-scoped download queue controller supporting concurrent background downloads,
 * cancellation, deletion, and reactive state flows.
 */
class DownloadManager(
    context: Context,
    private val http: OkHttpClient,
    sessionProvider: YouTubeSessionProvider? = null,
    private val scope: CoroutineScope,
    /**
     * Supplied lazily so constructing the graph does not build a WebView. Downloads share the
     * minter with playback; attesting twice would cost twice and prove the same thing.
     */
    poTokenMinter: () -> dev.sfg.orchard.mobile.playback.YouTubePoTokenMinter? = { null },
    /**
     * Also lazy, and for the same reason. The attesting client returns ciphered formats, so a
     * downloader without a solver would resolve through it and then be unable to read the URL.
     */
    challengeSolver: () -> dev.sfg.orchard.mobile.playback.YouTubeChallengeSolver? = { null },
    private val artworkResolver: suspend (Track) -> TrackArtwork? = { null },
    private val downloadAnimatedArtworkProvider: () -> Boolean = { false },
    qualityProvider: () -> AudioQuality = { AudioQuality.HIGH },
) {
    private val context = context.applicationContext
    val store: DownloadStore = DownloadStore(context)
    private val downloader: TrackDownloader =
        TrackDownloader(http, sessionProvider, store, poTokenMinter, challengeSolver, qualityProvider)

    private val mutableDownloads = MutableStateFlow<Map<String, DownloadItem>>(emptyMap())
    val downloads: StateFlow<Map<String, DownloadItem>> = mutableDownloads.asStateFlow()

    private val mutableDownloadedIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadedTrackIds: StateFlow<Set<String>> = mutableDownloadedIds.asStateFlow()

    private val mutableDownloadingIds = MutableStateFlow<Set<String>>(emptySet())
    val downloadingTrackIds: StateFlow<Set<String>> = mutableDownloadingIds.asStateFlow()

    private val mutableTotalBytesUsed = MutableStateFlow(store.totalBytesUsed())
    val totalBytesUsedFlow: StateFlow<Long> = mutableTotalBytesUsed.asStateFlow()

    private val downloadQueue = Channel<DownloadItem>(Channel.UNLIMITED)
    private val activeJobs = ConcurrentHashMap<String, Job>()
    private val stateLock = Any()

    init {
        // Load initial state from disk
        val initial = store.loadAll()
        mutableDownloads.value = initial
        updateDerivedStates(initial)
        scope.launch(Dispatchers.IO) { refreshTotalBytesUsed() }

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
        if (current?.status == DownloadStatus.COMPLETED && current.completedFileOrNull() != null) {
            Log.d(TAG, "Track ${track.id} already downloaded or queued")
            return
        }
        if (current?.isDownloading == true) {
            Log.d(TAG, "Track ${track.id} already downloaded or queued")
            return
        }

        // Android or the user may remove an external-files entry while the process is alive.
        // A COMPLETED row is only a download while its non-empty file still exists; otherwise a
        // Best Mix request sees the missing file, asks us to fetch it, and this method used to
        // reject that fetch as "already downloaded". Besides making the sort wait on a download
        // that never starts, that disagreement produced the apparently random one-song download
        // stage reported from collection Best Mix.
        if (current?.status == DownloadStatus.COMPLETED) {
            synchronized(stateLock) {
                val updated = mutableDownloads.value.toMutableMap().apply { remove(track.id) }
                mutableDownloads.value = updated
                updateDerivedStates(updated)
            }
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
        val removed = mutableDownloads.value[videoId]
        store.remove(videoId)
        val updated = mutableDownloads.value.toMutableMap()
        updated.remove(videoId)
        mutableDownloads.value = updated
        updateDerivedStates(updated)
        removed?.let { releaseUnusedAnimatedArtwork(it, updated.values) }
        refreshTotalBytesUsed()
    }

    /** Remove a collection of downloaded tracks (e.g. removing an album or playlist). */
    fun removeDownloads(videoIds: List<String>) {
        val updated = mutableDownloads.value.toMutableMap()
        val removed = mutableListOf<DownloadItem>()
        for (videoId in videoIds) {
            val job = activeJobs.remove(videoId)
            job?.cancel()
            store.remove(videoId)
            updated.remove(videoId)?.let(removed::add)
        }
        mutableDownloads.value = updated
        updateDerivedStates(updated)
        removed.forEach { releaseUnusedAnimatedArtwork(it, updated.values) }
        refreshTotalBytesUsed()
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
    fun totalBytesUsed(): Long = store.totalBytesUsed() + AnimatedArtworkCache.bytesUsed(context)

    /** Returns the local file for a downloaded track, or null if not downloaded. */
    fun getDownloadedFile(videoId: String): File? {
        val item = mutableDownloads.value[videoId] ?: return null
        return item.completedFileOrNull()
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

        if (result.status == DownloadStatus.COMPLETED && downloadAnimatedArtworkProvider()) {
            result = downloadAnimatedArtwork(result)
        }

        updateItemState(result)
        if (result.status == DownloadStatus.COMPLETED) {
            store.save(result)
            refreshTotalBytesUsed()
        }
    }

    /** Motion-cover failures never turn a successfully downloaded song into a failed download. */
    private suspend fun downloadAnimatedArtwork(item: DownloadItem): DownloadItem {
        val original = item.track
        val resolved = if (
            original.animatedArtworkUrl.isNotBlank() ||
            original.animatedArtworkVerticalUrl.isNotBlank()
        ) {
            TrackArtwork(
                trackId = original.id,
                videoUrl = original.animatedArtworkUrl,
                videoUrlVertical = original.animatedArtworkVerticalUrl,
            )
        } else {
            try {
                artworkResolver(original)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Could not resolve animated artwork for ${original.id}", error)
                null
            }
        } ?: return item

        suspend fun cache(source: String): CachedAnimatedArtwork? {
            if (source.isBlank()) return null
            return try {
                AnimatedArtworkCache.download(context, http, source)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Log.w(TAG, "Could not download animated artwork for ${original.id}", error)
                null
            }
        }

        val wide = cache(resolved.videoUrl)
        val vertical = when {
            resolved.videoUrlVertical.isBlank() -> null
            resolved.videoUrlVertical == resolved.videoUrl -> wide
            else -> cache(resolved.videoUrlVertical)
        }
        if (wide == null && vertical == null) return item

        return item.copy(
            track = original.copy(
                animatedArtworkUrl = wide?.playbackUrl ?: original.animatedArtworkUrl,
                animatedArtworkVerticalUrl = vertical?.playbackUrl ?: original.animatedArtworkVerticalUrl,
            ),
            cachedAnimatedArtworkUrl = wide?.playbackUrl.orEmpty(),
            cachedAnimatedArtworkVerticalUrl = vertical?.playbackUrl.orEmpty(),
            animatedArtworkBytesDownloaded = listOfNotNull(wide, vertical)
                .distinctBy(CachedAnimatedArtwork::playbackUrl)
                .sumOf(CachedAnimatedArtwork::bytes),
        )
    }

    private fun releaseUnusedAnimatedArtwork(
        removed: DownloadItem,
        remaining: Collection<DownloadItem>,
    ) {
        val retainedUrls = remaining.flatMapTo(mutableSetOf()) {
            listOf(it.cachedAnimatedArtworkUrl, it.cachedAnimatedArtworkVerticalUrl)
        }
        val releasedUrls = listOf(
            removed.cachedAnimatedArtworkUrl,
            removed.cachedAnimatedArtworkVerticalUrl,
        ).filter(String::isNotBlank).distinct().filterNot(retainedUrls::contains)
        if (releasedUrls.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            releasedUrls.forEach { url ->
                runCatching { AnimatedArtworkCache.remove(context, http, url) }
                    .onFailure { Log.w(TAG, "Could not remove cached animated artwork", it) }
            }
            refreshTotalBytesUsed()
        }
    }

    private fun refreshTotalBytesUsed() {
        mutableTotalBytesUsed.value = totalBytesUsed()
    }

    private fun updateItemState(item: DownloadItem) {
        synchronized(stateLock) {
            val map = mutableDownloads.value.toMutableMap()
            map[item.track.id] = item
            mutableDownloads.value = map
            updateDerivedStates(map)
        }
    }

    private fun updateDerivedStates(map: Map<String, DownloadItem>) {
        mutableDownloadedIds.value = map.values
            .filter { it.completedFileOrNull() != null }
            .map { it.track.id }
            .toSet()
        mutableDownloadingIds.value = map.values
            .filter { it.isDownloading }
            .map { it.track.id }
            .toSet()
    }

    companion object {
        private const val TAG = "DownloadManager"
        // NewPipe-resolved tracks may use four bounded range requests each. Three tracks keeps enough
        // parallelism to beat per-connection throttling without creating a request storm.
        private const val MAX_CONCURRENT_DOWNLOADS = 3
        private const val MAX_DOWNLOAD_ATTEMPTS = 6
        private const val RETRY_BASE_DELAY_MS = 750L
    }
}
