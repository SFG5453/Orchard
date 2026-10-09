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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import dev.sfg.orchard.mobile.ui.scroll.OrchardLazyRow as LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.auth.AuthState
import dev.sfg.orchard.mobile.ui.components.ArtworkTile
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent
import java.util.Calendar

/** Fallback masthead for foldable layout when library/content is completely empty. */
@Composable
internal fun FoldableMasthead(
    auth: AuthState,
    categories: List<String> = emptyList(),
    selectedCategory: String = "All",
    onSelectCategory: (String) -> Unit = {},
    onSearch: () -> Unit,
    onProfile: () -> Unit,
) {
    val greeting = remember {
        when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
            in 5..11 -> "Good morning"
            in 12..17 -> "Good afternoon"
            else -> "Good evening"
        }
    }

    val displayName =
        when (auth) {
            is AuthState.SignedIn -> auth.displayName.ifBlank { "Listener" }
            else -> "Guest"
        }
    val avatarUrl =
        when (auth) {
            is AuthState.SignedIn -> auth.avatarUrl
            else -> ""
        }

    Column(
        modifier =
            Modifier.fillMaxWidth()
                .statusBarsPadding()
                .padding(horizontal = 24.dp, vertical = 18.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "$greeting, $displayName",
                style =
                    MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.Bold,
                        fontSize = 28.sp,
                    ),
                color = CanopyColors.Text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Surface(
                    onClick = onSearch,
                    color = CanopyColors.Surface,
                    shape = CircleShape,
                    modifier = Modifier.width(260.dp).height(44.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = "Search",
                            tint = CanopyColors.Muted,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = "Search music, albums...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = CanopyColors.Muted,
                            maxLines = 1,
                        )
                    }
                }

                Surface(
                    onClick = onProfile,
                    shape = CircleShape,
                    color = CanopyColors.Surface,
                    modifier = Modifier.size(44.dp),
                ) {
                    if (avatarUrl.isNotBlank()) {
                        ArtworkTile(
                            url = avatarUrl,
                            description = displayName,
                            modifier = Modifier.fillMaxSize(),
                            radius = 999,
                        )
                    } else {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Rounded.Person,
                                contentDescription = "User Avatar",
                                tint = LocalAccent.current,
                                modifier = Modifier.size(24.dp),
                            )
                        }
                    }
                }
            }
        }

        if (categories.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(categories) { category ->
                    val isSelected = selectedCategory == category
                    Surface(
                        onClick = { onSelectCategory(category) },
                        shape = CircleShape,
                        color = if (isSelected) Color.White else CanopyColors.Surface,
                        border = if (isSelected) null else BorderStroke(1.dp, CanopyColors.Rule),
                        modifier = Modifier.height(34.dp),
                    ) {
                        Box(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = category,
                                style =
                                    MaterialTheme.typography.labelMedium.copy(
                                        fontWeight =
                                            if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        fontSize = 13.sp,
                                    ),
                                color = if (isSelected) Color.Black else CanopyColors.Text,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun FoldableSectionHeader(title: String, onSeeAll: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style =
                MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                ),
            color = CanopyColors.Text,
        )
        Row(
            modifier =
                Modifier.clip(CircleShape)
                    .clickable(onClick = onSeeAll)
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "See all",
                style = MaterialTheme.typography.labelLarge,
                color = CanopyColors.Muted,
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                Icons.AutoMirrored.Rounded.ArrowForward,
                contentDescription = null,
                tint = CanopyColors.Muted,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}
