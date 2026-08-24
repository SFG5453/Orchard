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
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Renders a beat-matched overlap ahead of the seam, so it is on disk before the playhead needs it.
 *
 * The timing is the whole design constraint. Rendering means decoding two stereo regions and
 * running a phase vocoder over one of them (seconds of work) which cannot happen at the moment
 * the transition starts. So preparation is triggered when the plan first becomes visible, which is
 * typically tens of seconds ahead, and the result is keyed by the pair it was rendered for.
 *
 * Nothing here is required. A pair that cannot be prepared in time, or at all, simply has no
 * prepared render when the seam arrives and the engine runs its volume ramp instead, which is the
 * same transition it would have run before any of this existed.
 */
@UnstableApi
class TransitionPreparer(
    private val context: Context,
    private val cache: StreamCache,
) {
    /** A rendered overlap on disk, and where it sits on the outgoing track's timeline. */
    data class Prepared(
        val file: File,
        val startSeconds: Double,
        val endSeconds: Double,
        /** Where the incoming track resumes once the overlap ends, on its own timeline. */
        val incomingResumeSeconds: Double,
        val stretchRatio: Double,
    )

    private val ready = ConcurrentHashMap<String, Prepared>()
    private val running = ConcurrentHashMap.newKeySet<String>()

    /** Pairs whose refusal has already been reported, so a per-tick gate logs once. */
    private val explained = ConcurrentHashMap.newKeySet<String>()

    /**
     * Pairs the renderer itself turned down. Distinct from [explained], which only suppresses
     * repeated logging of gates that may still come good -- audio finishing its download is the
     * ordinary case. A refusal from the renderer cannot come good: it is a verdict on the plan and
     * the audio, neither of which changes between ticks, so re-attempting it only pays for two
     * decodes again.
     */
    private val declined = ConcurrentHashMap.newKeySet<String>()

    private fun explainOnce(key: String, reason: String) {
        if (explained.add(key)) Log.d(TAG, "No render for $key: $reason")
    }
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "orchard-transition-render").apply {
            isDaemon = true
            // Normal priority, for the reason spelled out on the analysis pool in [TrackAnalyzer]:
            // Android maps MIN_PRIORITY to nice 19 and the background cgroup, a small share of one
            // core. This thread runs a phase vocoder over two whole tracks and has a hard deadline,
            // the transition it is for. Missing it is silent — the engine drops to the plain fade —
            // so the cost of starving this thread is not a slow mix, it is no mix at all.
            priority = Thread.NORM_PRIORITY
        }
    }

    /** The prepared overlap for this pair, or null. Never computes; safe on the watcher thread. */
    fun preparedFor(outgoing: Track, incoming: Track): Prepared? = ready[key(outgoing, incoming)]

    /**
     * Renders the overlap for this pair if it is worth rendering and not already done.
     *
     * Called on every tick while the plan is visible, so it must be cheap to reject: anything that
     * disqualifies the pair is checked before the executor is touched.
     */
    fun prepare(
        outgoing: Track,
        outgoingUri: Uri,
        outgoingAnalysis: TrackAnalysis,
        incoming: Track,
        incomingUri: Uri,
        incomingAnalysis: TrackAnalysis,
        plan: TransitionPlan,
    ) {
        val key = key(outgoing, incoming)
        // Only the top tier earns a render. Anything less has already been judged not to support
        // beat-matching, and stretching against a grid nobody trusts is exactly what the policy
        // ladder exists to prevent.
        //
        // Every gate below reports itself once per pair. This runs on every tick, so logging each
        // refusal outright would bury the log; but a pair that silently never renders is the whole
        // reason a mix turns into a fade, and that has to be answerable after the fact.
        if (plan.transitionStyle != TransitionStyle.DJ_BLEND) {
            return explainOnce(key, "style is ${plan.transitionStyle}, not DJ_BLEND")
        }
        if (plan.transitionBeats <= 0 || plan.fadeSeconds <= 0) {
            return explainOnce(key, "empty window: beats=${plan.transitionBeats} fade=${plan.fadeSeconds}s")
        }
        val policy = assessTransitionTier(outgoingAnalysis, incomingAnalysis)
        if (policy.tier != TransitionTier.BEATMATCHED) {
            return explainOnce(key, "tier is ${policy.tier}, not BEATMATCHED")
        }

        if (ready.containsKey(key) || declined.contains(key) || !running.add(key)) return
        if (!cache.isFullyCached(outgoingUri) || !cache.isFullyCached(incomingUri)) {
            running.remove(key)
            return explainOnce(
                key,
                "not fully cached: outgoing=${cache.isFullyCached(outgoingUri)} " +
                    "incoming=${cache.isFullyCached(incomingUri)}",
            )
        }

        executor.execute {
            try {
                val rendered = render(key, outgoingUri, outgoingAnalysis, incomingUri, incomingAnalysis, plan)
                if (rendered == null) {
                    // `prepare` is called on every tick for the whole run-up to the transition, and
                    // a refusal is final, so without this the pair decodes both tracks again every
                    // few hundred milliseconds for a minute or more and throws all of it away. That
                    // is how this was found. The engine's volume ramp covers the seam.
                    declined.add(key)
                    explainOnce(key, "renderer declined; see the OrchardTransition log for why")
                    return@execute
                }
                ready[key] = rendered
                Log.d(TAG, "Prepared transition $key")
            } catch (error: Exception) {
                Log.w(TAG, "Could not prepare $key", error)
            } finally {
                running.remove(key)
            }
        }
    }

    private fun render(
        key: String,
        outgoingUri: Uri,
        outgoingAnalysis: TrackAnalysis,
        incomingUri: Uri,
        incomingAnalysis: TrackAnalysis,
        plan: TransitionPlan,
    ): Prepared? {
        val started = System.currentTimeMillis()
        val overlap = plan.fadeSeconds

        // Both sources are cropped around the beat the mix aligns on, with margin either side: the
        // renderer needs room to stretch into, and the anchor is not at the edge of the overlap.
        val margin = overlap * 0.5 + MARGIN_SECONDS
        val outAnchor = nearestDownbeat(outgoingAnalysis, plan.transitionStart) ?: return null
        // The rendered buffer must start at the same cue the live two-player fallback uses. Using
        // the handoff/drop here silently discarded the intro and made rendered and live versions
        // of the same plan play different sections of the incoming track.
        val inAnchor = nearestDownbeat(incomingAnalysis, plan.incomingCueTime)
            ?: plan.incomingCueTime

        val (outgoingPcm, outgoingSliceStart) = decodeAround(outgoingUri, outAnchor, margin) ?: return null
        val (incomingPcm, incomingSliceStart) = decodeAround(incomingUri, inAnchor, margin) ?: return null
        val outgoingSliceEnd = outgoingSliceStart + outgoingPcm.left.size / TransitionRenderer.SAMPLE_RATE

        val rendered = TransitionRenderer.render(
            outgoing = TransitionRenderer.Source(
                left = outgoingPcm.left,
                right = outgoingPcm.right,
                bpm = plan.outgoingBpm,
                beats = beatGrid(outgoingAnalysis, outgoingSliceStart, outgoingPcm.left.size),
                downbeats = rebased(outgoingAnalysis.downbeats, outgoingSliceStart, outgoingPcm.left.size),
            ),
            incoming = TransitionRenderer.Source(
                left = incomingPcm.left,
                right = incomingPcm.right,
                // Octave-aligned by the planner, not the analyzed BPM: see [TransitionPlan].
                bpm = plan.incomingBpm,
                beats = beatGrid(incomingAnalysis, incomingSliceStart, incomingPcm.left.size),
                downbeats = rebased(incomingAnalysis.downbeats, incomingSliceStart, incomingPcm.left.size),
            ),
            // The planner owns *where*; the engine owns how long the overlap is, what shape it
            // takes, and which strategy suits the two spectra. That division is why none of
            // [TransitionPlan]'s handoff, bed, bassSwap or filterSweep are passed any more: they
            // described a renderer that decided nothing.
            outgoingAnchor = TransitionRenderer.Anchor(
                startSeconds = outAnchor - outgoingSliceStart,
                endSeconds = plan.transitionEnd - outgoingSliceStart,
            ),
            incomingAnchor = TransitionRenderer.Anchor(
                startSeconds = inAnchor - incomingSliceStart,
            ),
            vocalDuck = duckCurve(outgoingAnalysis, outgoingSliceStart, outgoingSliceEnd),
        ) ?: return null

        val file = TransitionAudio.writeWav(rendered, File(directory(), "$key.wav")) ?: return null
        Log.d(
            TAG,
            "Rendered ${rendered.durationSeconds}s ${rendered.strategy} beats=${rendered.beats} " +
                "stretch=${rendered.stretchRatio} in ${System.currentTimeMillis() - started}ms",
        )

        // Every timing below is the engine's own, not the anchor it was asked for. It picks a
        // beat-aligned start inside the requested window and the overlap length from the phrase
        // structure, so re-deriving these from the plan would place the mix at a cue that was
        // never rendered.
        val startSeconds = outgoingSliceStart + rendered.outgoingStart
        return Prepared(
            file = file,
            startSeconds = startSeconds,
            endSeconds = startSeconds + rendered.durationSeconds,
            // The overlap already contains the incoming track from its entry point through to the
            // end of the mix, so playback resumes past all of it.
            incomingResumeSeconds = incomingSliceStart + rendered.incomingResume,
            stretchRatio = rendered.stretchRatio,
        )
    }

    /**
     * The beat grid across the decoded region, in seconds from the start of that region.
     *
     * The analyzer stores downbeats but not every beat, so the beats are laid down from the first
     * beat at the analyzed interval. The engine places candidates on downbeats and only measures
     * against the beats, so a synthesized grid that drifts slightly is a scoring nuance; a missing
     * one makes it score blind.
     */
    private fun beatGrid(analysis: TrackAnalysis, from: Double, frames: Int): DoubleArray {
        val interval = analysis.beatInterval.takeIf { it > 0.0 }
            ?: analysis.bpm.takeIf { it > 0.0 }?.let { 60.0 / it }
            ?: return DoubleArray(0)
        val until = from + frames / TransitionRenderer.SAMPLE_RATE
        val first = analysis.firstBeat + interval * ceil((from - analysis.firstBeat) / interval)
        return generateSequence(first) { it + interval }
            .takeWhile { it <= until }
            .map { it - from }
            .toList()
            .toDoubleArray()
    }

    /** The subset of [times] inside the decoded region, rebased onto it. */
    private fun rebased(times: List<Double>, from: Double, frames: Int): DoubleArray {
        val until = from + frames / TransitionRenderer.SAMPLE_RATE
        return times.filter { it in from..until }.map { it - from }.toDoubleArray()
    }

    /** Decodes stereo either side of [anchor], returning the audio and where it starts. */
    private fun decodeAround(uri: Uri, anchor: Double, margin: Double): Pair<AudioDecoder.StereoPcm, Double>? {
        val from = max(0.0, anchor - margin)
        val source = cache.mediaDataSource(uri) ?: return null
        val decoded = source.use { AudioDecoder.decodeRegionStereo(it, from, anchor + margin) } ?: return null
        val (pcm, actualStart) = decoded
        val resampledLeft = MelSpectrogram.resample(pcm.left, pcm.sampleRate, TransitionRenderer.SAMPLE_RATE)
            ?: return null
        val resampledRight = MelSpectrogram.resample(pcm.right, pcm.sampleRate, TransitionRenderer.SAMPLE_RATE)
            ?: return null
        return AudioDecoder.StereoPcm(resampledLeft, resampledRight, TransitionRenderer.SAMPLE_RATE) to actualStart
    }

    /**
     * Vocal presence across the decoded slice, as one value per control point.
     *
     * The sweep alone follows the fade curve rather than the music: it costs the outgoing track the
     * same spectrum whether it is singing or playing an instrumental outro. This makes the depth
     * follow what is actually there, so a track is filtered out of the way smoothly when fading out
     * during the conclusion of a vocal.
     *
     * It spans the slice rather than the overlap because the overlap is what the render call
     * decides; the engine crops this curve to whatever region it settles on.
     */
    private fun duckCurve(analysis: TrackAnalysis, start: Double, end: Double): FloatArray? {
        val mask = analysis.vocalActivityMask
        val curve = analysis.energyCurve
        if (mask.isEmpty() || mask.size != curve.size || end <= start) return null
        val raw = FloatArray(DUCK_POINTS) { index ->
            val time = start + (end - start) * index / (DUCK_POINTS - 1.0)
            val nearest = curve.indices.minByOrNull { abs(curve[it].time - time) } ?: return@FloatArray 1f
            val value = mask[nearest].toFloat()
            if (value.isFinite()) value.coerceIn(0f, 1f) else 1f
        }
        // If all points are unmeasured/neutral (0.5), leave the sweep at full flat depth.
        if (raw.all { abs(it - 0.5f) < 0.05f }) return null

        // Apply smooth sustain across the falling edge of vocal presence so fading vocals
        // are tucked under the incoming track without sudden filter steps.
        val shaped = FloatArray(DUCK_POINTS)
        var maxPresence = 0f
        for (i in 0 until DUCK_POINTS) {
            maxPresence = maxOf(maxPresence * 0.95f, raw[i])
            shaped[i] = maxPresence.coerceIn(0f, 1f)
        }
        return shaped
    }

    private fun nearestDownbeat(analysis: TrackAnalysis, target: Double): Double? =
        analysis.downbeats.minByOrNull { abs(it - target) }?.takeIf { abs(it - target) < 2.0 }

    /**
     * Drops renders for pairs that are no longer next, so the cache directory cannot grow.
     *
     * The two verdict sets are pruned alongside it. They are much smaller, but they are also the
     * memory of *why* a pair produced nothing, and a pair that has left the queue and come back is
     * entitled to be judged again -- its analysis may have finished, or improved, in between.
     */
    fun retainOnly(keys: Set<String>) {
        for (key in ready.keys.toList()) {
            if (key in keys) continue
            ready.remove(key)?.file?.delete()
        }
        declined.retainAll(keys)
        explained.retainAll(keys)
    }

    fun key(outgoing: Track, incoming: Track): String = "${outgoing.id}-${incoming.id}"

    private fun directory(): File = File(context.cacheDir, "transitions").apply { mkdirs() }

    fun release() {
        executor.shutdownNow()
        ready.values.forEach { runCatching { it.file.delete() } }
        ready.clear()
        runCatching { directory().deleteRecursively() }
    }

    private companion object {
        const val TAG = "OrchardTransitionPrep"

        /** Extra audio either side of the anchor, so the stretcher has room to work into. */
        const val MARGIN_SECONDS = 6.0

        /**
         * Control points spanning the decoded slice; the engine interpolates between them and
         * keeps detail in proportion when it crops.
         *
         * Enough of them that a point is worth roughly a tenth of a second across a typical
         * slice, which is what the sustain below is shaped in terms of. Sixty-four covered an
         * overlap at that resolution; a slice is two or three times longer.
         */
        const val DUCK_POINTS = 192
    }
}
