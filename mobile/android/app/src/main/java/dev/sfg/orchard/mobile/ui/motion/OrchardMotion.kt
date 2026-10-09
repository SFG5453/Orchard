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

package dev.sfg.orchard.mobile.ui.motion

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.WeakHashMap
import kotlinx.coroutines.delay

/** Shared timings so every screen moves with the same accent. */
object OrchardMotion {
    const val STAGGER_MS = 38L
    const val STAGGER_CAP_MS = 380L
    // Items composed within this window after a screen opens join the opening cascade.
    const val OPENING_WINDOW_MS = 650L
    const val RISE_MS = 420
    const val SCROLL_RISE_MS = 260
    const val SCROLL_STAGGER_CAP_MS = 180L

    val pressSpring = spring<Float>(dampingRatio = 0.5f, stiffness = 650f)
    val releaseSpring = spring<Float>(dampingRatio = 0.42f, stiffness = Spring.StiffnessMedium)
}

// First-seen time per back stack entry, so each destination gets its own opening cascade.
// Weak keys: entries vanish with the back stack, the map does not keep them alive.
// A clock that forgets things on purpose. Unlike me, who forgets them by accident.
private object RevealClock {
    private val epochs = WeakHashMap<LifecycleOwner, Long>()

    fun epochOf(owner: LifecycleOwner): Long = synchronized(epochs) {
        epochs.getOrPut(owner) { SystemClock.uptimeMillis() }
    }
}

/**
 * Fades and lifts content in the first time it composes. Siblings composed while the screen is
 * opening cascade by [index]; anything scrolled into view later rises quickly with no delay.
 * The played flag is saveable, so lazy items do not replay when scrolled back into view.
 * [cascadeOnScroll] keeps a short stagger for groups that arrive together, such as a rail.
 */
@Composable
fun Modifier.riseIn(
    index: Int = 0,
    distance: Float = 28f,
    fromScale: Float = 0.97f,
    cascadeOnScroll: Boolean = false,
): Modifier {
    val epoch = RevealClock.epochOf(LocalLifecycleOwner.current)
    var played by rememberSaveable { mutableStateOf(false) }
    val progress = remember { Animatable(if (played) 1f else 0f) }
    val distancePx = with(LocalDensity.current) { distance.dp.toPx() }

    LaunchedEffect(Unit) {
        if (played) return@LaunchedEffect
        val opening = SystemClock.uptimeMillis() - epoch < OrchardMotion.OPENING_WINDOW_MS
        val cap = when {
            opening -> OrchardMotion.STAGGER_CAP_MS
            cascadeOnScroll -> OrchardMotion.SCROLL_STAGGER_CAP_MS
            else -> 0L
        }
        delay((index * OrchardMotion.STAGGER_MS).coerceIn(0L, cap))
        progress.animateTo(
            1f,
            tween(
                if (opening) OrchardMotion.RISE_MS else OrchardMotion.SCROLL_RISE_MS,
                easing = FastOutSlowInEasing,
            ),
        )
        played = true
    }

    return graphicsLayer {
        val p = progress.value
        alpha = p
        translationY = (1f - p) * distancePx
        val s = fromScale + (1f - fromScale) * p
        scaleX = s
        scaleY = s
    }
}

/** Sinks slightly while pressed and springs back with a little overshoot. */
@Composable
fun Modifier.pressScale(interactionSource: InteractionSource, pressedScale: Float = 0.95f): Modifier {
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) pressedScale else 1f,
        animationSpec = if (pressed) OrchardMotion.pressSpring else OrchardMotion.releaseSpring,
        label = "PressScale",
    )
    return graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** Clickable with a ripple clipped to [shape] and a springy press scale. */
@Composable
fun Modifier.bounceClickable(
    shape: Shape = RectangleShape,
    pressedScale: Float = 0.95f,
    enabled: Boolean = true,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier {
    val source = remember { MutableInteractionSource() }
    return this
        .pressScale(source, pressedScale)
        .clip(shape)
        .clickable(
            interactionSource = source,
            indication = ripple(),
            enabled = enabled,
            onClickLabel = onClickLabel,
            onClick = onClick,
        )
}

/**
 * Pops once each time [trigger] changes after the first composition. Useful for icons that
 * flip state (like, download done, selected tab) so the change reads as an event.
 * With [onlyOn] set, only changes into that value pop.
 */
@Composable
fun Modifier.popOnChange(trigger: Any?, peak: Float = 1.28f, onlyOn: Any? = null): Modifier {
    val scale = remember { Animatable(1f) }
    var seen by remember { mutableStateOf(trigger) }
    LaunchedEffect(trigger) {
        if (seen == trigger) return@LaunchedEffect
        seen = trigger
        if (onlyOn != null && trigger != onlyOn) return@LaunchedEffect
        scale.animateTo(
            1f,
            keyframes {
                durationMillis = 420
                1f at 0
                0.82f at 70
                peak at 190
                0.96f at 300
            },
        )
    }
    return graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}
