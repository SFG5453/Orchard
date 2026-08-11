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
internal fun audibleHandoffProgress(plan: TransitionPlan, rendered: Boolean): Float =
    when (plan.transitionStyle) {
        TransitionStyle.GAPLESS -> 0f
        TransitionStyle.DJ_BLEND,
        TransitionStyle.DJ_FILTER ->
            max(
                plan.handoffFraction,
                if (rendered) plan.bassSwapFraction else LIVE_DJ_BASS_HANDOFF,
            ).toFloat()
        TransitionStyle.EQUAL_POWER -> plan.handoffFraction.toFloat()
    }.coerceIn(0f, 1f)

/** Centre of the live mixer's independent 750 ms bass handoff. */
private const val LIVE_DJ_BASS_HANDOFF = 0.7
