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

package dev.sfg.orchard.mobile.playback.smart

import android.content.Context
import android.media.MediaDataSource
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.playback.StreamCache
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.max

/**
 * What a piece of analysis is for, which decides how much it is allowed to cost.
 *
 * Ported from Orchard desktop's `forPlayback` flag in `smartCrossfadeAnalysis.js`, and for the
 * same reason: the models earn their inference cost only for the two decks around a live
 * transition. Best Mix looks at a whole queue at once and needs tempo, key and energy (all of
 * which the DSP analyzer produces) so it must never pay for a model pass.
 */
enum class AnalysisScope {
    /** The tracks around a transition that is about to happen. Runs the models. */
    PLAYBACK,

    /** A queue Best Mix is ordering. [TrackFeatures] only: no beat model, no vocal model. */
    QUEUE,
}

/** Catalog metadata wins when present; otherwise use the duration measured from cached media. */
internal fun analysisDuration(catalogSeconds: Double, containerSeconds: Double?): Double =
    catalogSeconds.takeIf { it.isFinite() && it > 0 }
        ?: containerSeconds?.takeIf { it.isFinite() && it > 0 }
        ?: 0.0

/**
 * Produces [TrackAnalysis] for tracks that are about to be mixed, and hands it to the planner.
 *
 * Two windows per track, not the whole thing. A transition only ever reads the tail of the
 * outgoing track and the head of the incoming one, and a track is both of those at different
 * moments, so both ends are analysed and the middle is never decoded. Each end covers 60 seconds
 * and uses three Beat This chunks. LiteRT runs FP32 on GPU when available, with INT8 ONNX on CPU
 * as the fallback.
 *
 * [analysisFor] is called from the crossfade watcher every tick, so it never blocks or computes:
 * it returns what is already known, and an unanalysed track simply reads as no evidence, which the
 * policy ladder answers with a plain fade.
 */
