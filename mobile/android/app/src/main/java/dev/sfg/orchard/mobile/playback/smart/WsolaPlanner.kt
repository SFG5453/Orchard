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

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Plans a beat-matched transition: the outgoing track fades out through the incoming track's
 * intro and the first two bars after its analyzed drop.
 *
 * The intro is the runway. It is the part of a track written to have something else over it, so it
 * is the part the outgoing track fades across. The arrangement is then allowed to arrive before
 * the outgoing track is completely gone: that overlap is where the filter ride and bass handoff do
 * real mixing rather than merely waiting for a clean boundary.
 *
 * This began as a port of the desktop app's `src/audio/crossfade/wsolaPlanner.js`. The mobile
 * experiment deliberately chooses earlier outgoing material and later incoming material while
 * continuing to feed the same native renderer (`native/transition/transition_render.cpp`, compiled
 * into this app by `cpp/CMakeLists.txt`). Keep renderer-facing invariants aligned, but do not copy
 * these anchor offsets to desktop until the experiment has proved itself.
 *
 * Planning is pure and cheap so it can run on every playback tick. The heavy work -- decoding PCM
 * and rendering the overlap natively -- belongs to [TransitionPreparer], which calls this first to
 * learn whether a pairing is even worth preparing.
 */

// The fade is bounded in beats because overlap length is musical: bounding it in seconds makes a
// faster track get a longer mix, which is backwards. Four bars is the ceiling and one bar the
// floor, the latter for tracks whose intro cannot cover more.
private const val MIN_FADE_BEATS = 4
private const val MAX_FADE_BEATS = 16

// A ceiling on the whole overlap regardless of how long the incoming intro is.
private const val MAX_OVERLAP_SECONDS = 16.0

// Apple AutoMix's CN TOWER -> Whisper My Name reference aligns about 3:50 on the outgoing track
// with 0:12 on the incoming one. The unshifted drop-to-content-end plan aligns roughly 3:54 with
// 0:08: two bars late on one deck and two bars early on the other. Moving each deck by the same
// musical amount preserves the beat grid and overlap length while putting the incoming arrangement
// inside the blend instead of making it the finish line. Applied only to a content-end exit on the
// outgoing side; a real structural/energy exit has already supplied the earlier anchor.
internal const val ARRANGEMENT_OVERLAP_BEATS = 8

// One continuous equal-power fade across the whole overlap; see `handoff` and `bed` in
// native/transition/transition_render.h, where 0.5/0.5 is documented as the plain symmetric
// crossfade.
//
// The overlap used to be a bed plus a tail: the incoming intro rose to -8 dB while the outgoing
// gave up 0.7 dB, and the outgoing then did its entire audible fade *after* the drop. Two things
// were wrong with that. For the whole bed the outgoing sounded untouched, so the mix seemed to wait
// for it to finish and then rush; and the fade landed on top of the incoming track's full
// arrangement, which is two records at once rather than one becoming the other. Fading continuously
// through the intro instead means the outgoing is already gone when the drop lands.
const val HANDOFF_FRACTION = 0.5
const val BED_POSITION = 0.5

// The low end still hands over late -- see `bass_swap` in the renderer -- but it is now a fraction
// of the continuous fade rather than a separate tail that starts at the drop.
private const val BASS_SWAP_FRACTION = 0.4

// ...and it is capped in absolute seconds as well, so a long overlap does not scale the hold up
// with it and leave the outgoing bass sitting under a track that has already taken over.
private const val BASS_SWAP_MAX_SECONDS = 6.0

/**
 * The outgoing track rides a low-pass down across the fade -- the filter move a DJ makes on the
 * channel that is leaving. The bass swap keeps the low end exclusive, but above it the equal-power
 * fade alone holds both full arrangements at -3 dB each through the middle of the overlap, and two
 * beat-aligned mixes are correlated, so they sum hot exactly where their spectra collide. Full
 * depth: by the end the outgoing track is down to the bass band it is about to hand over anyway,
 * and the fade has it at silence there regardless, so stopping the sweep short only leaves top end
 * in the mix during the part that actually needs clearing. See `filter_sweep` in the renderer.
 */
