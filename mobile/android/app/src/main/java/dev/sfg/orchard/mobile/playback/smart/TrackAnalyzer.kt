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
import android.net.Uri
import android.util.Log
import androidx.media3.common.util.UnstableApi
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.playback.StreamCache
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

/**
 * Produces [TrackAnalysis] for tracks that are about to be mixed, and hands it to the planner.
 *
 * Two windows per track, not the whole thing. A transition only ever reads the tail of the
 * outgoing track and the head of the incoming one, and a track is both of those at different
 * moments, so both ends are analysed and the middle is never decoded. That is two inferences of
 * roughly 2.3 s each on a mid-range phone rather than one per thirty seconds of song.
 *
 * [analysisFor] is called from the crossfade watcher every tick, so it never blocks or computes:
 * it returns what is already known, and an unanalysed track simply reads as no evidence, which the
 * policy ladder answers with a plain fade.
 */
@UnstableApi
class TrackAnalyzer(
    context: Context,
    private val cache: StreamCache,
) {
    private val tracker = BeatTracker(context)
    private val vocals = VocalTracker(context)

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
        3,
        3,
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
        if (track.id.isBlank() || durationSeconds <= 0) {
            Log.d(TAG, "Skipping ${track.id}: no duration yet")
            return
        }
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
        Log.d(TAG, "Analysing ${track.id} (${track.title}), ${durationSeconds}s, $scope")

        val store = if (scope == AnalysisScope.PLAYBACK) results else queueResults
        executor.execute(
            Job(scope, sequence.incrementAndGet()) {
                try {
                    store[track.id] = analyze(track, uri, durationSeconds, scope)
                } catch (error: Exception) {
                    Log.w(TAG, "Analysis of ${track.id} failed", error)
                    // Recorded as ready-but-empty so a track that cannot be analysed is not retried
                    // on every tick for the rest of the session.
                    store[track.id] = TrackAnalysis(
                        status = TrackAnalysis.STATUS_READY,
                        trackId = track.id,
                        duration = durationSeconds,
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
                        vocals.release()
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

        val source = cache.mediaDataSource(uri) ?: return empty(track, durationSeconds)

        // Pass 1: Whole track mono at low rate for structural features (energy, phrases, etc.)
        val structRate = TrackFeatures.sampleRate.toInt()
        val structDecoded = source.use { AudioDecoder.decodeRegion(it, 0.0, durationSeconds, targetRate = structRate) }
            ?: return empty(track, durationSeconds)
        val (structPcm, _) = structDecoded
        val structSamples = if (abs(structPcm.sampleRate - TrackFeatures.sampleRate) > 1.0) {
            MelSpectrogram.resample(structPcm.samples, structPcm.sampleRate, TrackFeatures.sampleRate)
                ?: return empty(track, durationSeconds)
        } else structPcm.samples
        val features = TrackFeatures.analyze(structSamples, durationSeconds)

        // Pass 2: High-resolution stereo regions for the models (head and tail only).
        // This avoids decoding minutes of audio at 48kHz that the models never see.
        val window = BeatTracker.WINDOW_SECONDS
        val headDecoded = source.use { AudioDecoder.decodeRegionStereo(it, 0.0, minOf(window, durationSeconds), targetRate = 48000) }
        val tailStart = max(0.0, durationSeconds - window)
        val tailDecoded = if (tailStart > window / 2) {
            source.use { AudioDecoder.decodeRegionStereo(it, tailStart, durationSeconds, targetRate = 48000) }
        } else null

        val headPcm = headDecoded?.first?.let { AudioDecoder.Pcm(FloatArray(it.left.size) { i -> (it.left[i] + it.right[i]) * 0.5f }, it.sampleRate) }
        val tailPcm = tailDecoded?.first?.let { AudioDecoder.Pcm(FloatArray(it.left.size) { i -> (it.left[i] + it.right[i]) * 0.5f }, it.sampleRate) }

        val headGrid = headPcm?.let { grid(it, 0.0, it.durationSeconds) }
        val tailGrid = tailPcm?.let { grid(it, tailStart, it.durationSeconds) }

        // The tail governs where the outgoing track is mixed out.
        val leading = tailGrid ?: headGrid

        Log.d(
            TAG,
            "Analysed ${track.id} in ${System.currentTimeMillis() - started}ms " +
                "bpm=${leading?.bpm ?: features?.bpm} conf=${leading?.beatConfidence} " +
                "key=${features?.key} energy=${features?.energyCurve?.size} " +
                "mixOut=${features?.mixOutCandidates?.size}",
        )

        return TrackAnalysis(
            status = TrackAnalysis.STATUS_READY,
            trackId = track.id,
            duration = durationSeconds,
            contentEndTime = features?.contentEndTime?.takeIf { it > 0 } ?: durationSeconds,
            bpm = leading?.bpm ?: features?.bpm ?: 0.0,
            beatInterval = leading?.beatInterval ?: features?.beatInterval ?: 0.0,
            beatConfidence = leading?.beatConfidence ?: features?.beatConfidence ?: 0.0,
            downbeats = (headGrid?.downbeats.orEmpty() + tailGrid?.downbeats.orEmpty())
                .ifEmpty { features?.downbeats.orEmpty() }
                .sorted(),
            firstBeat = headGrid?.firstBeat ?: features?.firstBeat ?: 0.0,
            phraseBoundaries = features?.phraseBoundaries.orEmpty(),
            key = features?.key.orEmpty(),
            keyConfidence = features?.keyConfidence ?: 0.0,
            audibleStartTime = features?.audibleStartTime ?: headPcm?.let { audibleStart(it.samples, it.sampleRate, 0.0) },
            pickupTime = features?.pickupTime,
            introEndTime = features?.introEndTime ?: 0.0,
            outroStartTime = features?.outroStartTime ?: 0.0,
            mixInTime = features?.mixInTime ?: 0.0,
            mixOutTime = features?.mixOutTime ?: 0.0,
            mixInCandidates = features?.mixInCandidates.orEmpty(),
            mixOutCandidates = features?.mixOutCandidates.orEmpty(),
            energyCurve = features?.energyCurve.orEmpty(),
            lowEnergyCurve = features?.lowEnergyCurve.orEmpty(),
            vocalActivityMask = features?.let {
                // Vocal mask only where we have stereo model data.
                val headMask = headDecoded?.let { vocalMask(it.first, features, durationSeconds, 0.0) }
                val tailMask = tailDecoded?.let { vocalMask(it.first, features, durationSeconds, tailStart) }
                mergeMasks(features.energyCurve.size, headMask, tailMask)
            } ?: features?.vocalActivityMask.orEmpty(),
            vocalProbability = features?.vocalProbability ?: 0.0,
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
            key = features.key,
            keyConfidence = features.keyConfidence,
            energyCurve = features.energyCurve,
            vocalProbability = features.vocalProbability,
        )
    }

    /**
     * A vocal-presence value for every point on the energy curve.
     *
     * The policy indexes the mask against energy-curve sample times and requires the two to be the
     * same length, but the model's window is fixed at about 22 seconds, far less than a track. So
     * the mask is built at full length and filled only where the model actually ran: the head and
     * the tail, which are the only regions a transition reads.
     *
     * Everywhere else is left at [NEUTRAL_VOCAL]. That is not a guess dressed up as data; it sits
     * below VOCAL_ACTIVE_THRESHOLD, so unmeasured material can never trip vocal logic, which is
     * exactly how the desktop fallback analyzer behaves.
     */
    private fun vocalMask(
        stereo: AudioDecoder.StereoPcm,
        features: TrackFeatures.Features,
        duration: Double,
        actualStart: Double,
    ): DoubleArray? {
        val curve = features.energyCurve
        if (curve.isEmpty() || !VocalSpectrogram.available) return null

        val window = (VocalTracker.FIXED_FRAMES - 1) * VocalSpectrogram.hop / VocalSpectrogram.sampleRate
        val mask = DoubleArray(curve.size) { NEUTRAL_VOCAL }

        val values = vocals.track(stereo.left, stereo.right, stereo.sampleRate) ?: return null

        for (index in curve.indices) {
            val frame = ((curve[index].time - actualStart) * VocalSpectrogram.frameRate).toInt()
            if (frame in values.indices) {
                mask[index] = values[frame].toDouble()
            }
        }
        return mask
    }

    private fun mergeMasks(size: Int, head: DoubleArray?, tail: DoubleArray?): List<Double> {
        val merged = DoubleArray(size) { NEUTRAL_VOCAL }
        if (head != null) {
            for (i in merged.indices) if (head[i] != NEUTRAL_VOCAL) merged[i] = head[i]
        }
        if (tail != null) {
            for (i in merged.indices) if (tail[i] != NEUTRAL_VOCAL) merged[i] = tail[i]
        }
        return merged.toList()
    }

    /** Recorded ready-but-empty so a track that cannot be decoded is not retried every tick. */
    private fun empty(track: Track, durationSeconds: Double) = TrackAnalysis(
        status = TrackAnalysis.STATUS_READY,
        trackId = track.id,
        duration = durationSeconds,
    )

    /** Tracks one window of the already-decoded PCM, resampled to the model's rate. */
    private fun grid(pcm: AudioDecoder.Pcm, startSeconds: Double, endSeconds: Double): BeatTracker.Grid? {
        val from = (startSeconds * pcm.sampleRate).toInt().coerceIn(0, pcm.samples.size)
        val to = (endSeconds * pcm.sampleRate).toInt().coerceIn(from, pcm.samples.size)
        if (to - from < pcm.sampleRate) return null
        val window = pcm.samples.copyOfRange(from, to)
        val resampled = MelSpectrogram.resample(window, pcm.sampleRate) ?: return null
        return tracker.track(resampled, offsetSeconds = startSeconds)
    }

    /**
     * Where the audio first rises above its own noise floor, so a transition is not cued into
     * lead-in silence. Measured against the region's own peak rather than an absolute threshold,
     * since nothing here knows how the track was mastered.
     */
    private fun audibleStart(samples: FloatArray, rate: Double, offsetSeconds: Double): Double? {
        if (samples.isEmpty()) return null
        var peak = 0f
        for (sample in samples) peak = max(peak, abs(sample))
        if (peak <= 0f) return null
        val threshold = peak * AUDIBLE_FRACTION
        for (index in samples.indices) {
            if (abs(samples[index]) >= threshold) {
                return offsetSeconds + index / rate
            }
        }
        return null
    }

    fun release() {
        executor.shutdownNow()
        tracker.release()
    }

    private companion object {
        const val TAG = "OrchardTrackAnalyzer"
        const val AUDIBLE_FRACTION = 0.02f

        /**
         * What an unmeasured instant reads as. Below VOCAL_ACTIVE_THRESHOLD by design, so absence
         * of measurement is never mistaken for absence of a vocal, or for the presence of one.
         */
        const val NEUTRAL_VOCAL = 0.5
    }
}
