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

package dev.sfg.orchard.mobile.playback

import android.content.Context
import android.media.MediaDataSource
import android.os.Handler
import android.os.Process
import android.util.Log
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.playback.smart.AdaptiveMix
import dev.sfg.orchard.mobile.playback.smart.PreparedMix
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Whether this queue is an album genuinely being played through in order; the planner then
 * keeps it gapless. Best Mix and shuffle are mixes, never playthroughs, as on desktop.
 */
@UnstableApi
internal fun isAlbumPlaythrough(player: ExoPlayer, currentTrack: Track?): Boolean {
    if (player.shuffleModeEnabled) return false
    if (player.nextMediaItemIndex != player.currentMediaItemIndex + 1) return false
    val album = currentTrack?.album?.takeIf { it.isNotBlank() } ?: return false
    val context = player.playlistMetadata.title?.toString().orEmpty()
    if (context.endsWith("• Best Mix", ignoreCase = true) || context.equals("Best Mix", ignoreCase = true)) return false
    return context.equals(album, ignoreCase = true)
}

/** Adaptive mix done by a Connect mix host (spec: Mix Host). Blocks on the mixer's worker thread. */
internal fun interface RemoteMix {
    /** Ready or NoMix from the host; null when it is gone or broke, and this phone mixes for itself. */
    fun prepare(request: JSONObject, outgoing: () -> MediaDataSource?, incoming: () -> MediaDataSource?): PreparedMix?
}

/**
 * Prepares one pair at a time off the main thread, like desktop's `AdaptiveMixController`
 * feeding its worker process. Results are keyed by pair so a queue edit never plays a stale mix.
 *
 * The ONNX sessions stay loaded between mixes; building them is a 30 MB allocation burst plus
 * a full graph optimization.
 */
class AdaptiveMixer internal constructor(
    private val context: Context,
    private val handler: Handler,
    /** The desktop mixing for this phone while one is connected; asked per pair. */
    private val remote: () -> RemoteMix? = { null },
) {
    // QuickJS plans on this thread and allows itself 2 MiB of stack; ART gives threads 1 MiB.
    // One pair at a time: a DJ with two hands, not four.
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(null, {
            // Below the UI and playback threads. Nice 10+ moves a thread into the background
            // cpuset (little cores only) on most kernels, so stop one short of it.
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND + Process.THREAD_PRIORITY_MORE_FAVORABLE)
            task.run()
        }, "orchard-adaptive-mix", 8L shl 20).apply { isDaemon = true }
    }
    private var key: String? = null
    private var preparing = false
    private var result: PreparedMix? = null

    // Worker thread only. ORT spawns its pool here, so the pool inherits the priority above.
    private var models = 0L

    private val idleRelease = Runnable { onWorker(::freeModels) }

    internal fun resultFor(pair: String): PreparedMix? = result.takeIf { key == pair }

    internal fun isPreparing(pair: String): Boolean = preparing && key == pair

    internal fun prepare(
        pair: String,
        outgoing: () -> MediaDataSource?,
        incoming: () -> MediaDataSource?,
        request: JSONObject,
    ) {
        if (preparing || worker.isShutdown) return
        key = pair
        result = null
        preparing = true
        handler.removeCallbacks(idleRelease)
        worker.execute {
            val prepared = try {
                remote()?.prepare(request, outgoing, incoming)
                    ?: AdaptiveMix.prepare(::loadedModels, outgoing, incoming, request)
            } catch (error: Throwable) {
                // Native analysis must never take playback down with it.
                Log.w(TAG, "Adaptive mix preparation crashed", error)
                PreparedMix.Failed(error.message ?: "Adaptive mix preparation failed")
            }
            when (prepared) {
                is PreparedMix.NoMix -> Log.i(TAG, "AdaptiveMix: no mix | ${prepared.reason}")
                is PreparedMix.Failed -> Log.i(TAG, "AdaptiveMix: plan failed | ${prepared.reason}")
                is PreparedMix.Ready -> Unit
            }
            handler.post {
                preparing = false
                if (key == pair) result = prepared
                handler.postDelayed(idleRelease, MODEL_IDLE_MS)
            }
        }
    }

    private fun loadedModels(): Long? {
        if (models == 0L) models = AdaptiveMix.loadModels(context) ?: 0L
        return models.takeIf { it != 0L }
    }

    private fun freeModels() {
        if (models == 0L) return
        AdaptiveMix.freeModels(models)
        models = 0L
        Log.d(TAG, "Models released")
    }

    /** Drops the held result; a running preparation finishes into the void. */
    fun forget() {
        key = null
        result = null
    }

    /** Frees the sessions under memory pressure; the next pair loads them again. */
    fun trimMemory() {
        handler.removeCallbacks(idleRelease)
        onWorker(::freeModels)
    }

    // Main thread, like release(), so the shutdown check cannot race it.
    private fun onWorker(task: () -> Unit) {
        if (!worker.isShutdown) worker.execute(task)
    }

    fun release() {
        forget()
        handler.removeCallbacks(idleRelease)
        // Queued behind any running preparation, which still holds the handle.
        worker.execute(::freeModels)
        worker.shutdown()
    }

    companion object {
        private const val TAG = "OrchardAdaptiveMix"

        /** Desktop keeps these natural; the same words on both platforms. */
        internal val EXCLUDED = Regex("\\b(podcast|episode|audiobook|live|concert|performance)\\b",
            RegexOption.IGNORE_CASE)

        /** Sessions outlive a paused listener by this long before their memory goes back. */
        private const val MODEL_IDLE_MS = 10 * 60_000L

        /** Desktop's worker request, minus the stream URLs: the sources are decoded here. */
        internal fun request(
            current: Track,
            next: Track,
            duration: Double,
            position: Double,
            fadeSeconds: Double,
            albumSequential: Boolean,
        ): JSONObject = JSONObject()
            .put("outgoingDuration", duration)
            .put("incomingDuration", next.durationMs / 1000.0)
            .put("position", position)
            .put("currentTrack", current.plannerJson())
            .put("nextTrack", next.plannerJson())
            .put("fadeSeconds", fadeSeconds.toInt())
            .put("albumSequential", albumSequential)

        private fun Track.plannerJson(): JSONObject = JSONObject()
            .put("id", id).put("title", title).put("subtitle", artist)
            .put("artist", artist).put("album", album).put("albumId", albumId)
            .put("durationSeconds", durationMs / 1000.0)
    }
}
