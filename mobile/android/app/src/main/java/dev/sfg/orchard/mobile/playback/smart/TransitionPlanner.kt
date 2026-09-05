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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Turns stored analysis into a concrete transition plan for one pair of tracks.
 *
 * Like [assessTransitionTier], nothing here touches PCM; the planner decides *where* a transition
 * happens and *how* ambitious it is, and the renderer decides nothing.
 */

/** Which crossfade behaviour the listener asked for. */
enum class CrossfadeMode { STANDARD, SMART }

/**
 * Four bars. Overlaps are counted in beats because that is what the ear hears; the seconds values
 * are rails for tempi where four bars would be absurd, not the primary control. Eight to sixteen
 * beats is the range the automatic-DJ literature reports for stable dance material, and less for
 * dense pop.
 */
private const val AUTO_TRANSITION_MAX_BEATS = 16.0
private const val AUTO_MIN_SECONDS = 4.0
private const val AUTO_FAST_TRACK_MIN_SECONDS = 6.0
private const val AUTO_TRANSITION_MAX_SECONDS = 12.0
private const val AUTO_FALLBACK_SECONDS = 8.0

/** Below this a track would spend too much of itself transitioning to be worth planning. */
private const val MIN_SMART_DURATION_SECONDS = 45.0

/** Leave enough incoming material after a calibrated handoff to avoid landing in its outro. */
private const val MIN_INCOMING_CLEARANCE_SECONDS = 5.0

private val KEY_INDEX = mapOf(
    "C" to 0, "C♯" to 1, "D♭" to 1, "D" to 2, "D♯" to 3, "E♭" to 3,
    "E" to 4, "F" to 5, "F♯" to 6, "G♭" to 6, "G" to 7, "G♯" to 8,
    "A♭" to 8, "A" to 9, "A♯" to 10, "B♭" to 10, "B" to 11,
)

/** Anything matching this is spoken or already a performance; mixing it is never wanted. */
private val BLOCKED_TEXT = Regex(
    """\b(podcast|episode|audiobook|live|concert|performance)\b""",
    RegexOption.IGNORE_CASE,
)

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
) {
    /** Convenience for the engine, which schedules in milliseconds. */
    val fadeMs: Long get() = (fadeSeconds * 1000).roundToLong()
}

private fun blocked(reason: String, transitionStart: Double = 0.0, transitionEnd: Double = 0.0) =
    TransitionPlan(
        blocked = true,
        reason = reason,
        transitionStart = transitionStart,
        transitionEnd = transitionEnd,
    )

private fun trackDurationSeconds(track: Track?): Double =
    if (track == null || track.durationMs <= 0) 0.0 else track.durationMs / 1000.0

private fun itemText(track: Track?): String =
    if (track == null) "" else listOf(track.title, track.artist, track.album)
        .filter { it.isNotBlank() }
        .joinToString(" ")

/**
 * Gapless is for an album being played through, not for any two songs that happen to share an
 * album. A playlist, a manual queue or a shuffle that lands two album siblings back to back is a
 * mix, and gets mixed; the caller decides which of those it is via `albumSequential` and says so
 * explicitly.
 */
private fun sameAlbum(left: Track?, right: Track?): Boolean {
    if (left == null || right == null) return false
    if (left.albumId.isNotBlank() && left.albumId == right.albumId) return true
    return left.album.isNotBlank() && left.album == right.album && left.artist == right.artist
}

/** Folds [nextBpm] into the same octave as [currentBpm] and returns the ratio between them. */
private fun normalizedTempoRatio(currentBpm: Double, nextBpm: Double): Double {
    if (currentBpm <= 0 || nextBpm <= 0) return 1.0
    var ratio = nextBpm / currentBpm
    while (ratio > 1.5) ratio /= 2
    while (ratio < 0.67) ratio *= 2
    return ratio
}

