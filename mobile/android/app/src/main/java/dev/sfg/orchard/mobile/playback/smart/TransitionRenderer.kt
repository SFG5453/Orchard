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

/**
 * Renders a beat-matched transition into one finished stereo buffer.
 *
 * This is what makes a blend sound like a mix rather than two records playing at once. A volume
 * ramp cannot do any of it: the outgoing track is time-stretched onto the incoming track's grid,
 * the low end is handed over at a downbeat so two kick drums never occupy it at once, and a filter
 * sweep takes the outgoing track's top away first and its mids last, so it thins out and recedes
 * instead of merely getting quieter.
 *
 * The outgoing track is the one stretched, deliberately: it is discarded when the overlap ends, so
 * any tempo artefact dies with it and the incoming track runs at its native rate for the rest of
 * playback. Stretching the incoming instead would leave it detuned from its own file forever.
 *
 * Refuses rather than fails when the pairing cannot be rendered transparently: absurd tempo, a
 * stretch beyond what stays clean, too little audio to fill the overlap. A refusal means "use the
 * ordinary crossfade", which is what the caller would have done anyway.
 */
object TransitionRenderer {

    val available: Boolean get() = MelSpectrogram.available

    /** The rate the renderer and Rubber Band work at. */
    const val SAMPLE_RATE = 44_100.0

    /** A rendered overlap, ready to be scheduled as a plain audio source. */
    data class Rendered(
        val left: FloatArray,
        val right: FloatArray,
        /** What the outgoing track was stretched by; 1.0 means the tempi already matched. */
        val stretchRatio: Double,
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
     * One source of the overlap: planar stereo at [SAMPLE_RATE], plus the beat the mix aligns on.
     *
     * [anchorSeconds] is measured from the start of the supplied buffer, not from the start of the
     * track; the caller has already cropped, and both tracks are positioned so their anchors
     * coincide.
     */
    data class Source(
        val left: FloatArray,
        val right: FloatArray,
        val anchorSeconds: Double,
        val bpm: Double,
    )

    /**
     * @param beats length of the overlap in beats, which is how a mix is actually measured; the
     *   planner has already decided this.
     * @param handoff fraction of the overlap where the two tracks cross at equal power. Everything
     *   before it is the incoming track's intro playing underneath: a fader ride, not the first
     *   half of a crossfade.
     * @param bed how far along the equal-power curve that pre-roll travels.
     * @param bassSwap fraction where the low end changes hands, past the crossover by design:
     *   handing bass over at the same instant the fades cross makes the incoming track arrive early.
     * @param filterSweep how far the outgoing low-pass closes by the end, 0 for none.
     * @param vocalDuck optional per-instant vocal-presence multiplier on the sweep, one value per
     *   equally-spaced control point spanning the overlap. Empty leaves the sweep at flat depth.
     */
    fun render(
        outgoing: Source,
        incoming: Source,
        beats: Double,
        handoff: Double = 0.5,
        bed: Double = 0.5,
        bassSwap: Double = 0.7,
        filterSweep: Double = 0.0,
        vocalDuck: FloatArray? = null,
    ): Rendered? {
        if (!available) return null
        val packed = runCatching {
            nativeRender(
                outgoing.left, outgoing.right, outgoing.anchorSeconds, outgoing.bpm,
                incoming.left, incoming.right, incoming.anchorSeconds, incoming.bpm,
                SAMPLE_RATE, beats, handoff, bed, bassSwap, filterSweep, vocalDuck,
            )
        }.onFailure { Log.w(TAG, "Transition render failed", it) }.getOrNull() ?: return null

        // Empty is the documented refusal, not a fault; the reason is logged natively.
        if (packed.size < 2) return null
        val frames = packed[1].toInt()
        if (frames <= 0 || packed.size < 2 + frames * 2) return null

        return Rendered(
            left = packed.copyOfRange(2, 2 + frames),
            right = packed.copyOfRange(2 + frames, 2 + frames * 2),
            stretchRatio = packed[0].toDouble(),
        )
    }

    private const val TAG = "OrchardTransitionRender"

    @JvmStatic private external fun nativeRender(
        outgoingLeft: FloatArray,
        outgoingRight: FloatArray,
        outgoingAnchor: Double,
        outgoingBpm: Double,
        incomingLeft: FloatArray,
        incomingRight: FloatArray,
        incomingAnchor: Double,
        incomingBpm: Double,
        sampleRate: Double,
        beats: Double,
        handoff: Double,
        bed: Double,
        bassSwap: Double,
        filterSweep: Double,
        vocalDuck: FloatArray?,
    ): FloatArray
}
