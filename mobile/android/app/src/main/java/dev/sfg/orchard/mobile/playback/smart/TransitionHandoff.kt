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

import kotlin.math.max

/**
 * The first safe point for the visible track identity to change.
 *
 * Equal-power tracks cross at the main handoff. DJ transitions split the upper and bass bands;
 * presentation waits for both crossovers so the cover, metadata, and progress never claim the
 * incoming song while the outgoing song is still louder in a retained band.
 */
internal fun audibleHandoffProgress(plan: TransitionPlan, usesSelectedPlan: Boolean): Float =
    when (plan.transitionStyle) {
        TransitionStyle.GAPLESS -> 0f
        TransitionStyle.DJ_BLEND,
        TransitionStyle.DJ_FILTER ->
            max(
                plan.handoffFraction,
                if (usesSelectedPlan) plan.bassSwapFraction else plan.handoffFraction,
            ).toFloat()
        TransitionStyle.EQUAL_POWER -> plan.handoffFraction.toFloat()
    }.coerceIn(0f, 1f)

/** Keep source scheduling, transition wall time, and incoming media time from the same plan. */
internal fun transitionMarkerFor(
    plan: TransitionPlan,
    trackId: String,
    incomingTrackId: String,
    usesSelectedPlan: Boolean,
): dev.sfg.orchard.mobile.model.TransitionMarker {
    val native = plan.nativePlan.takeIf { usesSelectedPlan }
    return dev.sfg.orchard.mobile.model.TransitionMarker(
        trackId = trackId,
        startMs = ((native?.transitionStart ?: plan.transitionStart) * 1000).toLong(),
        endMs = ((native?.transitionEnd ?: plan.transitionEnd) * 1000).toLong(),
        style = if (native != null) {
            if (native.strategy == "filtered_blend") "dj_filter" else "dj_blend"
        } else plan.transitionStyle.name.lowercase(),
        incomingTrackId = incomingTrackId,
        incomingCueMs = ((native?.incomingCueTime ?: plan.incomingCueTime) * 1000).toLong().coerceAtLeast(0),
        incomingPlaybackRate = native?.incomingTempoRatio ?: plan.incomingPlaybackRate,
        audibleHandoffProgress = if (native != null) {
            max(native.handoffFraction, native.bassSwapFraction).toFloat().coerceIn(0f, 1f)
        } else audibleHandoffProgress(plan, usesSelectedPlan = false),
        renderedDurationMs = ((native?.overlapSeconds ?: 0.0) * 1000).toLong(),
    )
}