private fun splitKey(key: String): Pair<Int?, String?> {
    val parts = key.trim().split(' ')
    return KEY_INDEX[parts.firstOrNull()] to parts.getOrNull(1)
}

/** Semitone distance between two keys, plus one for a mode change. Null when either is unparsable. */
private fun keyDistance(left: String, right: String): Int? {
    val (leftIndex, leftMode) = splitKey(left)
    val (rightIndex, rightMode) = splitKey(right)
    if (leftIndex == null || rightIndex == null) return null
    val pitchDistance = min((leftIndex - rightIndex + 12) % 12, (rightIndex - leftIndex + 12) % 12)
    return pitchDistance + if (leftMode != null && rightMode != null && leftMode != rightMode) 1 else 0
}

private fun harmonicallyCompatible(left: String, right: String): Boolean {
    val (leftIndex, leftMode) = splitKey(left)
    val (rightIndex, rightMode) = splitKey(right)
    if (leftIndex == null || rightIndex == null) return false
    val distance = min((leftIndex - rightIndex + 12) % 12, (rightIndex - leftIndex + 12) % 12)
    if (leftMode != null && rightMode != null && leftMode != rightMode) return distance <= 1
    // A fifth is as close as a second here: it is the move every DJ makes.
    return distance <= 2 || distance == 5
}

/** A key the analyzer was not confident about is no key at all. */
private fun trustedKey(analysis: TrackAnalysis): String =
    if (analysis.key.isBlank() || analysis.keyConfidence < 0.25) "" else analysis.key

internal fun supportsHarmonicPhraseSwitch(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
): Boolean = harmonicallyCompatible(trustedKey(analysis), trustedKey(nextAnalysis))

internal data class SmartPairPreview(
    val tier: TransitionTier,
    val wsolaPlan: WsolaPlanResult.Planned? = null,
)

/**
 * Classifies a stored-analysis pair through the same degradation ladder playback will use.
 * Keeping the harmonic gate and operational WSOLA refusal here prevents queue ordering from
 * advertising a phrase switch that [planTransition] would replace with an adaptive overlap.
 */
internal fun previewSmartTransitionPair(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    duration: Double = 0.0,
    nextDuration: Double = 0.0,
): SmartPairPreview {
    val policy = assessTransitionTier(analysis, nextAnalysis)
    if (policy.tier != TransitionTier.BEATMATCHED) return SmartPairPreview(policy.tier)
    if (!supportsHarmonicPhraseSwitch(analysis, nextAnalysis)) {
        return SmartPairPreview(TransitionTier.DJ_ASSISTED)
    }
    val plan = planWsolaTransition(
        analysis = analysis,
        nextAnalysis = nextAnalysis,
        duration = duration,
        nextDuration = nextDuration,
    ) as? WsolaPlanResult.Planned
    return if (plan != null) {
        SmartPairPreview(TransitionTier.BEATMATCHED, plan)
    } else {
        SmartPairPreview(TransitionTier.DJ_ASSISTED)
    }
}

private fun nearestTimedValue(
    values: List<Double>,
    target: Double,
    tolerance: Double = Double.POSITIVE_INFINITY,
    minimum: Double = 0.0,
): Double? = values
    .filter { it.isFinite() && it >= minimum && abs(it - target) <= tolerance }
    .minByOrNull { abs(it - target) }

private fun timedValueNearOrBefore(
    values: List<Double>,
    target: Double,
    tolerance: Double = Double.POSITIVE_INFINITY,
    minimum: Double = 0.0,
): Double? = values
    .filter { it.isFinite() && it >= minimum && it <= target && target - it <= tolerance }
    .maxOrNull()

/**
 * Snaps a transition start onto the outgoing track's grid: a phrase boundary if one is near, a
 * downbeat otherwise, and the raw target when neither is.
 */