const val FILTER_SWEEP = 1.0

// The outgoing track must have at least this much audio before the overlap, and the incoming at
// least this much after it, so a transition never lands immediately after a track starts or runs
// into an unresolved tail.
private const val MIN_CLEARANCE_SECONDS = 5.0

private fun bassSwapFractionFor(overlapSeconds: Double): Double {
    if (overlapSeconds <= 0) return BASS_SWAP_FRACTION
    return min(BASS_SWAP_FRACTION, BASS_SWAP_MAX_SECONDS / overlapSeconds)
}

private fun nearestAtOrBefore(values: List<Double>, target: Double): Double? =
    values.filter { it.isFinite() && it >= 0 && it <= target }.maxOrNull()

/**
 * The outcome of planning one beat-matched transition.
 *
 * [Refused] is a routing decision, not an error: the caller should fall back to the ordinary
 * crossfade, which degrades further on its own.
 */
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
        val stretchRatio: Double,
        val incomingCueTime: Double,
        val incomingDropTime: Double,
        /** Where the blend finishes on the incoming timeline, after the arrangement has arrived. */
        val incomingHandoffTime: Double,
        val incomingResumeTime: Double,
    ) : WsolaPlanResult
}

/**
 * Where the incoming track takes over: the best-ranked mix-in candidate, snapped to a downbeat so
 * the shared grid starts on a bar. Candidate choice is a ranking problem -- analyzer score,
 * candidate type, downbeat alignment, available run-up and how vocal it is -- not a type lookup.
 * This is the end of the fade, not the point where the incoming track starts making sound; it
 * begins its intro a whole fade earlier, under the departing track.
 */
fun incomingMixInPoint(analysis: TrackAnalysis): Double? {
    val beatSeconds = analysis.beatInterval.orZero().takeIf { it > 0 }
        ?: if (analysis.bpm.orZero() > 0) 60 / analysis.bpm else 0.0
    val tolerance = max(0.5, beatSeconds * 2)
    val target = listOfNotNull(rankMixInCandidates(analysis).firstOrNull()?.time, analysis.mixInTime)
        .firstOrNull { it.isFinite() && it > 0 }
        ?: return null
    return nearestValue(analysis.downbeats, target, tolerance) ?: target
}

/**
 * Where the incoming track first makes sound. The fade starts here at the earliest; anything before
 * it is lead-in silence that would blend as a gap.
 */
fun incomingAudibleStart(analysis: TrackAnalysis): Double = audibleStartOf(analysis)

/**
 * Plans one beat-matched transition between [analysis] and [nextAnalysis].
 *
 * [duration] and [nextDuration] are the players' own durations, which win over the analyzed ones
 * when both are present.
 */
