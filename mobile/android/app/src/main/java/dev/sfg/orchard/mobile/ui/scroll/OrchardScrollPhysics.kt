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

package dev.sfg.orchard.mobile.ui.scroll

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.animation.core.spring
import androidx.compose.foundation.OverscrollEffect
import androidx.compose.foundation.OverscrollFactory
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn as FoundationLazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow as FoundationLazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.verticalScroll as foundationVerticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

object OrchardScrollPhysics {
    val flingBehavior: FlingBehavior = OrchardFlingBehavior()
    val overscrollFactory: OverscrollFactory = OrchardOverscrollFactory
}

@Composable
fun OrchardLazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    verticalArrangement: Arrangement.Vertical =
        if (!reverseLayout) Arrangement.Top else Arrangement.Bottom,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    FoundationLazyColumn(
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
        reverseLayout = reverseLayout,
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        flingBehavior = OrchardScrollPhysics.flingBehavior,
        userScrollEnabled = userScrollEnabled,
        content = content,
    )
}

@Composable
fun OrchardLazyRow(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
    reverseLayout: Boolean = false,
    horizontalArrangement: Arrangement.Horizontal =
        if (!reverseLayout) Arrangement.Start else Arrangement.End,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    userScrollEnabled: Boolean = true,
    content: LazyListScope.() -> Unit,
) {
    FoundationLazyRow(
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
        reverseLayout = reverseLayout,
        horizontalArrangement = horizontalArrangement,
        verticalAlignment = verticalAlignment,
        flingBehavior = OrchardScrollPhysics.flingBehavior,
        userScrollEnabled = userScrollEnabled,
        content = content,
    )
}

fun Modifier.orchardVerticalScroll(
    state: ScrollState,
    enabled: Boolean = true,
    reverseScrolling: Boolean = false,
): Modifier = foundationVerticalScroll(
    state = state,
    enabled = enabled,
    flingBehavior = OrchardScrollPhysics.flingBehavior,
    reverseScrolling = reverseScrolling,
)

private class OrchardFlingBehavior : FlingBehavior {
    private val decay =
        exponentialDecay<Float>(frictionMultiplier = 0.55f, absVelocityThreshold = 5f)

    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        var lastValue = 0f
        var remainingVelocity = initialVelocity
        AnimationState(initialValue = 0f, initialVelocity = initialVelocity).animateDecay(decay) {
            val delta = value - lastValue
            val consumed = scrollBy(delta)
            lastValue = value
            remainingVelocity = velocity
            if (abs(delta - consumed) > 0.5f) cancelAnimation()
        }
        return if (abs(remainingVelocity) < 5f) 0f else remainingVelocity
    }
}

private object OrchardOverscrollFactory : OverscrollFactory {
    override fun createOverscrollEffect(): OverscrollEffect = OrchardRubberBandOverscrollEffect()

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = javaClass.hashCode()
}

private class OrchardRubberBandOverscrollEffect : OverscrollEffect {
    private var offset by mutableStateOf(Offset.Zero)
    private var containerSize by mutableStateOf(IntSize.Zero)

    override val isInProgress: Boolean
        get() = abs(offset.x) > 0.5f || abs(offset.y) > 0.5f

    override fun applyToScroll(
        delta: Offset,
        source: NestedScrollSource,
        performScroll: (Offset) -> Offset,
    ): Offset {
        val preConsumed = Offset(x = relaxAxis(offset.x, delta.x), y = relaxAxis(offset.y, delta.y))
        if (preConsumed != Offset.Zero) {
            offset += preConsumed
            offset =
                Offset(
                    x = if (abs(offset.x) < 0.5f) 0f else offset.x,
                    y = if (abs(offset.y) < 0.5f) 0f else offset.y,
                )
        }

        val leftForScroll = delta - preConsumed
        val consumedByScroll = performScroll(leftForScroll)
        val unconsumed = leftForScroll - consumedByScroll
        if (source == NestedScrollSource.UserInput && unconsumed != Offset.Zero) {
            offset =
                Offset(
                    x = rubberBandAxis(offset.x, unconsumed.x, containerSize.width),
                    y = rubberBandAxis(offset.y, unconsumed.y, containerSize.height),
                )
        }
        return preConsumed + consumedByScroll
    }

    override suspend fun applyToFling(
        velocity: Velocity,
        performFling: suspend (Velocity) -> Velocity,
    ) {
        val consumed = performFling(velocity)
        val remaining = velocity - consumed
        val start = offset
        coroutineScope {
            launch {
                Animatable(start.x).animateTo(
                    targetValue = 0f,
                    initialVelocity = remaining.x,
                    animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f),
                ) {
                    offset = offset.copy(x = value)
                }
            }
            launch {
                Animatable(start.y).animateTo(
                    targetValue = 0f,
                    initialVelocity = remaining.y,
                    animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f),
                ) {
                    offset = offset.copy(y = value)
                }
            }
        }
        offset = Offset.Zero
    }

    override val node: DelegatableNode =
        object : Modifier.Node(), LayoutModifierNode {
            override fun MeasureScope.measure(
                measurable: Measurable,
                constraints: Constraints,
            ): MeasureResult {
                val placeable = measurable.measure(constraints)
                containerSize = IntSize(placeable.width, placeable.height)
                return layout(placeable.width, placeable.height) {
                    val translated = IntOffset(offset.x.roundToInt(), offset.y.roundToInt())
                    placeable.placeRelativeWithLayer(translated.x, translated.y)
                }
            }
        }

    private fun relaxAxis(current: Float, delta: Float): Float {
        if (current == 0f || delta == 0f || sign(current) == sign(delta)) return 0f
        return sign(delta) * minOf(abs(delta), abs(current))
    }

    private fun rubberBandAxis(current: Float, delta: Float, dimensionPx: Int): Float {
        if (delta == 0f) return current
        val dimension = dimensionPx.coerceAtLeast(1).toFloat()
        val resistance = 0.55f / (1f + abs(current) / (dimension * 0.12f))
        val limit = dimension * 0.28f
        return (current + delta * resistance).coerceIn(-limit, limit)
    }
}