private fun alignedTransitionStart(
    analysis: TrackAnalysis,
    target: Double,
    end: Double,
    preferEarlier: Boolean,
    minimum: Double,
): Double {
    val interval = analysis.beatInterval.orZero().takeIf { it > 0 }
        ?: if (analysis.bpm.orZero() > 0) 60 / analysis.bpm else 0.0
    val phraseTolerance = max(1.0, interval * 4)
    val downbeatTolerance = max(0.75, interval * 2)
    val phrase = if (preferEarlier) {
        timedValueNearOrBefore(analysis.phraseBoundaries, target, phraseTolerance, minimum)
    } else {
        nearestTimedValue(analysis.phraseBoundaries, target, phraseTolerance, minimum)
    }
    val downbeat = if (preferEarlier) {
        timedValueNearOrBefore(analysis.downbeats, target, downbeatTolerance, minimum)
    } else {
        nearestTimedValue(analysis.downbeats, target, downbeatTolerance, minimum)
    }
    return clamp(phrase ?: downbeat ?: target, minimum, end)
}

/**
 * Where the incoming track's arrangement arrives: the point the outgoing track should be gone by.
 *
 * Candidate entry points are ranked, not pattern-matched: the analyzer's score plus type, downbeat,
 * run-up and vocal terms decide which one leads. The fallbacks below only run for an analysis that
 * carries no candidate list at all.
 */
internal fun incomingCuePoint(analysis: TrackAnalysis): Double {
    rankMixInCandidates(analysis).firstOrNull()?.let { return it.time }

    val interval = analysis.beatInterval.orZero().takeIf { it > 0 }
        ?: if (analysis.bpm.orZero() > 0) 60 / analysis.bpm else 0.0
    val downbeats = analysis.downbeats

    val analyzedMixIn = analysis.mixInTime
    if (analyzedMixIn.isFinite() && analyzedMixIn > 0) {
        return nearestTimedValue(downbeats, analyzedMixIn, max(0.5, interval * 2)) ?: analyzedMixIn
    }

    val pickup = max(
        0.0,
        analysis.introEndTime.orZero().takeIf { it != 0.0 }
            ?: (analysis.audibleStartTime ?: analysis.pickupTime).orZero().takeIf { it != 0.0 }
            ?: analysis.firstBeat.orZero(),
    )
    val duration = analysis.duration.orZero().takeIf { it != 0.0 } ?: 300.0
    if (pickup > 0 && pickup < duration - 10) {
        downbeats.firstOrNull { it >= pickup }?.let { return it }
    }
    val phrases = analysis.phraseBoundaries
    if (phrases.size > 1 && phrases[1] > 4) return phrases[1]
    if (downbeats.size >= 8) return downbeats[min(8, downbeats.size - 1)].orZero()
    return pickup
}

/** Where the incoming track first makes sound, so the fade is not cued into its lead-in silence. */
private fun incomingStartPoint(analysis: TrackAnalysis): Double =
    listOfNotNull(analysis.audibleStartTime, analysis.pickupTime, analysis.firstBeat)
        .firstOrNull { it.isFinite() && it >= 0 } ?: 0.0

/**
 * The most ambitious move available: run the incoming track's instrumental intro underneath the
 * outgoing one and close on its drop, so the outgoing track is fully gone by the time the incoming
 * vocals arrive.
 *
 * The shape of that mix is [planWsolaTransition]'s to decide -- it is the port of the desktop
 * planner, and the two have to agree or the same pairing blends differently on each platform. This
 * function is only the adapter between that plan and the [TransitionPlan] the engine schedules
 * from. A refusal is a routing decision, not an error: the caller falls back to the adaptive
 * overlap below, which degrades further on its own.
 */
