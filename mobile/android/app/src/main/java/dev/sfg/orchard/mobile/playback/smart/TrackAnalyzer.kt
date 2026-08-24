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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.Semaphore
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

    /**
     * Lets the two tracks of one transition through the model-grade passes together, and no more.
     *
     * The pool runs three jobs so queue-scope work, which is one cheap mono decode, keeps up with a
     * whole playlist. The playback-scope pass is a different animal: high-rate stereo audio and two
     * ONNX models, tens of megabytes live. Three of those at once exhausted a 192MB heap and took
     * the process down inside MediaCodec's own callback.
     *
     * Two, not one, because a transition needs *both* of its tracks analysed before it starts, and
     * serialising them puts the second one's whole runtime on the critical path — which is how a
     * pair came to finish eighteen seconds after the transition it was for. Now that each region is
     * released before the next is decoded, two passes cost about what one used to.
     */
    private val modelPass = Semaphore(2)

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
        results[track.id] ?: queueResults[track.id] ?: TrackAnalysis(trackId = track.id)

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
                    val result = analyze(track, uri, effectiveDuration, scope)
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

        // Each pass opens its own handle. `use` closes the source at the end of its block, so the
        // three passes cannot share one: closing it after pass 1 left passes 2 and 3 decoding a
        // dead handle, which surfaced as "Failed to instantiate extractor" and cost every track its
        // beat grid — and with no grid the policy can never reach BEATMATCHED, so every transition
        // in the app quietly came out as a plain fade.
        fun openSource() = cache.mediaDataSource(uri)
        if (openSource() == null) return empty(track, durationSeconds)

        // Pass 1: Whole track mono at low rate for structural features (energy, phrases, etc.)
        val structural = structural(::openSource, durationSeconds) ?: return empty(track, durationSeconds)
        val features = structural.features

        // Pass 2: High-resolution stereo regions for the models (head and tail only).
        // This avoids decoding minutes of audio at 48kHz that the models never see.
        val window = BeatTracker.WINDOW_SECONDS
        val tailStart = max(0.0, durationSeconds - window)

        // 30s of stereo is ~10MB, and the mono mix, the resampled copy and the mel buffer are all
        // live at once on top of it. Each region is therefore decoded, reduced to the few numbers
        // that outlive it, and dropped before the next one is opened, so a track's peak is one
        // region rather than two. [modelPass] then keeps whole tracks from overlapping.
        val head: Region?
        val tail: Region?
        modelPass.acquire()
        try {
            // The vocal model's window is shorter than the region, so each region keeps the end a
            // transition actually reads: the head is entered near its start, the tail is left from
            // its end.
            head = region(::openSource, 0.0, minOf(window, durationSeconds), features, VocalTracker.Keep.LEADING)
            tail = if (tailStart > window / 2) {
                region(::openSource, tailStart, durationSeconds, features, VocalTracker.Keep.TRAILING)
            } else null
        } finally {
            modelPass.release()
        }

        val headGrid = head?.grid
        val tailGrid = tail?.grid

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
            audibleStartTime = features?.audibleStartTime ?: head?.audibleStart,
            pickupTime = features?.pickupTime,
            introEndTime = features?.introEndTime ?: 0.0,
            outroStartTime = features?.outroStartTime ?: 0.0,
            mixInTime = features?.mixInTime ?: 0.0,
            mixOutTime = features?.mixOutTime ?: 0.0,
            mixInCandidates = features?.mixInCandidates.orEmpty(),
            mixOutCandidates = features?.mixOutCandidates.orEmpty(),
            energyCurve = features?.energyCurve.orEmpty(),
            lowEnergyCurve = features?.lowEnergyCurve.orEmpty(),
            // Vocal mask only where we have stereo model data.
            vocalActivityMask = features?.let {
                mergeMasks(it.energyCurve.size, head?.vocalMask, tail?.vocalMask)
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
            vocalActivityMask = features.vocalActivityMask,
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
        actualStart: Double,
        keep: VocalTracker.Keep,
    ): DoubleArray? {
        val curve = features.energyCurve
        if (curve.isEmpty() || !VocalSpectrogram.available) return null

        val mask = DoubleArray(curve.size) { NEUTRAL_VOCAL }
        val presence = vocals.track(stereo.left, stereo.right, stereo.sampleRate, keep) ?: return null

        // The model measures one window of the region rather than all of it, so its frame zero sits
        // `startSeconds` into the region, not at the region's own start.
        val from = actualStart + presence.startSeconds
        for (index in curve.indices) {
            val frame = ((curve[index].time - from) * VocalSpectrogram.frameRate).toInt()
            if (frame in presence.values.indices) {
                mask[index] = presence.values[frame].toDouble()
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
    /**
     * The beat grid of one decoded region, with beat times stated against the whole track.
     *
     * [offsetSeconds] is where the region begins in the track, and is only ever added to the times
     * that come back. It used to double as the start of a slice taken from [pcm], which worked for
     * the head, whose offset is zero, and silently produced nothing for the tail: a region decoded
     * from 105s onwards holds its own samples from index 0, so slicing it at 105s left an empty
     * window and no grid at all.
     */
    /** Everything a decoded region contributes, once its audio has been let go of. */
    private class Region(
        val grid: BeatTracker.Grid?,
        val vocalMask: DoubleArray?,
        val audibleStart: Double?,
    )

    /**
     * What the structural pass contributes, once its audio has been let go of. Null [features]
     * means the analyzer declined the track; a null [Structural] means it could not be decoded at
     * all, which is worth abandoning the whole analysis for rather than paying for two more
     * decodes of a container nothing can read.
     */
    private class Structural(val features: TrackFeatures.Features?)

    /**
     * Decodes the whole track at the structural analyzer's own low rate and returns only what it
     * measured.
     *
     * A function rather than a block in [analyze] for the same reason as [region], and more
     * urgently: this is the largest buffer the app ever allocates, and the model passes below want
     * tens of megabytes of their own on top of it. Held as a local of [analyze] it stayed reachable
     * until the whole analysis finished, so the two costs were paid at once.
     */
    private fun structural(
        openSource: () -> MediaDataSource?,
        durationSeconds: Double,
    ): Structural? {
        val decoded = openSource()?.use {
            AudioDecoder.decodeRegion(it, 0.0, durationSeconds, targetRate = TrackFeatures.sampleRate.toInt())
        } ?: return null
        val (pcm, _) = decoded
        // Ordinarily a no-op now that the decoder resamples as it goes; the Opus decoder offers
        // only its own rates, so that path still arrives needing conversion.
        val samples = if (abs(pcm.sampleRate - TrackFeatures.sampleRate) > 1.0) {
            MelSpectrogram.resample(pcm.samples, pcm.sampleRate, TrackFeatures.sampleRate) ?: return null
        } else pcm.samples
        return Structural(TrackFeatures.analyze(samples, durationSeconds))
    }

    /**
     * Decodes one high-rate stereo region, runs both models over it, and returns only the results.
     *
     * The point of the function boundary is the audio: the stereo buffer, its mono mix and the
     * resampled copy are all local, so they are collectible the moment this returns instead of
     * staying live until the whole analysis finishes. Two regions' worth held at once, times three
     * analysis threads, is what exhausted the heap.
     */
    private fun region(
        openSource: () -> MediaDataSource?,
        startSeconds: Double,
        endSeconds: Double,
        features: TrackFeatures.Features?,
        keep: VocalTracker.Keep,
    ): Region? {
        // Decoded at the vocal model's rate rather than the container's. It is the higher of the
        // two rates this region feeds, so nothing is lost, and it saves resampling a stereo region
        // for that model and holding both copies while the beat model's own copy is made.
        val decoded = openSource()?.use {
            AudioDecoder.decodeRegionStereo(
                it, startSeconds, endSeconds, targetRate = VocalSpectrogram.sampleRate.toInt(),
            )
        } ?: return null
        val (stereo, actualStart) = decoded
        val mono = AudioDecoder.Pcm(
            FloatArray(stereo.left.size) { i -> (stereo.left[i] + stereo.right[i]) * 0.5f },
            stereo.sampleRate,
        )
        return Region(
            // The extractor seeks to a sync sample at or before what was asked for, so the region's
            // real start is what its beat times must be stated against, not the requested one.
            grid = grid(mono, offsetSeconds = actualStart),
            vocalMask = features?.let { vocalMask(stereo, it, actualStart, keep) },
            audibleStart = audibleStart(mono.samples, mono.sampleRate, actualStart),
        )
    }

    private fun grid(pcm: AudioDecoder.Pcm, offsetSeconds: Double): BeatTracker.Grid? {
        if (pcm.samples.size < pcm.sampleRate) return null
        val resampled = MelSpectrogram.resample(pcm.samples, pcm.sampleRate) ?: return null
        return tracker.track(resampled, offsetSeconds = offsetSeconds)
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