@UnstableApi
class TrackAnalyzer(
    context: Context,
    private val cache: StreamCache,
    private val featureStore: BestMixFeatureStore? = null,
) {
    private val tracker = BeatTracker(context)

    /**
     * Model-grade analysis, and the only thing [analysisFor] will hand the transition planner.
     *
     * Kept apart from [queueResults] rather than merged under a flag, because the two are not
     * interchangeable: a DSP-only pass still reports a beat confidence, from the analyzer's own
     * autocorrelation, and the policy ladder would read that as authorization to beat-match. It
     * must never see one.
     */
    private val results = ConcurrentHashMap<String, TrackAnalysis>()

    /** DSP-only analysis, for Best Mix. Superseded by [results] the moment a full pass lands. */
    private val queueResults = ConcurrentHashMap<String, TrackAnalysis>()

    private val runningPlayback = ConcurrentHashMap.newKeySet<String>()
    private val runningQueue = ConcurrentHashMap.newKeySet<String>()
    private val sequence = AtomicLong()

    /**
     * Called on the analysis thread whenever a result lands, so a queue waiting on evidence can
     * re-read it. Never called with a lock held; the callback must not block.
     */
    @Volatile
    var onAnalysed: ((Track) -> Unit)? = null

    /**
     * Single-threaded but priority-ordered.
     *
     * Best Mix can enqueue a whole queue's worth of passes at once, and the transition two tracks
     * away must not wait behind twenty of them, so a playback request jumps whatever background
     * work is already queued. The sequence number keeps equal priorities in submission order,
     * which [PriorityBlockingQueue] does not do on its own.
     */
    private val executor = ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        PriorityBlockingQueue<Runnable>(16) { left, right ->
            val leftJob = left as? Job
            val rightJob = right as? Job
            if (leftJob != null && rightJob != null) leftJob.compareTo(rightJob) else 0
        },
    ) { runnable ->
        Thread(runnable, "orchard-analysis").apply {
            isDaemon = true
            // Normal priority, not minimum. Android maps Thread.MIN_PRIORITY to nice 19, which puts
            // the thread in the background cgroup and caps it at a small share of CPU, analysis
            // then takes tens of seconds for work the hardware can do in a few. The audio output
            // thread runs far above normal priority regardless, so playback is not what this
            // competes with; a whole-track decode simply needs a real share of a core to finish in
            // a time that is useful before the transition it is for.
            priority = Thread.NORM_PRIORITY
        }
    }

    private class Job(
        val scope: AnalysisScope,
        private val order: Long,
        private val body: () -> Unit,
    ) : Runnable, Comparable<Job> {
        override fun run() = body()
        override fun compareTo(other: Job): Int {
            val byScope = scope.ordinal - other.scope.ordinal
            return if (byScope != 0) byScope else order.compareTo(other.order)
        }
    }

    /**
     * What is known about [track] right now: never a computation, never a block.
     *
     * Returns an empty analysis for anything not yet finished, which [assessTransitionTier] reads
     * as no evidence rather than as a failure. Model-grade only: see [queueAnalysisFor].
     */
    fun analysisFor(track: Track): TrackAnalysis =
        results[track.id] ?: TrackAnalysis(trackId = track.id)

    /**
     * What Best Mix is allowed to order on: a full pass where one exists, the DSP-only pass
     * otherwise. Ordering a queue needs tempo, key and energy, none of which is a model's opinion.
     */
    fun queueAnalysisFor(track: Track): TrackAnalysis =
        results[track.id] ?: queueResults[track.id] ?: TrackAnalysis(trackId = track.id)

    /** True once [track] has a result of any scope, including a failure. Nothing more will arrive. */
    fun isAnalysed(track: Track): Boolean =
        results.containsKey(track.id) || queueResults.containsKey(track.id)

    /**
     * Queues [track] for analysis if it is not already done or in flight.
     *
     * Requires the track to be fully cached: a partially fetched file may not even have a parsable
     * container, and analysing the head of a track whose tail has not arrived would produce a grid
     * for audio the listener will never reach through this transition. Callers re-request as
     * caching progresses; this is cheap to call repeatedly.
     *
     * A [AnalysisScope.QUEUE] result never satisfies a later [AnalysisScope.PLAYBACK] request: a
     * track Best Mix analysed first is analysed again, with the models, once it is actually about
     * to play. That costs one decode, once, for the two tracks around a transition.
     */
    fun request(
        track: Track,
        uri: Uri,
        durationSeconds: Double,
        scope: AnalysisScope = AnalysisScope.PLAYBACK,
    ) {
        if (track.id.isBlank()) return
        if (results.containsKey(track.id) || track.id in runningPlayback) return
        val running = if (scope == AnalysisScope.PLAYBACK) runningPlayback else runningQueue
        if (scope == AnalysisScope.QUEUE && queueResults.containsKey(track.id)) return
        if (!cache.isFullyCached(uri)) {
            // The common reason nothing is analysed: caching a whole track takes as long as it
            // takes, and skipping through a queue cancels prefetches before they finish.
            Log.d(TAG, "Waiting on cache for ${track.id} (${track.title})")
            return
        }
        if (!running.add(track.id)) return

        val store = if (scope == AnalysisScope.PLAYBACK) results else queueResults
        executor.execute(
            Job(scope, sequence.incrementAndGet()) {
                var effectiveDuration = durationSeconds
                try {
                    if (!effectiveDuration.isFinite() || effectiveDuration <= 0) {
                        val measured = cache.mediaDataSource(uri)?.use(AudioDecoder::containerDurationSeconds)
                        effectiveDuration = analysisDuration(effectiveDuration, measured)
                    }
                    if (effectiveDuration <= 0) {
                        Log.d(TAG, "Skipping ${track.id}: cached media has no duration")
                        store[track.id] = empty(track, 0.0)
                        return@Job
                    }
                    Log.d(TAG, "Analysing ${track.id} (${track.title}), ${effectiveDuration}s, $scope")
                    val result = AudioWorkLimiter.run {
                        analyze(track, uri, effectiveDuration, scope)
                    }
                    store[track.id] = result
                    Log.d(
                        TAG,
                        "Cues ${track.id}: contentEnd=${result.contentEndTime} " +
                            "mixIn=${result.mixInTime} " +
                            "mixInCandidates=${result.mixInCandidates.joinToString { "${it.type}@${it.time}" }} " +
                            "mixOut=${result.mixOutTime} " +
                            "mixOutCandidates=${result.mixOutCandidates.joinToString { "${it.type}@${it.time}" }}",
                    )
                } catch (error: Throwable) {
                    // Throwable, not Exception: analysis leans on native libraries, and a
                    // LinkageError or an OOM from one of them is an Error. Uncaught on a pool
                    // thread that is nobody's parent, it takes the whole app down for work
                    // whose entire failure mode is meant to be "this track goes unanalysed".
                    Log.w(TAG, "Analysis of ${track.id} failed", error)
                    // Recorded as ready-but-empty so a track that cannot be analysed is not retried
                    // on every tick for the rest of the session.
                    store[track.id] = TrackAnalysis(
                        status = TrackAnalysis.STATUS_READY,
                        trackId = track.id,
                        duration = effectiveDuration,
                    )
                } finally {
                    running.remove(track.id)
                    // The session is worth keeping across the two windows of one track and across a
                    // current/next pair queued together, but not across the minutes of playback
                    // between transitions: it holds hundreds of megabytes of native heap that a
                    // backgrounded music player cannot justify. Reloading costs about a second, on
                    // work that already takes fifteen. Queue-scope work never loads them at all,
                    // so it is not what this waits on.
                    if (runningPlayback.isEmpty()) {
                        tracker.release()
                    }
                    runCatching { onAnalysed?.invoke(track) }
                }
            },
        )
    }

    private fun analyze(
        track: Track,
        uri: Uri,
        durationSeconds: Double,
        scope: AnalysisScope,
    ): TrackAnalysis {
        if (scope == AnalysisScope.QUEUE) return analyzeForQueue(track, uri, durationSeconds)
        val started = System.currentTimeMillis()

        // Each pass opens its own handle. `use` closes the source at the end of its block, so the
        // two passes cannot share one: closing it after pass 1 left pass 2 decoding a
        // dead handle, which surfaced as "Failed to instantiate extractor" and cost every track its
        // beat grid — and with no grid the policy can never reach BEATMATCHED, so every transition
        // in the app quietly came out as a plain fade.
        fun openSource() = cache.mediaDataSource(uri)
        if (openSource() == null) return empty(track, durationSeconds)

        // V3 plans an outgoing 60-second tail against an incoming 60-second head.
        // Keep both roles because this track may be either side of a later queue pair.
        val window = PLANNER_WINDOW_SECONDS
        val tailStart = max(0.0, durationSeconds - window)
        val head = plannerRegion(::openSource, 0.0, minOf(window, durationSeconds), durationSeconds)
            ?: return empty(track, durationSeconds)
        val tail = if (tailStart > 0.0) {
            plannerRegion(::openSource, tailStart, durationSeconds, durationSeconds)
                ?: return empty(track, durationSeconds)
        } else head
        val headPayload = JSONObject(head.plannerJson)
        val tailPayload = JSONObject(tail.plannerJson)

        Log.d(
            TAG,
            "Analysed ${track.id} in ${System.currentTimeMillis() - started}ms " +
                "tailBpm=${tail.grid.bpm} headBpm=${head.grid.bpm} " +
                "tailFrames=${tailPayload.optJSONArray("transitionFeatureFrames")?.length()} " +
                "headFrames=${headPayload.optJSONArray("transitionFeatureFrames")?.length()}",
        )

        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = track.id,
            duration = durationSeconds,
            contentEndTime = tailPayload.optDouble("contentEndTime", durationSeconds),
            bpm = tail.grid.bpm,
            beatInterval = tail.grid.beatInterval,
            beatConfidence = tail.grid.beatConfidence,
            downbeats = (head.grid.downbeats + tail.grid.downbeats).distinct().sorted(),
            firstBeat = head.grid.firstBeat,
            key = tailPayload.optString("key", ""),
            keyConfidence = tailPayload.optDouble("keyConfidence", 0.0),
            audibleStartTime = headPayload.optDouble("audibleStartTime", 0.0),
            plannerFeaturesJson = tail.plannerJson,
            plannerHeadJson = head.plannerJson,
            plannerTailJson = tail.plannerJson,
        )
    }

    /**
     * The DSP-only pass Best Mix orders on: one mono decode and [TrackFeatures], nothing else.
     *
     * No [BeatTracker] and no [VocalTracker]. Ordering a queue is a question about tempo, key and
     * energy, and the analyzer answers all three without inference; where the beats *are* only
     * matters once a transition is actually being planned, and that track gets a full pass then.
     * This is also why the result is stored apart from [results]: the tempo here is the analyzer's
     * own autocorrelation, which the policy is explicitly built not to trust.
     *
     * The saving is the whole point. The two model passes are the expensive half of a full
     * analysis, and Best Mix runs over a queue rather than a pair.
     */
    private fun analyzeForQueue(track: Track, uri: Uri, durationSeconds: Double): TrackAnalysis {
        val started = System.currentTimeMillis()

        val source = cache.mediaDataSource(uri) ?: return empty(track, durationSeconds)
        val targetRate = TrackFeatures.sampleRate.toInt()
        val decoded = source.use { AudioDecoder.decodeRegion(it, 0.0, durationSeconds, targetRate = targetRate) }
            ?: return empty(track, durationSeconds)
        val (pcm, _) = decoded

        val samples = if (abs(pcm.sampleRate - TrackFeatures.sampleRate) > 1.0) {
            MelSpectrogram.resample(pcm.samples, pcm.sampleRate, TrackFeatures.sampleRate)
                ?: return empty(track, durationSeconds)
        } else pcm.samples

        val features = TrackFeatures.analyze(samples, durationSeconds)
            ?: return empty(track, durationSeconds)
        featureStore?.put(track.id, features)

        Log.d(
            TAG,
            "Analysed ${track.id} for the queue in ${System.currentTimeMillis() - started}ms " +
                "bpm=${features.bpm} key=${features.key} energy=${features.energyCurve.size}",
        )

        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = track.id,
            duration = durationSeconds,
            contentEndTime = features.contentEndTime.takeIf { it > 0 } ?: durationSeconds,
            bpm = features.bpm,
            beatInterval = features.beatInterval,
            beatConfidence = features.beatConfidence,
            downbeats = features.downbeats,
            firstBeat = features.firstBeat,
            phraseBoundaries = features.phraseBoundaries,
            key = features.key,
            keyConfidence = features.keyConfidence,
            audibleStartTime = features.audibleStartTime,
            pickupTime = features.pickupTime,
            introEndTime = features.introEndTime,
            outroStartTime = features.outroStartTime,
            mixInTime = features.mixInTime,
            mixOutTime = features.mixOutTime,
            mixInCandidates = features.mixInCandidates,
            mixOutCandidates = features.mixOutCandidates,
            energyCurve = features.energyCurve,
            lowEnergyCurve = features.lowEnergyCurve,
            plannerFeaturesJson = features.plannerFeaturesJson,
            vocalActivityMask = features.vocalActivityMask,
            vocalProbability = features.vocalProbability,
        )
    }

    /** Recorded ready-but-empty so a track that cannot be decoded is not retried every tick. */
    private fun empty(track: Track, durationSeconds: Double) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        trackId = track.id,
        duration = durationSeconds,
    )

    private class PlannerRegion(val grid: BeatTracker.Grid, val plannerJson: String)

    private fun plannerRegion(
        openSource: () -> MediaDataSource?,
        startSeconds: Double,
        endSeconds: Double,
        trackDurationSeconds: Double,
    ): PlannerRegion? {
        val decoded = openSource()?.use { source ->
            AudioDecoder.decodeRegionStereo(source, startSeconds, endSeconds, targetRate = 44_100)
        } ?: return null
        val (stereo, actualStart) = decoded
        val first = ((startSeconds - actualStart) * stereo.sampleRate).toInt().coerceAtLeast(0)
        val count = minOf(
            ((endSeconds - startSeconds) * stereo.sampleRate).toInt(),
            stereo.left.size - first,
            stereo.right.size - first,
        )
        if (count < stereo.sampleRate.toInt()) return null
        val mono = FloatArray(count) { index ->
            (stereo.left[first + index] + stereo.right[first + index]) * 0.5f
        }
        val grid = grid(AudioDecoder.Pcm(mono, stereo.sampleRate), startSeconds) ?: return null
        val payload = TrackFeatures.plannerWindow(
            mono, stereo.sampleRate, startSeconds, trackDurationSeconds, grid,
        ) ?: return null
        return PlannerRegion(grid, payload)
    }

    private fun grid(pcm: AudioDecoder.Pcm, offsetSeconds: Double): BeatTracker.Grid? {
        if (pcm.samples.size < pcm.sampleRate) return null
        val resampled = MelSpectrogram.resample(pcm.samples, pcm.sampleRate) ?: return null
        return tracker.track(resampled, offsetSeconds = offsetSeconds)
    }

    fun release() {
        executor.shutdownNow()
        tracker.release()
    }

    private companion object {
        const val TAG = "OrchardTrackAnalyzer"
        const val PLANNER_WINDOW_SECONDS = 60.0
    }
}