private fun phraseSwitch(
    analysis: TrackAnalysis,
    nextAnalysis: TrackAnalysis,
    length: Double,
    nextLength: Double,
): TransitionPlan? {
    val planned = previewSmartTransitionPair(
        analysis = analysis,
        nextAnalysis = nextAnalysis,
        duration = length,
        nextDuration = nextLength,
    ).wsolaPlan ?: return null

    val overlap = planned.transitionEnd - planned.transitionStart
    return TransitionPlan(
        markerVisible = true,
        transitionStart = planned.transitionStart,
        transitionEnd = planned.transitionEnd,
        // The outgoing track's own share of the overlap. The planner's `overlapSeconds` is the same
        // count of beats measured on the *incoming* grid, which is what the renderer stretches onto;
        // scheduling the ramp from it would run the fade at the wrong tempo's length.
        fadeSeconds = overlap,
        // The fade spans the whole overlap, including the mobile experiment's two bars of incoming
        // arrangement, rather than starting once the drop has landed.
        handoffStartSeconds = 0.0,
        handoffDuration = overlap,
        incomingCueTime = planned.incomingCueTime,
        incomingHandoffTime = planned.incomingHandoffTime,
        // Only the volume-ramp fallback uses this; a rendered overlap follows the incoming grid and
        // stretches the outgoing source instead. The live fallback cannot change the already-playing
        // outgoing deck without moving its timeline, so it performs the inverse operation and nudges
        // the incoming deck onto the outgoing grid.
        incomingPlaybackRate = ((1 / planned.stretchRatio) * 10000).roundToInt() / 10000.0,
        pickupSeconds = incomingAudibleStart(nextAnalysis),
        transitionBeats = planned.beats,
        bassSwap = true,
        handoffFraction = planned.handoffFraction,
        bedPosition = planned.bedPosition,
        bassSwapFraction = planned.bassSwapFraction,
        filterSweep = planned.filterSweep,
        outgoingBpm = planned.outgoingBpm,
        incomingBpm = planned.incomingBpm,
        transitionStyle = TransitionStyle.DJ_BLEND,
        choreography = planned.choreography,
    )
}

private data class Overlap(
    val overlap: Double,
    val transitionBeats: Int,
    val incomingPlaybackRate: Double,
)

/** How long a mix should run when the tracks are related but not phrase-switchable. */
private fun adaptiveOverlap(analysis: TrackAnalysis, nextAnalysis: TrackAnalysis): Overlap {
    val currentBpm = analysis.bpm.orZero()
    val nextBpm = nextAnalysis.bpm.orZero()
    if (currentBpm <= 0 || nextBpm <= 0) {
        return Overlap(AUTO_FALLBACK_SECONDS, 0, 1.0)
    }

    val ratio = normalizedTempoRatio(currentBpm, nextBpm)
    val distance = keyDistance(trustedKey(analysis), trustedKey(nextAnalysis))
    val vocalConflict = analysis.vocalProbability >= 0.62 && nextAnalysis.vocalProbability >= 0.62

    val sameTempo = abs(1 - ratio) <= 0.05
    if (!sameTempo) {
        val nonBeatmatchedDuration = if (vocalConflict) 2.5 else 3.5
        return Overlap(
            overlap = clamp(nonBeatmatchedDuration, 2.0, 4.0),
            transitionBeats = 0,
            incomingPlaybackRate = 1.0,
        )
    }

    // A longer mix is how distance gets absorbed, unless both tracks are singing, where a longer
    // mix just means two vocals over each other for longer.
    val transitionBeats =
        if (!vocalConflict && (abs(1 - ratio) > 0.07 || (distance != null && distance > 4))) 16 else 8
    val beatSeconds = 60 / currentBpm
    // Eight beats can be under four seconds on faster material. Keep a little more real-time
    // overlap there so smart mixes do not turn into abrupt swaps.
    val minimumOverlap = if (currentBpm >= 140) AUTO_FAST_TRACK_MIN_SECONDS else AUTO_MIN_SECONDS

    return Overlap(
        overlap = clamp(transitionBeats * beatSeconds, minimumOverlap, AUTO_TRANSITION_MAX_SECONDS),
        transitionBeats = transitionBeats,
        incomingPlaybackRate = if (ratio in 0.9..1.1) {
            (clamp(1 / ratio, 0.9, 1.1) * 10000).roundToInt() / 10000.0
        } else {
            1.0
        },
    )
}

