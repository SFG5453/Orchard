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

package dev.sfg.orchard.mobile.ui.components

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.ui.glass.GlassTone
import dev.sfg.orchard.mobile.ui.glass.LocalGlass
import dev.sfg.orchard.mobile.ui.glass.glassPane
import dev.sfg.orchard.mobile.ui.motion.popOnChange
import dev.sfg.orchard.mobile.ui.navigation.Routes
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

data class BottomDestination(val route: String, val label: String, val icon: ImageVector)

private val destinations = listOf(
    BottomDestination(Routes.HOME, "Home", Icons.Rounded.Home),
    BottomDestination(Routes.SEARCH, "Search", Icons.Rounded.Search),
    BottomDestination(Routes.LIBRARY, "Library", Icons.Rounded.LibraryMusic),
    BottomDestination(Routes.SETTINGS, "Settings", Icons.Rounded.Person),
)

/**
 * Height the mini player and nav bar occupy together. Screens reserve this much bottom content
 * padding so their last row can still be scrolled clear of the floating chrome.
 */
val OrchardChromeHeight = 132.dp

private val BottomBarShape = RoundedCornerShape(26.dp)
/** Bottom navigation bar: a frosted floating pane, inset from the sides and fully rounded. */
@Composable
fun OrchardBottomBar(currentRoute: String?, onSelect: (String) -> Unit) {
    val glassTint = LocalGlass.current.tint
    val selectedIndex = remember(currentRoute, destinations) {
        val idx = destinations.indexOfFirst { it.route == currentRoute }
        if (idx != -1) idx
        else if (currentRoute == Routes.SETTINGS_HOME_LAYOUT) {
            destinations.indexOfFirst { it.route == Routes.SETTINGS }.takeIf { it != -1 }
        } else {
            null
        }
    }
    var lastKnownIndex by remember { mutableIntStateOf(selectedIndex ?: 0) }
    LaunchedEffect(selectedIndex) {
        if (selectedIndex != null) {
            lastKnownIndex = selectedIndex
        }
    }
    val activeIndex = selectedIndex ?: lastKnownIndex

    val tintColor = if (glassTint != Color.Unspecified && glassTint.alpha > 0f) glassTint else LocalAccent.current

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .glassPane(BottomBarShape, GlassTone.CHROME)
            .background(
                brush = Brush.verticalGradient(
                    listOf(
                        tintColor.copy(alpha = 0.38f),
                        tintColor.copy(alpha = 0.24f),
                    ),
                ),
                shape = BottomBarShape,
            )
            .border(
                width = 1.dp,
                brush = Brush.verticalGradient(
                    listOf(
                        tintColor.copy(alpha = 0.55f),
                        tintColor.copy(alpha = 0.25f),
                    ),
                ),
                shape = BottomBarShape,
            ),
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) {
            val count = destinations.size
            val slotWidth = maxWidth / count
            val indicatorWidth = if (slotWidth - 8.dp < 56.dp) slotWidth - 8.dp else 56.dp
            val indicatorHeight = 32.dp
            val targetOffset = (slotWidth * (activeIndex + 0.5f)) - (indicatorWidth / 2)

            val animatedOffset by animateDpAsState(
                targetValue = targetOffset,
                animationSpec = spring(
                    dampingRatio = 0.78f,
                    stiffness = 380f,
                ),
                label = "BottomBarIndicatorX",
            )
            val indicatorAlpha by animateFloatAsState(
                targetValue = if (selectedIndex != null) 1f else 0f,
                animationSpec = tween(180),
                label = "BottomBarIndicatorAlpha",
            )

            if (indicatorAlpha > 0.001f) {
                val indicatorColor = tintColor.copy(alpha = 0.45f)

                Box(
                    modifier = Modifier
                        .offset { IntOffset(animatedOffset.roundToPx(), 6.dp.roundToPx()) }
                        .size(width = indicatorWidth, height = indicatorHeight)
                        .alpha(indicatorAlpha)
                        .background(
                            color = indicatorColor,
                            shape = RoundedCornerShape(16.dp),
                        )
                        .border(
                            width = 0.5.dp,
                            color = Color.White.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(16.dp),
                        ),
                )
            }

            NavigationBar(
                containerColor = Color.Transparent,
                tonalElevation = 0.dp,
                // The Scaffold already insets the whole chrome column above the system navigation bar.
                // Letting the bar apply that inset a second time would eat into its fixed 64dp height,
                // which squashed the icons and labels under 3-button navigation.
                windowInsets = WindowInsets(0),
                modifier = Modifier.height(64.dp),
            ) {
                destinations.forEach { destination ->
                    val isSelected = currentRoute == destination.route
                    NavigationBarItem(
                        selected = isSelected,
                        onClick = { onSelect(destination.route) },
                        icon = {
                            val iconScale by animateFloatAsState(
                                targetValue = if (isSelected) 1.08f else 1.0f,
                                animationSpec = spring(dampingRatio = 0.65f, stiffness = 400f),
                                label = "NavIconScale",
                            )
                            Icon(
                                destination.icon,
                                contentDescription = destination.label,
                                modifier = Modifier
                                    .size(22.dp)
                                    .scale(iconScale)
                                    .popOnChange(isSelected, peak = 1.35f, onlyOn = true),
                            )
                        },
                        label = {
                            Text(
                                destination.label,
                                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                            )
                        },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Color.White,
                            selectedTextColor = Color.White,
                            indicatorColor = Color.Transparent,
                            unselectedIconColor = Color.White.copy(alpha = 0.72f),
                            unselectedTextColor = Color.White.copy(alpha = 0.72f),
                        ),
                    )
                }
            }
        }
    }
}
