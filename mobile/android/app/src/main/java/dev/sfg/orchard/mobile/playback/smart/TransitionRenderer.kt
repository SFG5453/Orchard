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

import android.util.Log
import dev.sfg.orchard.earmark.RegionConstraint
import dev.sfg.orchard.earmark.SelectedTransition
import dev.sfg.orchard.earmark.TransitionOptions
import dev.sfg.orchard.earmark.TransitionSource
import dev.sfg.orchard.earmark.renderPlannedTransition
import dev.sfg.orchard.earmark.renderTransition
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Renders a beat-matched transition into one finished stereo buffer, using earmark -- the same
 * engine the desktop app runs.
 *
 * This is what makes a blend sound like a mix rather than two records playing at once. A volume
 * ramp cannot do any of it: the outgoing track is time-stretched onto the incoming track's grid,
 * the low end is handed over at a downbeat so two kick drums never occupy it at once, and a filter
 * sweep takes the outgoing track's top away first and its mids last, so it thins out and recedes
 * instead of merely getting quieter.
 *
 * Unlike the renderer this replaced, the engine also *chooses*: given the anchors below it
 * generates beat-aligned candidates inside them, scores them, and picks both the overlap length
 * and the strategy. So the timings that come back are the ones that were used, and are not
 * necessarily the ones that were asked for -- read them off [Rendered], never re-derive them.
 *
 * Refuses rather than fails when the pairing cannot be rendered transparently: absurd tempo, a
 * stretch beyond what stays clean, too little audio to fill the overlap, or an anchor window with
 * no beat-aligned transition in it. A refusal means "use the ordinary crossfade", which is what
 * the caller would have done anyway.
 */
object TransitionRenderer {

    /**
     * True when the engine's library loaded. Transitions are optional, so this is a fact, not a
     * fault. Loading it here rather than leaving it to JNA gets the cost off the first render's
     * deadline, and reports a missing ABI slice as a log line instead of a crash mid-mix.
     */
    val available: Boolean by lazy {
        runCatching { System.loadLibrary("orchard_earmark") }
            .onFailure { Log.w(TAG, "Transition engine unavailable", it) }
            .isSuccess
    }

    /** The rate the renderer works at. */
    const val SAMPLE_RATE = 44_100.0

    /**
     * How far either side of a cue the engine may place the transition.
     *
     * Not slop: every reachable transition end sits a whole number of bars from a downbeat, so a
     * window narrower than that lattice legitimately contains nothing and the engine refuses
     * rather than drifting to the nearest fit. At 122 BPM the lattice is 1.97 s, so a window of
     * roughly half a bar is the smallest that can be relied on to catch anything at all.
     */
    private const val ANCHOR_TOLERANCE_SECONDS = 1.0

    /** One source of the overlap: planar stereo at [SAMPLE_RATE], plus its beat grid. */
    data class Source(
        val left: FloatArray,
        val right: FloatArray,
        val bpm: Double,
        /** Beat times in seconds from the start of [left], not from the start of the track. */
        val beats: DoubleArray,
        val downbeats: DoubleArray,
    )

    /**
     * Where a transition may begin and end on one track, in seconds from the start of the supplied
     * buffer. Either bound may be absent, which leaves that end to the engine.
     */
    data class Anchor(val startSeconds: Double? = null, val endSeconds: Double? = null)

    /** Caller-selected plan specifying the exact parameters to render. */
    data class SelectedPlan(
        val outgoingStart: Double,
        val incomingStart: Double,
        val duration: Double,
        val beats: Int,
        val outgoingBpm: Double,
        val incomingBpm: Double,
        val targetBpm: Double,
        val outgoingTempoRatio: Double,
        val incomingTempoRatio: Double,
        val strategy: String,
        val handoffFraction: Double? = null,
        val bedPosition: Double? = null,
        val bassSwapFraction: Double? = null,
        val filterSweep: Double? = null,
    )

    /** A rendered overlap, ready to be scheduled as a plain audio source. */
    data class Rendered(
        val left: FloatArray,
        val right: FloatArray,
        /** What the outgoing track was stretched by; 1.0 means the tempi already matched. */
        val stretchRatio: Double,
        val incomingStretchRatio: Double,
        /** Where the engine actually placed the transition, on each source's supplied buffer. */
        val outgoingStart: Double,
        val incomingStart: Double,
        /** Where each track had reached when the overlap ended, on the same timelines. */
        val outgoingResume: Double,
        val incomingResume: Double,
        val beats: Int,
        /** The strategy the engine chose: a bass swap and a filtered blend are different mixes. */
        val strategy: String,
    ) {
        val frames: Int get() = left.size
        val durationSeconds: Double get() = frames / SAMPLE_RATE

        override fun equals(other: Any?): Boolean =
            this === other || (other is Rendered && stretchRatio == other.stretchRatio &&
                left.contentEquals(other.left) && right.contentEquals(other.right))

        override fun hashCode(): Int =
            31 * (31 * left.contentHashCode() + right.contentHashCode()) + stretchRatio.hashCode()
    }