fun planWsolaTransition(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    duration: Double = 0.0,
    nextDuration: Double = 0.0,
): WsolaPlanResult {
    // The confidence gate: beat-matching is the top policy tier, and only a trusted beat grid on
    // both sides may authorize it. A refusal here routes the pairing to the legacy planner, which
    // degrades further on its own.
    val policy = assessTransitionTier(analysis, nextAnalysis)
    if (policy.tier != TransitionTier.BEATMATCHED) {
        return WsolaPlanResult.Refused(policy.reasons.firstOrNull() ?: "policy")
    }

    val outgoingBpm = analysis.bpm.orZero()
    val incomingBpm = alignTempoOctave(outgoingBpm, nextAnalysis.bpm.orZero())
    val stretchRatio = outgoingBpm / incomingBpm

    val outgoingLength = max(duration.orZero(), analysis.duration.orZero())
    val incomingLength = max(nextDuration.orZero(), nextAnalysis.duration.orZero())
    if (outgoingLength <= 0 || incomingLength <= 0) return WsolaPlanResult.Refused("missing-duration")

    val incomingBeatSeconds = 60 / incomingBpm
    val outgoingBeatSeconds = 60 / outgoingBpm

    // The handoff: where the incoming track's arrangement and vocal arrive.
    val incomingDropTime = incomingMixInPoint(nextAnalysis)
    if (incomingDropTime == null || !incomingDropTime.isFinite() || incomingDropTime < 0) {
        return WsolaPlanResult.Refused("incoming-mix-in")
    }

    // Where the overlap ends: the best-ranked mix-out anchor that does not skip more of the
    // outgoing track's music than the policy budget allows. Resolved early so the vocal-activity
    // windows below can be measured against it.
    val contentEnd = analysis.contentEndTime.orZero().takeIf { it != 0.0 } ?: outgoingLength
    val mixOutAnchor = resolveMixOutAnchor(analysis, contentEnd = contentEnd, duration = outgoingLength)
    val unshiftedOverlapEnd = min(outgoingLength, mixOutAnchor.time)
    val outgoingArrangementOverlap =
        if (mixOutAnchor.type == "content_end") {
            min(ARRANGEMENT_OVERLAP_BEATS * outgoingBeatSeconds, MAX_DISCARDED_MUSIC_SECONDS)
        } else {
            0.0
        }
    val overlapEndTarget = max(MIN_CLEARANCE_SECONDS, unshiftedOverlapEnd - outgoingArrangementOverlap)

    // Size the fade from the incoming intro. The mobile anchor shift below moves the resulting
    // window as a unit, preserving its length and phase while carrying it two bars past the drop.
    // Quantize down to whole bars of the shared grid so both ends stay on a downbeat.
    val audibleStart = incomingAudibleStart(nextAnalysis)
    val availableFadeBeats = max(0.0, incomingDropTime - audibleStart) / incomingBeatSeconds
    val cappedByOverlap = floor(floor(MAX_OVERLAP_SECONDS / incomingBeatSeconds) / 4).toInt() * 4
    if (cappedByOverlap < MIN_FADE_BEATS) return WsolaPlanResult.Refused("overlap-too-long")
    var fadeBeats = minOf(
        MAX_FADE_BEATS,
        cappedByOverlap,
        floor(availableFadeBeats / 4).toInt() * 4,
    )
    // A track that starts singing immediately cannot hide a four-bar fade. Rather than refuse the
    // pairing outright -- which is what made this shape fail the first time it was tried -- the fade
    // shortens to whatever the intro covers, down to a one-bar floor.
    if (fadeBeats < MIN_FADE_BEATS) fadeBeats = MIN_FADE_BEATS

    // Both sides singing through the fade is the case this shape exists to avoid -- but a clash over
    // the *whole* candidate window does not mean the whole window is the problem. A vocal that only
    // arrives in the outgoing track's last bar, or is already singing a bar into the incoming drop,
    // used to collapse a 16-beat fade straight to the one-bar floor, because the check ran once
    // against the full window and any overlap anywhere in it failed the whole thing. That is what
    // made transitions too short far more often than the vocals actually required: back off one bar
    // at a time instead, and use the longest window that is genuinely clash-free. Only a clash that
    // survives all the way down to the floor falls back to it, which remains the least-bad option.
    fun clashOver(beats: Int): Boolean {
        val outStart = overlapEndTarget - beats * outgoingBeatSeconds
        val inStart = max(audibleStart, incomingDropTime - beats * incomingBeatSeconds)
        val outVocal = vocalActivityBetween(analysis, outStart, overlapEndTarget)
        val inVocal = vocalActivityBetween(nextAnalysis, inStart, incomingDropTime)

        if (isVocalClash(outVocal, inVocal)) return true

        // When incoming is vocal-free, allow fading out during the end of outgoing vocals (up to
        // 2 bars / 8 beats), but avoid overdoing it across an entire verse/chorus.
        if (beats > 8 && outVocal != null && outVocal >= VOCAL_ACTIVE_THRESHOLD) {
            val deepVocal = vocalActivityBetween(analysis, outStart, overlapEndTarget - 8 * outgoingBeatSeconds)
            if (deepVocal != null && deepVocal >= VOCAL_ACTIVE_THRESHOLD) {
                return true
            }
        }
        return false
    }
    var fadeVocalClash = clashOver(fadeBeats)
    while (fadeVocalClash && fadeBeats > MIN_FADE_BEATS) {
        fadeBeats -= 4
        fadeVocalClash = clashOver(fadeBeats)
    }

    // The one-bar floor above can ask for more intro than the track owns: a track whose first
    // downbeat is a beat and a half after it starts making sound has no four beats to give. Spending
    // them anyway is what breaks the shape -- `beats` is what the renderer mixes, so an overlap
    // longer than the intro finishes *after* the drop, fading the outgoing track across the incoming
    // arrangement, which is precisely what this plan exists to prevent.
    //
    // So the floor yields to the intro. Whole beats rather than whole bars here: this is already the
    // degraded path, and a three-beat fade that lands on the drop is better than a four-beat one
    // that overruns it.
    val coverableBeats = floor(max(0.0, incomingDropTime - audibleStart) / incomingBeatSeconds).toInt()
    val overlapBeats = min(fadeBeats, coverableBeats)
    if (overlapBeats < 1) return WsolaPlanResult.Refused("incoming-no-intro")

    // The same beat count on both grids: the outgoing side is consumed at its own tempo and
    // stretched onto the incoming grid by the renderer.
    val outgoingOverlapSeconds = overlapBeats * outgoingBeatSeconds
    val overlapSeconds = overlapBeats * incomingBeatSeconds

    // Let the incoming arrangement live inside the final two bars of the blend. Moving both cue
    // and handoff preserves the overlap's length and beat phase; only the chosen section of the
    // incoming track changes. This is the deliberate mobile experiment that replaces the old
    // "outgoing gone exactly at the drop" safety rail.
    val requestedIncomingHandoff =
        incomingDropTime + ARRANGEMENT_OVERLAP_BEATS * incomingBeatSeconds
    val maxIncomingHandoff = incomingLength - MIN_CLEARANCE_SECONDS
    // Do not make a short track "fit" by pulling the handoff back before its analyzed drop. That
    // would silently undo the arrangement overlap and can even resume before the intended mix-in.
    if (maxIncomingHandoff < incomingDropTime) return WsolaPlanResult.Refused("incoming-too-short")
    val incomingHandoffTime = min(requestedIncomingHandoff, maxIncomingHandoff)
    val incomingCueTime = incomingHandoffTime - overlapSeconds
    if (incomingCueTime < audibleStart - 0.05) return WsolaPlanResult.Refused("incoming-no-runway")

    val startTarget = overlapEndTarget - outgoingOverlapSeconds
    val transitionStart = nearestAtOrBefore(analysis.downbeats, startTarget) ?: startTarget
    if (transitionStart < MIN_CLEARANCE_SECONDS) return WsolaPlanResult.Refused("outgoing-too-short")
    val transitionEnd = transitionStart + outgoingOverlapSeconds
    if (transitionEnd > outgoingLength + 0.05) return WsolaPlanResult.Refused("outgoing-overlap-overruns")

    val incomingResumeTime = incomingCueTime + overlapSeconds
    if (incomingResumeTime + MIN_CLEARANCE_SECONDS > incomingLength) {
        return WsolaPlanResult.Refused("incoming-too-short")
    }

    return WsolaPlanResult.Planned(
        tier = policy.tier,
        beatConfidence = policy.beatConfidence,
        mixOutType = mixOutAnchor.type,
        vocalClash = fadeVocalClash,
        transitionStart = transitionStart,
        transitionEnd = transitionEnd,
        overlapSeconds = overlapSeconds,
        beats = overlapBeats,
        fadeBeats = overlapBeats,
        handoffFraction = HANDOFF_FRACTION,
        bedPosition = BED_POSITION,
        bassSwapFraction = bassSwapFractionFor(overlapSeconds),
        filterSweep = FILTER_SWEEP,
        outgoingBpm = outgoingBpm,
        incomingBpm = incomingBpm,
        stretchRatio = stretchRatio,
        incomingCueTime = incomingCueTime,
        incomingDropTime = incomingDropTime,
        incomingHandoffTime = incomingHandoffTime,
        incomingResumeTime = incomingResumeTime,
    )
}
