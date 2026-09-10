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

import dev.sfg.orchard.mobile.model.Track
import kotlin.math.roundToLong

enum class CrossfadeMode { STANDARD, SMART }

/** How the renderer should execute a planned transition. */
enum class TransitionStyle {
    /** A constant-power fade. The only style the bottom tier permits. */
    EQUAL_POWER,

    /** Album siblings played through: a near-instant handoff, not a mix. */
    GAPLESS,

    /** Beat-aligned blend with a bass swap, for matching or near-matching tempi. */
    DJ_BLEND,

    /** Filtered handoff for tempi too far apart to blend flat. */
    DJ_FILTER,
}

/**
 * The planned transition for one pair of tracks, in outgoing-track timeline seconds.
 *
 * A plan is produced on every tick; [shouldStart] is what says the playhead has actually reached
 * it. [markerVisible] is separate because the UI wants to draw the upcoming transition before it
 * begins. When [blocked] is true nothing should happen at all and [reason] says why.
 */
data class TransitionPlan(
    val shouldStart: Boolean = false,
    val markerVisible: Boolean = false,
    val blocked: Boolean = false,
    val reason: String = "",
    val transitionStart: Double = 0.0,
    val transitionEnd: Double = 0.0,
    val fadeSeconds: Double = 0.0,
    val transitionStyle: TransitionStyle = TransitionStyle.EQUAL_POWER,
    /** Where in the incoming track playback should be cued to when the transition opens. */
    val incomingCueTime: Double = 0.0,
    /** Where the incoming track's arrangement lands, on its own timeline. */
    val incomingHandoffTime: Double = 0.0,
    val incomingPlaybackRate: Double = 1.0,
    val handoffStartSeconds: Double = 0.0,
    val handoffDuration: Double = 0.0,
    val pickupSeconds: Double = 0.0,
    val transitionBeats: Int = 0,
    val bassSwap: Boolean = false,
    /**
     * The shape of the rendered overlap, for the renderer's `handoff`, `bed`, `bass_swap` and
     * `filter_sweep` inputs. Only a beat-matched plan sets these; the defaults are the renderer's
     * own and are never read on a plan that is not rendered. They travel on the plan rather than
     * being constants at the render site because the planner is what decides them -- the bass swap
     * in particular is a function of the overlap's length, so a fixed value hands the low end over
     * at the wrong instant on any overlap but one.
     */
    val handoffFraction: Double = HANDOFF_FRACTION,
    val bedPosition: Double = BED_POSITION,
    val bassSwapFraction: Double = 0.7,
    val filterSweep: Double = 0.0,
    /**
     * The tempi the overlap is built on, which are **not** the analyses' raw BPMs: the incoming one
     * has been folded into the outgoing one's octave. A 63 BPM track mixed against a 126 BPM one is
     * counted at 126, the way a DJ counts it, and that is the grid the renderer lays the overlap on.
     *
     * Handing the renderer the raw pair instead is not a near-miss, it is a refusal: it computes
     * `outgoing.bpm / incoming.bpm` without aligning octaves and rejects anything beyond
     * the configured transparent stretch window, so an octave-distant pairing decodes both tracks
     * and then throws the work away. Zero when the plan is not beat-matched.
     */
    val outgoingBpm: Double = 0.0,
    val incomingBpm: Double = 0.0,
    /** Why the policy landed where it did, when it declined to be more ambitious. */
    val policyReasons: List<String> = emptyList(),
    /** The complete portable choreography representation. */
    val choreography: TransitionChoreography? = null,
    /** Exact desktop native plan; the live fields above are its attached fallback. */
    val nativePlan: WsolaPlanResult.Planned? = null,
) {
    /** Convenience for the engine, which schedules in milliseconds. */
    val fadeMs: Long get() = (fadeSeconds * 1000).roundToLong()
}


/** Desktop owns musical decisions and scheduling. This only maps its portable result to Kotlin. */
fun planTransition(
    analysis: TrackAnalysis = TrackAnalysis(),
    nextAnalysis: TrackAnalysis = TrackAnalysis(),
    currentTrack: Track? = null,
    nextTrack: Track? = null,
    currentTime: Double = 0.0,
    duration: Double = 0.0,
    fadeSeconds: Double = 6.0,
    minFadeSeconds: Double = 1.0,
    mode: CrossfadeMode = CrossfadeMode.STANDARD,
    albumSequential: Boolean = false,
): TransitionPlan {
    val input = plannerInput(analysis, nextAnalysis, duration, (nextTrack?.durationMs ?: 0) / 1000.0)
        .put("currentTrack", currentTrack?.plannerJson())
        .put("nextTrack", nextTrack?.plannerJson())
        .put("currentTime", currentTime.orZero()).put("fadeSeconds", fadeSeconds.orZero())
        .put("minFadeSeconds", minFadeSeconds.orZero())
        .put("mode", if (mode == CrossfadeMode.SMART) "smart" else "standard")
        .put("albumSequential", albumSequential)
    val result = DesktopTransitionPlanner.invoke("live", input)
    // Eligibility is decided by desktop's live adapter before native preparation can be attempted.
    val native = if (result.has("pairPlan")) {
        planWsolaTransition(analysis, nextAnalysis,
            maxOf(duration.orZero(), (currentTrack?.durationMs ?: 0) / 1000.0),
            (nextTrack?.durationMs ?: 0) / 1000.0) as? WsolaPlanResult.Planned
    } else null
    return TransitionPlan(
        shouldStart = result.optBoolean("shouldStart"), markerVisible = result.optBoolean("markerVisible"),
        blocked = !result.optBoolean("markerVisible"), reason = result.getString("reason"),
        transitionStart = result.number("transitionStart"), transitionEnd = result.number("transitionEnd"),
        fadeSeconds = result.number("fadeSeconds"),
        transitionStyle = when (result.optString("transitionStyle")) {
            "gapless" -> TransitionStyle.GAPLESS
            "dj_filter" -> TransitionStyle.DJ_FILTER
            else -> TransitionStyle.EQUAL_POWER
        },
        incomingCueTime = result.number("incomingCueTime"),
        incomingHandoffTime = result.number("incomingHandoffTime"),
        incomingPlaybackRate = result.number("incomingPlaybackRate", 1.0),
        handoffStartSeconds = result.number("handoffStartSeconds"),
        handoffDuration = result.number("handoffDuration"),
        pickupSeconds = result.number("pickupSeconds"),
        transitionBeats = result.optInt("transitionBeats"), bassSwap = result.optBoolean("bassSwap"),
        policyReasons = result.strings("policyReasons"),
        choreography = result.optJSONObject("choreography")?.choreography(), nativePlan = native,
    )
}

internal data class SmartPairPreview(val tier: TransitionTier, val wsolaPlan: WsolaPlanResult.Planned? = null)

internal fun previewSmartTransitionPair(
    analysis: TrackAnalysis, nextAnalysis: TrackAnalysis, duration: Double = 0.0, nextDuration: Double = 0.0,
): SmartPairPreview {
    val native = planWsolaTransition(analysis, nextAnalysis, duration, nextDuration)
    if (native is WsolaPlanResult.Planned) return SmartPairPreview(TransitionTier.BEATMATCHED, native)
    val result = DesktopTransitionPlanner.invoke("native", plannerInput(analysis, nextAnalysis, duration, nextDuration))
    return SmartPairPreview(if (result.getJSONObject("pairPlan").getString("transitionClass") == "simple_crossfade")
        TransitionTier.DJ_ASSISTED else TransitionTier.PLAIN_CROSSFADE)
}