    /**
     * Renders an exact caller-chosen transition plan without candidate generation or heuristic search.
     */
    fun renderPlanned(
        outgoing: Source,
        incoming: Source,
        plan: SelectedPlan,
        vocalDuck: FloatArray? = null,
    ): Rendered? {
        if (!available) return null

        val result = runCatching {
            renderPlannedTransition(
                outgoing = outgoing.toFfi(),
                incoming = incoming.toFfi(),
                plan = SelectedTransition(
                    outgoingStart = plan.outgoingStart,
                    incomingStart = plan.incomingStart,
                    duration = plan.duration,
                    beats = plan.beats.toUInt(),
                    outgoingBpm = plan.outgoingBpm,
                    incomingBpm = plan.incomingBpm,
                    targetBpm = plan.targetBpm,
                    outgoingTempoRatio = plan.outgoingTempoRatio,
                    incomingTempoRatio = plan.incomingTempoRatio,
                    outgoingPitchSemitones = null,
                    incomingPitchSemitones = null,
                    strategy = plan.strategy,
                    handoffFraction = plan.handoffFraction,
                    bedPosition = plan.bedPosition,
                    bassSwapFraction = plan.bassSwapFraction,
                    filterSweep = plan.filterSweep,
                ),
                duckCurve = vocalDuck?.map { it.toDouble() },
            )
        }.onFailure { Log.w(TAG, "Planned transition render failed", it) }.getOrNull() ?: return null

        if (!result.rendered) {
            Log.d(TAG, "Refused: ${result.rejected.ifEmpty { "unknown" }}")
            return null
        }
        val channels = result.pcm.toChannels(result.channelCount.toInt())
        if (channels.size < 2 || channels[0].isEmpty()) return null

        Log.d(TAG, "Rendered ${result.strategy}: ${result.summary}")
        return Rendered(
            left = channels[0],
            right = channels[1],
            stretchRatio = result.outgoingTempoRatio,
            incomingStretchRatio = result.incomingTempoRatio,
            outgoingStart = result.outgoingStart,
            incomingStart = result.incomingStart,
            outgoingResume = result.outgoingResume,
            incomingResume = result.incomingResume,
            beats = result.beats.toInt(),
            strategy = result.strategy,
        )
    }

    /**
     * @param outgoingAnchor where the outgoing track should leave, and where the mix should end on
     *   it. The end is what pins a mix to a cue: "finish before the outro" is a constraint on the
     *   end, not the start.
     * @param incomingAnchor where the incoming track should be entered.
     * @param vocalDuck optional per-instant vocal-presence multiplier on the outgoing filter ride,
     *   one value per equally spaced control point spanning **the whole outgoing buffer** rather
     *   than the overlap. It has to span the buffer because the overlap is what this call decides;
     *   the engine crops the curve to whatever region it picks. Null leaves the ride at full depth.
     */
    fun render(
        outgoing: Source,
        incoming: Source,
        outgoingAnchor: Anchor,
        incomingAnchor: Anchor,
        vocalDuck: FloatArray? = null,
    ): Rendered? {
        if (!available) return null

        val result = runCatching {
            renderTransition(
                outgoing = outgoing.toFfi(),
                incoming = incoming.toFfi(),
                options = TransitionOptions(
                    outgoing = outgoingAnchor.toFfi(),
                    incoming = incomingAnchor.toFfi(),
                    // Deliberately unconstrained. The engine picks its overlap length from the
                    // phrase structure it measured; a list it does not already allow -- which any
                    // beat count that is not 4, 8, 16 or 32 would be -- admits nothing at all and
                    // turns every transition into a refusal.
                    beatLengths = null,
                    duckCurve = vocalDuck?.map { it.toDouble() },
                    diagnostics = false,
                ),
            )
        }.onFailure { Log.w(TAG, "Transition render failed", it) }.getOrNull() ?: return null

        if (!result.rendered) {
            Log.d(TAG, "Refused: ${result.rejected.ifEmpty { "unknown" }}")
            return null
        }
        val channels = result.pcm.toChannels(result.channelCount.toInt())
        if (channels.size < 2 || channels[0].isEmpty()) return null

        Log.d(TAG, "Rendered ${result.strategy}: ${result.summary}")
        return Rendered(
            left = channels[0],
            right = channels[1],
            stretchRatio = result.outgoingTempoRatio,
            incomingStretchRatio = result.incomingTempoRatio,
            outgoingStart = result.outgoingStart,
            incomingStart = result.incomingStart,
            outgoingResume = result.outgoingResume,
            incomingResume = result.incomingResume,
            beats = result.beats.toInt(),
            strategy = result.strategy,
        )
    }

    private fun Source.toFfi() = TransitionSource(
        pcm = planarBytes(left, right),
        channelCount = 2u,
        sampleRate = SAMPLE_RATE.toInt().toUInt(),
        bpm = bpm,
        beats = beats.toList(),
        downbeats = downbeats.toList(),
    )

    private fun Anchor.toFfi(): RegionConstraint? {
        if (startSeconds == null && endSeconds == null) return null
        return RegionConstraint(
            startEarliest = startSeconds?.minus(ANCHOR_TOLERANCE_SECONDS),
            startLatest = startSeconds?.plus(ANCHOR_TOLERANCE_SECONDS),
            endEarliest = endSeconds?.minus(ANCHOR_TOLERANCE_SECONDS),
            endLatest = endSeconds?.plus(ANCHOR_TOLERANCE_SECONDS),
        )
    }

    /** Planar little-endian float PCM: all of the left channel, then all of the right. */
    private fun planarBytes(left: FloatArray, right: FloatArray): ByteArray {
        val frames = minOf(left.size, right.size)
        val bytes = ByteBuffer.allocate(frames * 2 * Float.SIZE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        // Bulk puts rather than a sample loop: this runs over a million samples per channel, and
        // the whole point of crossing the boundary as bytes was to avoid paying per sample.
        bytes.asFloatBuffer().apply {
            put(left, 0, frames)
            put(right, 0, frames)
        }
        return bytes.array()
    }

    private fun ByteArray.toChannels(count: Int): List<FloatArray> {
        if (count <= 0) return emptyList()
        val frames = size / Float.SIZE_BYTES / count
        val floats = ByteBuffer.wrap(this).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return List(count) { channel ->
            FloatArray(frames).also {
                floats.position(channel * frames)
                floats.get(it, 0, frames)
            }
        }
    }

    private const val TAG = "OrchardTransitionRender"
}
