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

package dev.sfg.orchard.mobile.app

import android.util.Log
import dev.sfg.orchard.mobile.OrchardGraph
import dev.sfg.orchard.mobile.connect.ConnectWire
import dev.sfg.orchard.mobile.model.BestMixJob
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.PlaybackTarget
import dev.sfg.orchard.mobile.model.PlaybackTargetState
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.playback.LocalPlaybackController
import dev.sfg.orchard.mobile.playback.QueueEditor
import dev.sfg.orchard.mobile.playback.smart.BestMixSorter
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Starts playback of songs, collections, searches and Best Mix orders on the selected target. */
internal class QueueLauncher(
    private val graph: OrchardGraph,
    private val scope: CoroutineScope,
    private val local: LocalPlaybackController,
    private val playback: StateFlow<PlaybackSnapshot>,
    private val targets: StateFlow<PlaybackTargetState>,
    private val bestMix: BestMixPreparer,
    private val showWarning: (String) -> Unit,
    private val openDetail: (String) -> Unit,
) {
    private val bestMixJobState = MutableStateFlow<BestMixJob?>(null)
    val bestMixJob: StateFlow<BestMixJob?> = bestMixJobState

    // One run at a time; a second tap while one is in flight is ignored.
    private fun beginBestMix(key: String) = bestMixJobState.compareAndSet(null, BestMixJob(key, "Preparing Best Mix..."))

    private fun reportBestMix(status: String, onProgress: (String) -> Unit) {
        bestMixJobState.update { it?.copy(status = status) }
        onProgress(status)
    }

    fun play(track: Track, contextTitle: String = "") {
        Log.d(TAG, "play: track=${track.id} ('${track.title}'), contextTitle='$contextTitle'")
        playAll(listOf(track), contextTitle = contextTitle)
    }

    fun playAll(
        tracks: List<Track>,
        startIndex: Int = 0,
        contextTitle: String = "",
        shuffle: Boolean = false,
    ) {
        Log.d(TAG, "playAll: ${tracks.size} tracks, startIndex=$startIndex, contextTitle='$contextTitle'")
        if (tracks.isEmpty()) return
        val safeIndex = startIndex.coerceIn(tracks.indices)
        val source = contextTitle.takeIf { it.isMeaningfulPlaybackSource() }
            ?: tracks[safeIndex].album.takeIf { it.isMeaningfulPlaybackSource() }
            ?: "Your queue"

        scope.launch {
            val start = tracks[safeIndex]
            Log.d(TAG, "playAll: starting playback with track ${start.id} ('${start.title}')")
            val edited = QueueEditor.replaceAndPlay(tracks, safeIndex)
            val queue = edited.tracks

            when (targets.value.selected) {
                PlaybackTarget.LocalPhone -> {
                    local.replaceQueue(queue, edited.currentIndex, contextTitle = source)
                    // After the queue lands, never before: the service remembers the order it
                    // shuffles over, and enabling shuffle first would have it remember the queue
                    // this one is replacing, leaving nothing to restore when shuffle goes off.
                    if (shuffle) local.setShuffle(true)
                }
                is PlaybackTarget.Remote -> {
                    // The target owns the queue; it receives the whole list and plays from here.
                    val skip = if (edited.currentIndex < ConnectWire.QUEUE_LIMIT) 0 else edited.currentIndex
                    graph.connect.command(
                        "replace_queue",
                        JSONObject()
                            .put("tracks", ConnectWire.tracks(queue.drop(skip)))
                            .put("index", edited.currentIndex - skip)
                            .put("play", true)
                            .put("context_title", source),
                    )
                    if (shuffle) graph.connect.command("set_shuffle", JSONObject().put("enabled", true))
                }
            }
            graph.library.recordPlayed(start)
        }
    }

    /**
     * Plays whatever a spoken request resolves to. A blank query is Assistant asking for music
     * with no preference, which the user's own liked songs answer better than a search would.
     */
    fun playFromSearch(query: String) {
        Log.d(TAG, "playFromSearch: '$query'")
        scope.launch {
            val tracks = if (query.isBlank()) {
                graph.library.library.value.likedTracks.let(QueueEditor::shuffle)
            } else {
                runCatching { graph.catalog.search(query).tracks }.getOrDefault(emptyList())
            }
            if (tracks.isEmpty()) {
                showWarning(
                    if (query.isBlank()) "Nothing to play yet" else "Nothing found for \"$query\"",
                )
                return@launch
            }
            playAll(tracks, contextTitle = query)
        }
    }

    /**
     * Plays a collection shuffled. The queue goes in unshuffled and the service shuffles what
     * follows the randomly chosen opener, so the collection's own order is what shuffle is
     * remembered as being turned on over and switching it off restores the album or playlist.
     */
    fun shuffleAll(tracks: List<Track>, contextTitle: String = "") {
        val playable = tracks.distinctBy(Track::id).filter { it.id.isNotBlank() }
        if (playable.isEmpty()) return
        playAll(
            playable,
            startIndex = kotlin.random.Random.Default.nextInt(playable.size),
            contextTitle = contextTitle,
            shuffle = true,
        )
    }

    /**
     * Plays [tracks] as Best Mix, the way desktop does: the first song starts, and the next
     * [BestMixSorter.SNAPSHOT] are ordered to follow it.
     */
    fun playBestMix(
        tracks: List<Track>,
        title: String,
        onProgress: (String) -> Unit = {},
        onComplete: () -> Unit = {},
    ) = scope.launch {
        if (!beginBestMix(BestMixJob.collectionKey(title))) return@launch
        val playable = tracks.filter { it.id.isNotBlank() }.distinctBy(Track::id)
        val bestMixTitle = title.takeIf { it.isNotBlank() }?.let { "$it • Best Mix" } ?: "Best Mix"
        try {
            if (playable.isEmpty()) return@launch
            val first = playable.first()
            val rest = playable.drop(1)
            bestMix.prepare(listOf(first) + rest.take(BestMixSorter.SNAPSHOT), "Best Mix") { reportBestMix(it, onProgress) }
            reportBestMix("Sorting Best Mix...", onProgress)
            val sorted = withContext(Dispatchers.IO) { BestMixSorter.sort(graph.bestMixFeatures, rest, first) }
            if (sorted == null) showWarning("No songs could be analyzed for Best Mix.")
            playAll(listOf(first) + (sorted ?: rest), contextTitle = bestMixTitle)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            // Best Mix is an enhancement to playback, not a prerequisite.
            Log.w(TAG, "Best Mix preparation failed; playing the original order", error)
            if (playable.isNotEmpty()) {
                playAll(playable, contextTitle = bestMixTitle)
                showWarning("Best Mix could not finish analyzing every song. Playing the original order.")
            }
        } finally {
            bestMixJobState.value = null
            onComplete()
        }
    }

    /** Orders the upcoming songs to follow the current one, as desktop's queue Best Mix. */
    fun bestMixUpcoming(
        onProgress: (String) -> Unit = {},
        onComplete: () -> Unit = {},
    ) = scope.launch {
        if (!beginBestMix(BestMixJob.QUEUE_KEY)) return@launch
        try {
            val currentSnapshot = playback.value
            val upcoming = currentSnapshot.upcoming
            if (upcoming.size <= 1) return@launch
            val queueRequest = BestMixQueueRequest.capture(currentSnapshot)
            val currentTrack = currentSnapshot.currentTrack
            bestMix.prepare(listOfNotNull(currentTrack) + upcoming.take(BestMixSorter.SNAPSHOT),
                "Queue Best Mix") { reportBestMix(it, onProgress) }
            reportBestMix("Sorting queue...", onProgress)
            val sortedUpcoming = withContext(Dispatchers.IO) {
                BestMixSorter.sort(graph.bestMixFeatures, upcoming, currentTrack)
            } ?: run {
                showWarning("No songs could be analyzed for Best Mix.")
                return@launch
            }
            val reconciled = queueRequest.reconcile(playback.value, sortedUpcoming) ?: return@launch
            val baseTitle = currentSnapshot.contextTitle.ifBlank { currentTrack?.album.orEmpty() }
            val newTitle = when {
                baseTitle.isBlank() -> "Best Mix"
                baseTitle.endsWith("• Best Mix", ignoreCase = true) -> baseTitle
                else -> "$baseTitle • Best Mix"
            }
            local.replaceUpcoming(reconciled, contextTitle = newTitle)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            Log.w(TAG, "Could not prepare Best Mix for the upcoming queue", error)
            showWarning("Best Mix could not finish preparing the queue.")
        } finally {
            bestMixJobState.value = null
            onComplete()
        }
    }

    /**
     * Plays all tracks from a collection (playlist, album, artist, or mix) given its id.
     */
    fun playCollection(id: String, contextTitle: String = "", shuffle: Boolean = false) {
        Log.d(TAG, "playCollection: id='$id', contextTitle='$contextTitle', shuffle=$shuffle")
        scope.launch {
            val detail = runCatching { graph.catalog.browse(id) }.getOrNull()
            val tracks = detail?.tracks.orEmpty().filter { it.id.isNotBlank() }
            if (tracks.isNotEmpty()) {
                playAll(
                    tracks = tracks,
                    startIndex = if (shuffle) kotlin.random.Random.Default.nextInt(tracks.size) else 0,
                    contextTitle = contextTitle.ifBlank { detail?.title.orEmpty() },
                    shuffle = shuffle,
                )
            } else {
                showWarning("No playable tracks found")
            }
        }
    }

    fun playItem(item: CatalogItem, shuffle: Boolean = false) {
        when (item) {
            is CatalogItem.Song -> play(item.track, contextTitle = item.track.title)
            is CatalogItem.Collection -> playCollection(item.playlist.id, contextTitle = item.title, shuffle = shuffle)
            is CatalogItem.Record -> playCollection(item.album.id, contextTitle = item.title, shuffle = shuffle)
            is CatalogItem.Performer -> playCollection(item.artist.id, contextTitle = item.title, shuffle = shuffle)
            is CatalogItem.Category -> openDetail(item.stableId)
        }
    }

    private companion object {
        const val TAG = "QueueLauncher"
    }
}
