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
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "orchard-transition-render").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
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
        // Only the top tier earns a render. Anything less has already been judged not to support
        // beat-matching, and stretching against a grid nobody trusts is exactly what the policy
        // ladder exists to prevent.
        if (plan.transitionStyle != TransitionStyle.DJ_BLEND) return
        if (plan.transitionBeats <= 0 || plan.fadeSeconds <= 0) return
        val policy = assessTransitionTier(outgoingAnalysis, incomingAnalysis)
        if (policy.tier != TransitionTier.BEATMATCHED) return

        val key = key(outgoing, incoming)
        if (ready.containsKey(key) || !running.add(key)) return
        if (!cache.isFullyCached(outgoingUri) || !cache.isFullyCached(incomingUri)) {
            running.remove(key)
            return
        }

        executor.execute {
            try {
                ready[key] = render(key, outgoingUri, outgoingAnalysis, incomingUri, incomingAnalysis, plan)
                    ?: return@execute
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
        val inAnchor = nearestDownbeat(incomingAnalysis, plan.incomingHandoffTime)
            ?: plan.incomingCueTime

        val outgoing = decodeAround(outgoingUri, outAnchor, margin) ?: return null
        val incoming = decodeAround(incomingUri, inAnchor, margin) ?: return null

        val rendered = TransitionRenderer.render(
            outgoing = TransitionRenderer.Source(
                left = outgoing.first.left,
                right = outgoing.first.right,
                anchorSeconds = outAnchor - outgoing.second,
                bpm = outgoingAnalysis.bpm,
            ),
            incoming = TransitionRenderer.Source(
                left = incoming.first.left,
                right = incoming.first.right,
                anchorSeconds = inAnchor - incoming.second,
                bpm = incomingAnalysis.bpm,
            ),
            beats = plan.transitionBeats.toDouble(),
            // The mix changes hands where the incoming arrangement lands, which is what the planner
            // put incomingHandoffTime at; before it the incoming intro is a bed under the outgoing.
            handoff = 0.5,
            bassSwap = 0.7,
            // A sweep is what makes this read as a mix rather than two records at once.
            filterSweep = FILTER_SWEEP,
            vocalDuck = duckCurve(outgoingAnalysis, plan.transitionStart, plan.transitionEnd),
        ) ?: return null

        val file = TransitionAudio.writeWav(rendered, File(directory(), "$key.wav")) ?: return null
        Log.d(
            TAG,
            "Rendered ${rendered.durationSeconds}s stretch=${rendered.stretchRatio} " +
                "in ${System.currentTimeMillis() - started}ms",
        )

        return Prepared(
            file = file,
            startSeconds = plan.transitionStart,
            endSeconds = plan.transitionStart + rendered.durationSeconds,
            // The overlap already contains the incoming track up to its own anchor plus the tail of
            // the overlap, so playback resumes past all of it.
            incomingResumeSeconds = inAnchor - incoming.second +
                (rendered.durationSeconds - (outAnchor - outgoing.second)) + incoming.second,
            stretchRatio = rendered.stretchRatio,
        )
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
     * Vocal presence across the overlap, as one value per control point.
     *
     * The sweep alone follows the fade curve rather than the music: it costs the outgoing track the
     * same spectrum whether it is singing or playing an instrumental outro. This makes the depth
     * follow what is actually there, so a track is filtered out of the way only when it has a vocal
     * to collide with.
     */
    private fun duckCurve(analysis: TrackAnalysis, start: Double, end: Double): FloatArray? {
        val mask = analysis.vocalActivityMask
        val curve = analysis.energyCurve
        if (mask.isEmpty() || mask.size != curve.size || end <= start) return null
        return FloatArray(DUCK_POINTS) { index ->
            val time = start + (end - start) * index / (DUCK_POINTS - 1.0)
            val nearest = curve.indices.minByOrNull { abs(curve[it].time - time) } ?: return@FloatArray 1f
            mask[nearest].toFloat().coerceIn(0f, 1f)
        }
    }

    private fun nearestDownbeat(analysis: TrackAnalysis, target: Double): Double? =
        analysis.downbeats.minByOrNull { abs(it - target) }?.takeIf { abs(it - target) < 2.0 }

    /** Drops renders for pairs that are no longer next, so the cache directory cannot grow. */
    fun retainOnly(keys: Set<String>) {
        for (key in ready.keys.toList()) {
            if (key in keys) continue
            ready.remove(key)?.file?.delete()
        }
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

        /** How far the outgoing low-pass closes by the end of the overlap. */
        const val FILTER_SWEEP = 0.85

        /** Control points spanning the overlap; the renderer interpolates between them. */
        const val DUCK_POINTS = 64
    }
}
