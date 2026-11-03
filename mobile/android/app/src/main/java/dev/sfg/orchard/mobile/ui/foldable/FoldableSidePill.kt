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
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.ui.components.dockGlass
import dev.sfg.orchard.mobile.ui.navigation.Routes

private data class PillItem(val route: String, val label: String, val icon: ImageVector)

private val homeItem = PillItem(Routes.HOME, "Home", Icons.Rounded.Home)
private val libraryItem = PillItem(Routes.LIBRARY, "Library", Icons.Rounded.LibraryMusic)
private val settingsItem = PillItem(Routes.SETTINGS, "Settings", Icons.Rounded.Person)

/** Icon-only vertical glass pill at the end edge for tablets and unfolded foldables. */
@Composable
fun FoldableSidePill(
    currentRoute: String?,
    onSelect: (String) -> Unit,
    onSearch: () -> Unit,
    showSettings: Boolean,
    modifier: Modifier = Modifier,
) {
    val items = listOfNotNull(homeItem, libraryItem, settingsItem.takeIf { showSettings })
    val selected =
        items.firstOrNull { it.route == currentRoute }
            ?: settingsItem.takeIf { showSettings && currentRoute == Routes.SETTINGS_HOME_LAYOUT }
    Column(
        modifier = modifier.padding(12.dp).dockGlass().padding(6.dp).selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        items.forEach { item ->
            PillButton(item.icon, item.label, item == selected) { onSelect(item.route) }
        }
        PillButton(Icons.Rounded.Search, "Search", false, onSearch)
    }
}

@Composable
private fun PillButton(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit) {
    val background by animateColorAsState(
        if (selected) Color.White.copy(alpha = 0.18f) else Color.Transparent,
        tween(180),
        label = "PillSelect",
    )
    Box(
        modifier =
            Modifier.size(52.dp)
                .clip(CircleShape)
                .background(background)
                .clickable(role = Role.Tab, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = label,
            tint = if (selected) Color.White else Color.White.copy(alpha = 0.72f),
            modifier = Modifier.size(24.dp),
        )
    }
}