private fun standardTransition(
    length: Double,
    playbackTime: Double,
    fadeSeconds: Double,
    minFadeSeconds: Double,
    reason: String = "standard",
): TransitionPlan {
    val fade = clamp(fadeSeconds, minFadeSeconds, 12.0)
    val transitionStart = max(0.0, length - fade)
    val started = playbackTime >= transitionStart
    return TransitionPlan(
        shouldStart = started,
        markerVisible = true,
        transitionStart = transitionStart,
        transitionEnd = length,
        fadeSeconds = fade,
        transitionStyle = TransitionStyle.EQUAL_POWER,
        reason = if (started) reason else "before-$reason-window",
    )
}

/** A stale analysis paired with the wrong track is worse than no analysis at all. */
private fun analysisReadyForTrack(analysis: TrackAnalysis, track: Track?): Boolean {
    if (analysis.status.isBlank()) return true
    if (analysis.status != TrackAnalysis.STATUS_READY) return false
    return analysis.trackId.isBlank() || track?.id.isNullOrBlank() || analysis.trackId == track.id
}

/**
 * Plans the transition out of [currentTrack] and into [nextTrack].
 *
 * Called on every playback tick; the returned plan describes the transition whether or not it has
 * started yet, so the UI can draw it ahead of time.
 *
 * @param albumSequential true only when this is an album genuinely being played through in order,
 *   which is the sole case that earns a gapless handoff instead of a mix.
 * @param currentTime the outgoing track's playhead, in seconds.
 */
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
    val length = max(duration.orZero(), trackDurationSeconds(currentTrack))
    val playbackTime = max(0.0, currentTime.orZero())
    if (length <= 0) return blocked("no-duration")

    val standardFade = clamp(fadeSeconds, minFadeSeconds, 12.0)
    if (mode != CrossfadeMode.SMART) {
        return standardTransition(length, playbackTime, standardFade, minFadeSeconds)
    }

    if (length < MIN_SMART_DURATION_SECONDS) {
        return blocked("short-duration-guard", transitionStart = length, transitionEnd = length)
    }

    val analyzedContentEnd = analysis.contentEndTime.orZero().takeIf { it != 0.0 } ?: length
    val finalMixAnchor = if (analyzedContentEnd > 0 && analyzedContentEnd <= length) {
        analyzedContentEnd
    } else {
        length
    }
    // Where the transition ends. An interior anchor has to earn its place: it is only taken when
    // what it skips is silence or a short tail, never when a minute of music is still to come.
    val mixOutAnchor = resolveMixOutAnchor(analysis, contentEnd = finalMixAnchor, duration = length)
    val hasInteriorMixOut = mixOutAnchor.time < finalMixAnchor - 1

    if (albumSequential && sameAlbum(currentTrack, nextTrack) && !hasInteriorMixOut) {
        val transitionStart = max(0.0, length - 0.45)
        val started = playbackTime >= transitionStart
        return TransitionPlan(
            shouldStart = started,
            markerVisible = true,
            transitionStart = transitionStart,
            transitionEnd = length,
            fadeSeconds = 0.12,
            transitionStyle = TransitionStyle.GAPLESS,
            reason = if (started) "same-album-gapless" else "before-gapless-window",
        )
    }

    if (BLOCKED_TEXT.containsMatchIn("${itemText(currentTrack)} ${itemText(nextTrack)}")) {
        return blocked("blocked-speech-or-live")
    }

    if (!analysisReadyForTrack(analysis, currentTrack) ||
        !analysisReadyForTrack(nextAnalysis, nextTrack)
    ) {
        return standardTransition(
            length,
            playbackTime,
            standardFade,
            minFadeSeconds,
            "smart-analysis-fallback",
        )
    }

    val preferredMixAnchor = min(length, mixOutAnchor.time)
    // Having already played past the interior anchor, honouring it would mean jumping backwards.
    val mixAnchor =
        if (playbackTime >= preferredMixAnchor - 0.05 && preferredMixAnchor < finalMixAnchor - 1) {
            finalMixAnchor
        } else {
            preferredMixAnchor
        }

    // The degradation ladder's bottom rung: when tempo is missing or neither beat grid is trusted,
    // no beat-quantized DJ move is allowed.
    val policy = assessTransitionTier(analysis, nextAnalysis)
    if (policy.tier == TransitionTier.PLAIN_CROSSFADE) {
        val transitionStart = max(0.0, mixAnchor - standardFade)
        val started = playbackTime >= transitionStart
        return TransitionPlan(
            shouldStart = started,
            markerVisible = true,
            transitionStart = transitionStart,
            transitionEnd = mixAnchor,
            fadeSeconds = mixAnchor - transitionStart,
            transitionStyle = TransitionStyle.EQUAL_POWER,
            incomingCueTime = incomingStartPoint(nextAnalysis),
            policyReasons = policy.reasons,
            reason = if (started) "smart-plain-crossfade" else "before-plain-crossfade-window",
        )
    }

    val nextLength = max(nextAnalysis.duration.orZero(), trackDurationSeconds(nextTrack))

    // The shared planner resolves its own mix-out anchor from the full track, so it is given the
    // real duration rather than `mixAnchor`. A window the playhead has already run past is the one
    // case that correction existed for, and it falls through to the adaptive overlap below.
    phraseSwitch(analysis, nextAnalysis, length, nextLength)
        ?.takeIf { playbackTime < it.transitionEnd }
        ?.let { plan ->
            val started = playbackTime >= plan.transitionStart
            return plan.copy(
                shouldStart = started,
                policyReasons = policy.reasons,
                reason = if (started) "smart-phrase-switch" else "before-phrase-switch",
            )
        }

    val (overlap, transitionBeats, incomingPlaybackRate) = adaptiveOverlap(analysis, nextAnalysis)
    val currentBpm = analysis.bpm.orZero()
    val nextBpm = nextAnalysis.bpm.orZero()
    val handoffBpm = if (currentBpm > 0) currentBpm else nextBpm
    val sameBeatBlend = currentBpm > 0 && nextBpm > 0 &&
        abs(1 - normalizedTempoRatio(currentBpm, nextBpm)) <= 0.05 &&
        (analysis.beatConfidence.orZero() >= 0.2 || nextAnalysis.beatConfidence.orZero() >= 0.2)
    // The fully beat-matched phrase switch is intentionally conservative and can decline a pair
    // whose key estimate is weak. Its DJ_BLEND fallback must retain the same mobile calibration,
    // otherwise the exact tracks used to tune it silently take the old drop-to-content-end route.
    val outgoingArrangementOverlap =
        if (sameBeatBlend && mixOutAnchor.type == "content_end") {
            min(ARRANGEMENT_OVERLAP_BEATS * 60 / currentBpm, MAX_DISCARDED_MUSIC_SECONDS)
        } else {
            0.0
        }
    val mixEnd = max(0.0, mixAnchor - outgoingArrangementOverlap)
    // An overlap is a musical length, so it is bounded in beats first. Bounding it in seconds meant
    // a faster track got a *longer* mix: at 140 BPM the sixteen-second cap ran to thirty-seven
    // beats, over nine bars, which stops sounding like a transition and starts sounding like two
    // records at once. The seconds cap survives only as a rail for tempi where four bars is
    // absurdly long.
    val maximumOverlap = minOf(
        if (handoffBpm > 0) (AUTO_TRANSITION_MAX_BEATS * 60) / handoffBpm else AUTO_TRANSITION_MAX_SECONDS,
        AUTO_TRANSITION_MAX_SECONDS,
        mixEnd * 0.4,
        if (nextLength > 0) nextLength * 0.4 else AUTO_TRANSITION_MAX_SECONDS,
    )
    val handoffBeats = if (sameBeatBlend) 8 else 4
    val beatSeconds = if (handoffBpm > 0) 60 / handoffBpm else 0.5
    val handoffSeconds = if (handoffBpm > 0) {
        clamp((handoffBeats * 60) / handoffBpm, 2.0, if (sameBeatBlend) 6.0 else 5.0)
    } else {
        4.0
    }
    val analyzedPickup = nextAnalysis.audibleStartTime ?: nextAnalysis.pickupTime
    val pickupSeconds = if (analyzedPickup != null && analyzedPickup.isFinite() && analyzedPickup >= 0) {
        analyzedPickup
    } else {
        0.0
    }
    val incomingDropTime = incomingCuePoint(nextAnalysis)
    val alignedIncomingBpm = alignTempoOctave(currentBpm, nextBpm)
    val requestedIncomingHandoff =
        if (sameBeatBlend && alignedIncomingBpm > 0) {
            incomingDropTime + ARRANGEMENT_OVERLAP_BEATS * 60 / alignedIncomingBpm
        } else {
            incomingDropTime
        }
    val maxIncomingHandoff = nextLength - MIN_INCOMING_CLEARANCE_SECONDS
    // Preserve the analyzed drop when a short incoming track cannot afford the experimental tail.
    // The conservative fallback is the old handoff, never a clamp to a point before the drop.
    val incomingHandoffTime =
        if (maxIncomingHandoff >= incomingDropTime) {
            min(requestedIncomingHandoff, maxIncomingHandoff)
        } else {
            incomingDropTime
        }
    val rawIncomingCueTime = incomingStartPoint(nextAnalysis)
    val analyzedIncomingHandoff = nextAnalysis.mixInTime
    val hasIncomingPreroll = analyzedIncomingHandoff.isFinite() &&
        analyzedIncomingHandoff > rawIncomingCueTime + 0.5
    val incomingCueTime = if (hasIncomingPreroll) rawIncomingCueTime else incomingHandoffTime
    val introPreroll = max(
        0.0,
        (if (hasIncomingPreroll) incomingHandoffTime - incomingCueTime else 0.0) /
            max(0.8, incomingPlaybackRate),
    )

    val finalIncomingCueTime: Double
    val transitionStart: Double

    if (sameBeatBlend && beatSeconds > 0) {
        // AutoMix-style transition for matching or near-matching BPM. The incoming track uses its
        // instrumental intro as runway, then lets two bars of arrangement live inside the blend.
        // incomingHandoffTime decides where that calibrated window closes.
        val introDropTime = incomingHandoffTime / max(0.8, incomingPlaybackRate)
        val totalOverlap = clamp(introDropTime, min(12.0, maximumOverlap), maximumOverlap)
        val targetStart = max(0.0, mixEnd - totalOverlap)
        val earliestTransitionStart = max(0.0, mixEnd - maximumOverlap)
        transitionStart = alignedTransitionStart(
            analysis,
            targetStart,
            mixEnd - 0.05,
            preferEarlier = true,
            minimum = earliestTransitionStart,
        )
        // A short intro cannot cover the whole overlap; cueing at zero shortens the run-up instead
        // of dragging the drop away from where it was analyzed.
        finalIncomingCueTime =
            max(0.0, incomingHandoffTime - (mixEnd - transitionStart) * incomingPlaybackRate)
    } else {
        val maxNonBeatmatched = minOf(4.0, maximumOverlap)
        val desiredOverlap = clamp(overlap, 2.0, maxNonBeatmatched)
        val actualOverlap = clamp(desiredOverlap, minOf(2.0, maxNonBeatmatched), maxNonBeatmatched)
        val targetStart = max(0.0, mixEnd - actualOverlap)
        val earliestTransitionStart = max(0.0, mixEnd - maxNonBeatmatched)
        val alignedStart = alignedTransitionStart(
            analysis,
            targetStart,
            mixEnd - 0.05,
            preferEarlier = false,
            minimum = earliestTransitionStart,
        )
        transitionStart = max(alignedStart, mixEnd - 4.0)
        finalIncomingCueTime = if (hasIncomingPreroll) {
            max(0.0, incomingHandoffTime - (mixEnd - transitionStart) * incomingPlaybackRate)
        } else {
            incomingCueTime
        }
    }

    val alignedOverlap = mixEnd - transitionStart
    val hasBassContent = analysis.lowEnergyCurve.isNotEmpty() || nextAnalysis.lowEnergyCurve.isNotEmpty()
    val started = playbackTime >= transitionStart
    val fallbackChoreography = TransitionChoreography(
        strategy = if (sameBeatBlend) ChoreographyStrategy.STAGED_BLEND else ChoreographyStrategy.FILTERED_HANDOFF,
        outgoing = OutgoingChoreography(
            start = transitionStart,
            end = mixEnd,
            tempoRatio = 1.0,
        ),
        incoming = IncomingChoreography(
            cue = finalIncomingCueTime,
            arrival = incomingHandoffTime,
            resume = incomingHandoffTime + alignedOverlap * incomingPlaybackRate,
            tempoRatio = incomingPlaybackRate,
        ),
        duration = alignedOverlap,
        dominancePoint = 0.5,
        curves = AutomationCurves(
            outgoingGain = listOf(
                AutomationPoint(0.0, 1.0, CurveInterpolation.SMOOTH_STEP),
                AutomationPoint(1.0, 0.0),
            ),
            incomingGain = listOf(
                AutomationPoint(0.0, 0.0, CurveInterpolation.SMOOTH_STEP),
                AutomationPoint(1.0, 1.0),
            ),
            outgoingLowPass = if (sameBeatBlend || hasBassContent) {
                listOf(
                    AutomationPoint(0.0, 20000.0, CurveInterpolation.LOGARITHMIC),
                    AutomationPoint(0.3, 20000.0, CurveInterpolation.LOGARITHMIC),
                    AutomationPoint(1.0, 800.0),
                )
            } else {
                emptyList()
            },
            outgoingBass = listOf(
                AutomationPoint(0.0, 1.0),
                AutomationPoint(0.45, 1.0, CurveInterpolation.EQUAL_POWER_IN),
                AutomationPoint(0.55, 0.0),
                AutomationPoint(1.0, 0.0),
            ),
            incomingBass = listOf(
                AutomationPoint(0.0, 0.0),
                AutomationPoint(0.45, 0.0, CurveInterpolation.EQUAL_POWER_OUT),
                AutomationPoint(0.55, 1.0),
                AutomationPoint(1.0, 1.0),
            ),
        ),
        bassSwapPoint = 0.5,
    )
    return TransitionPlan(
        shouldStart = started,
        markerVisible = true,
        transitionStart = transitionStart,
        transitionEnd = mixEnd,
        fadeSeconds = alignedOverlap,
        // Filtered blends fade across the overlap too, so the departing track is gone by the time
        // the incoming one is running on its own.
        handoffStartSeconds = 0.0,
        handoffDuration = alignedOverlap,
        incomingCueTime = finalIncomingCueTime,
        incomingHandoffTime = incomingHandoffTime,
        incomingPlaybackRate = incomingPlaybackRate,
        pickupSeconds = pickupSeconds,
        transitionBeats = transitionBeats,
        bassSwap = sameBeatBlend || hasBassContent,
        transitionStyle = if (sameBeatBlend) TransitionStyle.DJ_BLEND else TransitionStyle.DJ_FILTER,
        policyReasons = policy.reasons,
        choreography = fallbackChoreography,
        reason = if (started) "smart-duration" else "before-smart-duration",
    )
}
