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

package dev.sfg.orchard.mobile.ui.foldable

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.ui.components.OrchardMark
import dev.sfg.orchard.mobile.ui.glass.GlassTone
import dev.sfg.orchard.mobile.ui.glass.LocalGlass
import dev.sfg.orchard.mobile.ui.glass.glassPane
import dev.sfg.orchard.mobile.ui.navigation.Routes
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

data class RailDestination(val route: String, val label: String, val icon: ImageVector)

private val foldableDestinations = listOf(
    RailDestination(Routes.HOME, "Home", Icons.Rounded.Home),
    RailDestination(Routes.SEARCH, "Search", Icons.Rounded.Search),
    RailDestination(Routes.LIBRARY, "Library", Icons.Rounded.LibraryMusic),
    RailDestination(Routes.DEVICES, "Connect", Icons.Rounded.Groups),
    RailDestination(Routes.SETTINGS, "Settings", Icons.Rounded.Person),
)

private val NavRailShape = RoundedCornerShape(28.dp)

/**
 * Vertical navigation rail designed for foldable devices (unfolded) and tablets.
 * Sits elegantly along the left edge, replacing the bottom bar and freeing up
 * valuable vertical screen real estate.
 */
@Composable
fun OrchardNavigationRail(
    currentRoute: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val glass = LocalGlass.current.enabled
    val glassTint = LocalGlass.current.tint
    val tintColor = if (glassTint != Color.Unspecified && glassTint.alpha > 0f) glassTint else LocalAccent.current

    val selectedIndex = remember(currentRoute) {
        val idx = foldableDestinations.indexOfFirst { it.route == currentRoute }
        if (idx != -1) idx
        else if (currentRoute == Routes.SETTINGS_HOME_LAYOUT) {
            foldableDestinations.indexOfFirst { it.route == Routes.SETTINGS }.takeIf { it != -1 }
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

    val railScrim = remember {
        Brush.horizontalGradient(
            0f to CanopyColors.Chrome.copy(alpha = 0.92f),
            0.75f to CanopyColors.Chrome.copy(alpha = 0.75f),
            1f to Color.Transparent,
        )
    }

    Box(
        modifier = modifier
            .fillMaxHeight()
            .width(FoldableNavRailWidth)
            .background(CanopyColors.Chrome.copy(alpha = 0.95f))
            .drawWithContent {
                drawContent()
                drawLine(
                    color = Color.White.copy(alpha = 0.08f),
                    start = androidx.compose.ui.geometry.Offset(size.width, 0f),
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            },
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .width(FoldableNavRailWidth)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top: Orchard Logo
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = ripple(bounded = true, radius = 24.dp),
                        onClick = { onSelect(Routes.HOME) },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                OrchardMark(Modifier.size(34.dp))
            }

            Spacer(Modifier.height(8.dp))

            // Nav destinations evenly spaced across the rail height
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                foldableDestinations.forEachIndexed { index, destination ->
                    val isSelected = selectedIndex == index
                    val activeProgress by animateFloatAsState(
                        targetValue = if (isSelected) 1f else 0f,
                        animationSpec = spring(dampingRatio = 0.75f, stiffness = 350f),
                        label = "RailItemSelect_${destination.route}",
                    )

                    val unselectedColor = if (glass) Color.White.copy(alpha = 0.65f) else CanopyColors.Muted
                    val itemColor by animateColorAsState(
                        targetValue = if (isSelected) Color.White else unselectedColor,
                        animationSpec = tween(180),
                        label = "RailItemColor_${destination.route}",
                    )

                    val pillBg = if (isSelected) {
                        if (tintColor != Color.Unspecified && tintColor.alpha > 0f) {
                            tintColor.copy(alpha = 0.38f)
                        } else {
                            Color.White.copy(alpha = 0.16f)
                        }
                    } else {
                        Color.Transparent
                    }

                    Surface(
                        onClick = { onSelect(destination.route) },
                        shape = RoundedCornerShape(16.dp),
                        color = pillBg,
                        border = if (isSelected) BorderStroke(1.dp, Color.White.copy(alpha = 0.20f)) else null,
                        modifier = Modifier
                            .size(width = 62.dp, height = 54.dp)
                            .scale(1f + 0.04f * activeProgress),
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Icon(
                                destination.icon,
                                contentDescription = destination.label,
                                tint = itemColor,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                text = destination.label,
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = itemColor,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}
