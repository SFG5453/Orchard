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

package dev.sfg.orchard.mobile.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Scrolls the lyric list so visible lines catch up to the new position one after another. */
internal class LyricGlide {
    private val shifts = HashMap<Int, Animatable<Float, AnimationVector1D>>()
    // Offset of the active line after the last scroll settled.
    private var rest: Int? = null

    fun shift(index: Int): Float = shifts.getOrPut(index) { Animatable(0f) }.value

    suspend fun scrollTo(state: LazyListState, target: Int, staggered: Boolean) = coroutineScope {
        val before = state.offsetOf(target)
        val settled = rest
        when {
            !staggered || before == null || settled == null -> state.animateScrollToItem(target)
            abs(before - settled) < MIN_SHIFT_PX -> state.scrollToItem(target)
            else -> {
                val delta = (before - settled).toFloat()
                val near = state.layoutInfo.visibleItemsInfo.map { it.index }.filter { abs(it - target) <= GLIDE_RANGE }
                // Lines start where they were drawn, then spring into the new layout.
                near.forEach { i -> shifts.getOrPut(i) { Animatable(0f) }.let { it.snapTo(it.value + delta) } }
                state.scrollToItem(target)
                near.forEach { i ->
                    launch {
                        delay(minOf(maxOf(i - target, 0), MAX_STAGGER_STEPS) * STAGGER_MS)
                        shifts.getValue(i).animateTo(0f, spring(dampingRatio = 0.62f, stiffness = Spring.StiffnessLow))
                    }
                }
            }
        }
        withFrameNanos {}
        rest = state.offsetOf(target)
    }

    /** Drops lines back to their layout position, for example when the reader starts scrolling. */
    suspend fun settle() {
        rest = null
        shifts.values.forEach { it.snapTo(0f) }
    }

    private fun LazyListState.offsetOf(index: Int): Int? =
        layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.offset

    private companion object {
        const val GLIDE_RANGE = 10
        const val MAX_STAGGER_STEPS = 8
        const val STAGGER_MS = 45L
        const val MIN_SHIFT_PX = 2
    }
}
