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

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry
import dev.sfg.orchard.mobile.ui.navigation.Routes

private val TopLevelRoutes = setOf(Routes.HOME, Routes.LIBRARY, Routes.SETTINGS)

private typealias NavScope = AnimatedContentTransitionScope<NavBackStackEntry>

// Tab to tab is a fade-through; everything else is a push along the horizontal axis.
private fun NavScope.isTabSwitch(): Boolean =
    initialState.destination.route in TopLevelRoutes && targetState.destination.route in TopLevelRoutes

private val slideSpring = spring<IntOffset>(dampingRatio = 0.9f, stiffness = 420f)

object NavMotion {
    val enter: NavScope.() -> EnterTransition = {
        if (isTabSwitch()) {
            fadeIn(tween(240, delayMillis = 70, easing = LinearOutSlowInEasing)) +
                scaleIn(tween(320, delayMillis = 70, easing = FastOutSlowInEasing), initialScale = 0.94f)
        } else {
            slideInHorizontally(slideSpring) { it / 3 } + fadeIn(tween(220, delayMillis = 40))
        }
    }

    val exit: NavScope.() -> ExitTransition = {
        if (isTabSwitch()) {
            fadeOut(tween(90)) + scaleOut(tween(160), targetScale = 1.03f)
        } else {
            // The outgoing page recedes a touch so the incoming one reads as stacked on top.
            slideOutHorizontally(slideSpring) { -it / 8 } +
                fadeOut(tween(200)) +
                scaleOut(tween(260), targetScale = 0.96f)
        }
    }

    val popEnter: NavScope.() -> EnterTransition = {
        if (isTabSwitch()) {
            fadeIn(tween(240, delayMillis = 70)) + scaleIn(tween(320, delayMillis = 70), initialScale = 0.94f)
        } else {
            slideInHorizontally(slideSpring) { -it / 8 } +
                fadeIn(tween(240)) +
                scaleIn(tween(300), initialScale = 0.96f)
        }
    }

    val popExit: NavScope.() -> ExitTransition = {
        if (isTabSwitch()) {
            fadeOut(tween(90)) + scaleOut(tween(160), targetScale = 1.03f)
        } else {
            slideOutHorizontally(slideSpring) { it / 3 } + fadeOut(tween(180))
        }
    }
}
