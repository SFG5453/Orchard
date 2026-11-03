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

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.ShortNavigationBarItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import dev.sfg.orchard.mobile.ui.glass.GlassTone
import dev.sfg.orchard.mobile.ui.glass.LocalGlass
import dev.sfg.orchard.mobile.ui.glass.glassPane
import dev.sfg.orchard.mobile.ui.navigation.Routes
import dev.sfg.orchard.mobile.ui.theme.LocalAccent
import kotlin.math.roundToInt
import kotlin.math.sign

data class BottomDestination(val route: String, val label: String, val icon: ImageVector)

private val destinations = listOf(
    BottomDestination(Routes.HOME, "Home", Icons.Rounded.Home),
    BottomDestination(Routes.LIBRARY, "Library", Icons.Rounded.LibraryMusic),
)

private val settingsDestination = BottomDestination(Routes.SETTINGS, "Settings", Icons.Rounded.Person)

/**
 * Height the resting dock occupies. Screens reserve this much bottom content padding so their
 * last row can still be scrolled clear of the floating chrome.
 */
val OrchardChromeHeight = 144.dp

private val NavHeight = 64.dp
private val CollapsedSize = 56.dp
private val DockGap = 8.dp

/**
 * Floating split dock: a tab pill with a round search button beside it, the mini player above.
 * While [collapsed], the pill shrinks to the current tab and the player slides in between.
 */
@Composable
fun OrchardDock(
    currentRoute: String?,
    onSelect: (String) -> Unit,
    onSearch: () -> Unit,
    collapsed: Boolean,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
    showSettings: Boolean = false,
    player: @Composable () -> Unit,
) {
    val progress by animateFloatAsState(
        targetValue = if (collapsed) 1f else 0f,
        // M3 expressive default spatial spring.
        animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f),
        label = "DockCollapse",
    )
    // Tabs finish shrinking first so the player never lands on a full-width pill.
    val tabsProgress = (progress * 1.5f).coerceIn(0f, 1f)
    val allDestinations = if (showSettings) destinations + settingsDestination else destinations
    val selected = allDestinations.firstOrNull { it.route == currentRoute }
        ?: settingsDestination.takeIf { showSettings && currentRoute == Routes.SETTINGS_HOME_LAYOUT }

    Layout(
        modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        content = {
            Box(Modifier.dockGlass()) {
                if (tabsProgress < 1f) {
                    DockTabs(
                        destinations = allDestinations,
                        selected = selected,
                        onSelect = onSelect,
                        modifier = Modifier.alpha((1f - tabsProgress * 1.6f).coerceIn(0f, 1f)),
                    )
                }
                if (tabsProgress > 0f) {
                    IconButton(
                        onClick = onExpand,
                        modifier = Modifier.fillMaxSize().alpha(tabsProgress),
                    ) {
                        Icon(
                            imageVector = (selected ?: destinations.first()).icon,
                            contentDescription = "Show tabs",
                            tint = Color.White,
                        )
                    }
                }
            }
            Box { player() }
            Box(Modifier.dockGlass()) {
                IconButton(onClick = onSearch, modifier = Modifier.fillMaxSize()) {
                    Icon(Icons.Rounded.Search, contentDescription = "Search", tint = Color.White)
                }
            }
        },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val gap = DockGap.roundToPx()
        val small = CollapsedSize.roundToPx()
        val navH = lerp(NavHeight.roundToPx().toFloat(), small.toFloat(), progress).roundToInt()
        val fab = navH

        val tabsW = lerp((width - fab - gap).toFloat(), small.toFloat(), tabsProgress).roundToInt().coerceAtLeast(small)
        val playerX = lerp(0f, (small + gap).toFloat(), progress).roundToInt()
        val playerW = (lerp(width.toFloat(), (width - 2 * (small + gap)).toFloat(), progress).roundToInt()).coerceAtLeast(0)

        val tabs = measurables[0].measure(Constraints.fixed(tabsW, navH))
        val player = measurables[1].measure(Constraints(minWidth = playerW, maxWidth = playerW))
        val search = measurables[2].measure(Constraints.fixed(fab, fab))

        val restingH = if (player.height > 0) player.height + gap + NavHeight.roundToPx() else NavHeight.roundToPx()
        val collapsedH = maxOf(small, player.height)
        val height = lerp(restingH.toFloat(), collapsedH.toFloat(), progress).roundToInt()

        layout(width, height) {
            tabs.place(0, height - navH)
            search.place(width - fab, height - fab)
            // Placed last so it slides over the shrinking pill.
            player.place(playerX, 0)
        }
    }
}

@Composable
private fun DockTabs(
    destinations: List<BottomDestination>,
    selected: BottomDestination?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = LocalAccent.current
    val colors = ShortNavigationBarItemDefaults.colors(
        selectedIconColor = Color.White,
        selectedTextColor = Color.White,
        selectedIndicatorColor = accent.copy(alpha = 0.42f),
        unselectedIconColor = Color.White.copy(alpha = 0.72f),
        unselectedTextColor = Color.White.copy(alpha = 0.72f),
    )
    Row(
        modifier
            .fillMaxSize()
            .padding(horizontal = 6.dp)
            .selectableGroup(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        destinations.forEach { destination ->
            ShortNavigationBarItem(
                selected = destination == selected,
                onClick = { onSelect(destination.route) },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = {
                    Text(
                        destination.label,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                },
                colors = colors,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Collapses the dock on downward content scroll and expands it on upward scroll. */
@Stable
class DockCollapseState(private val thresholdPx: Float) {
    var collapsed by mutableStateOf(false)
    private var travel = 0f

    val connection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            // Pulling past the top consumes nothing, so count it as upward travel.
            val dy = consumed.y + available.y.coerceAtLeast(0f)
            if (dy == 0f) return Offset.Zero
            if (dy.sign != travel.sign) travel = 0f
            travel += dy
            if (travel < -thresholdPx) collapsed = true else if (travel > thresholdPx) collapsed = false
            return Offset.Zero
        }
    }
}

@Composable
fun rememberDockCollapse(): DockCollapseState {
    val threshold = with(LocalDensity.current) { 24.dp.toPx() }
    return remember(threshold) { DockCollapseState(threshold) }
}

/** Shared pill treatment so the tabs, search and player read as one set. */
@Composable
internal fun Modifier.dockGlass(): Modifier {
    val glassTint = LocalGlass.current.tint
    val tint = if (glassTint != Color.Unspecified && glassTint.alpha > 0f) glassTint else LocalAccent.current
    return this
        .glassPane(CircleShape, GlassTone.CHROME)
        .clip(CircleShape)
        .background(tint.copy(alpha = 0.18f))
        .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
}
