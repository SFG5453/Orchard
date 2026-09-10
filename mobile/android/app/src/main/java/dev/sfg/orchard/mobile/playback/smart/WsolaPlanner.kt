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

const val HANDOFF_FRACTION = 0.5
const val BED_POSITION = 0.5
const val FILTER_SWEEP = 1.0

sealed interface WsolaPlanResult {
    data class Refused(val reason: String) : WsolaPlanResult

    /** All times are seconds on each track's own media timeline. */
    data class Planned(
        val tier: TransitionTier,
        val beatConfidence: Double,
        val mixOutType: String,
        /** True when the fade was shortened all the way to the floor and both sides still sing. */
        val vocalClash: Boolean,
        val transitionStart: Double,
        val transitionEnd: Double,
        val overlapSeconds: Double,
        val beats: Int,
        /**
         * What the fade actually spends, after the intro has had its say. The pre-clamp target is
         * not reported: nothing downstream can act on beats the incoming track had no room for.
         */
        val fadeBeats: Int,
        val handoffFraction: Double,
        val bedPosition: Double,
        val bassSwapFraction: Double,
        val filterSweep: Double,
        val outgoingBpm: Double,
        val incomingBpm: Double,
        /** Source seconds consumed by the outgoing deck per rendered output second. */
        val stretchRatio: Double,
        val incomingCueTime: Double,
        val incomingDropTime: Double,
        /** Where the blend finishes on the incoming timeline, after the arrangement has arrived. */
        val incomingHandoffTime: Double,
        val incomingResumeTime: Double,
        val choreography: TransitionChoreography? = null,
        val targetBpm: Double = incomingBpm,
        val outgoingTempoRatio: Double = stretchRatio,
        val incomingTempoRatio: Double = 1.0,
        val strategy: String = "beatmatched_crossfade",

    ) : WsolaPlanResult
}


/** The desktop native adapter, without mobile offsets or an additional policy gate. */
fun planWsolaTransition(
    analysis: TrackAnalysis, nextAnalysis: TrackAnalysis, duration: Double = 0.0, nextDuration: Double = 0.0,
): WsolaPlanResult {
    val result = DesktopTransitionPlanner.invoke("native", plannerInput(analysis, nextAnalysis, duration, nextDuration))
    if (!result.getBoolean("ok")) return WsolaPlanResult.Refused(result.getString("reason"))
    return WsolaPlanResult.Planned(
        tier = TransitionTier.BEATMATCHED, beatConfidence = result.getDouble("beatConfidence"),
        mixOutType = result.getString("mixOutType"), vocalClash = result.getBoolean("vocalClash"),
        transitionStart = result.getDouble("transitionStart"), transitionEnd = result.getDouble("transitionEnd"),
        overlapSeconds = result.getDouble("overlapSeconds"), beats = result.getInt("beats"),
        fadeBeats = result.getInt("fadeBeats"), handoffFraction = result.getDouble("handoffFraction"),
        bedPosition = result.getDouble("bedPosition"), bassSwapFraction = result.getDouble("bassSwapFraction"),
        filterSweep = result.getDouble("filterSweep"), outgoingBpm = result.getDouble("outgoingBpm"),
        incomingBpm = result.getDouble("incomingBpm"), stretchRatio = result.getDouble("stretchRatio"),
        incomingCueTime = result.getDouble("incomingCueTime"), incomingDropTime = result.getDouble("incomingDropTime"),
        incomingHandoffTime = result.getDouble("incomingResumeTime"), incomingResumeTime = result.getDouble("incomingResumeTime"),
        choreography = result.getJSONObject("choreography").choreography(), targetBpm = result.getDouble("targetBpm"),
        outgoingTempoRatio = result.getDouble("outgoingTempoRatio"), incomingTempoRatio = result.getDouble("incomingTempoRatio"),
        strategy = result.getString("strategy"),
    )
}

fun incomingAudibleStart(analysis: TrackAnalysis): Double = audibleStartOf(analysis)
