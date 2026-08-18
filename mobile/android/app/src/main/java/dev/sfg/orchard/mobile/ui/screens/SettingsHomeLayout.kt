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

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.BuiltInHomeSection
import dev.sfg.orchard.mobile.model.HomeSectionConfig
import dev.sfg.orchard.mobile.model.OrchardSettings
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent
import dev.sfg.orchard.mobile.auth.AuthState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsHomeLayout(
    settings: OrchardSettings,
    auth: AuthState,
    onSettings: (OrchardSettings) -> Unit,
    onBack: () -> Unit
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    val tabs = listOf("Online", "Offline")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Home Screen Layout") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = CanopyColors.Chrome,
                    titleContentColor = CanopyColors.Text,
                    navigationIconContentColor = CanopyColors.Text
                )
            )
        },
        containerColor = CanopyColors.Chrome
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PrimaryTabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = CanopyColors.Chrome,
                contentColor = CanopyColors.Text,
                indicator = {
                    TabRowDefaults.SecondaryIndicator(
                        Modifier.tabIndicatorOffset(selectedTabIndex, matchContentSize = true),
                        color = LocalAccent.current
                    )
                }
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTabIndex == index,
                        onClick = { selectedTabIndex = index },
                        text = { Text(title, fontWeight = FontWeight.SemiBold) },
                        selectedContentColor = LocalAccent.current,
                        unselectedContentColor = CanopyColors.Muted
                    )
                }
            }

            val currentList = if (selectedTabIndex == 0) settings.homeLayoutOnline else settings.homeLayoutOffline

            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    Text(
                        "Reorder and toggle sections for the Home screen.",
                        color = CanopyColors.Muted,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }

                itemsIndexed(currentList) { index, config ->
                    SectionItemRow(
                        config = config,
                        title = config.section.title(auth),
                        isFirst = index == 0,
                        isLast = index == currentList.size - 1,
                        onMoveUp = {
                            val newList = currentList.toMutableList()
                            val temp = newList[index - 1]
                            newList[index - 1] = newList[index]
                            newList[index] = temp
                            if (selectedTabIndex == 0) onSettings(settings.copy(homeLayoutOnline = newList))
                            else onSettings(settings.copy(homeLayoutOffline = newList))
                        },
                        onMoveDown = {
                            val newList = currentList.toMutableList()
                            val temp = newList[index + 1]
                            newList[index + 1] = newList[index]
                            newList[index] = temp
                            if (selectedTabIndex == 0) onSettings(settings.copy(homeLayoutOnline = newList))
                            else onSettings(settings.copy(homeLayoutOffline = newList))
                        },
                        onToggle = { enabled ->
                            val newList = currentList.toMutableList()
                            newList[index] = config.copy(enabled = enabled)
                            if (selectedTabIndex == 0) onSettings(settings.copy(homeLayoutOnline = newList))
                            else onSettings(settings.copy(homeLayoutOffline = newList))
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionItemRow(
    config: HomeSectionConfig,
    title: String,
    isFirst: Boolean,
    isLast: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onToggle: (Boolean) -> Unit
) {
    Surface(
        color = CanopyColors.Surface,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = CanopyColors.Text
                )
            }
            IconButton(onClick = onMoveUp, enabled = !isFirst) {
                Icon(Icons.Rounded.ArrowUpward, contentDescription = "Move Up", tint = if (isFirst) CanopyColors.Muted else CanopyColors.Text)
            }
            IconButton(onClick = onMoveDown, enabled = !isLast) {
                Icon(Icons.Rounded.ArrowDownward, contentDescription = "Move Down", tint = if (isLast) CanopyColors.Muted else CanopyColors.Text)
            }
            Switch(
                checked = config.enabled,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = LocalAccent.current,
                    uncheckedThumbColor = CanopyColors.Muted,
                    uncheckedTrackColor = CanopyColors.Canvas,
                ),
            )
        }
    }
}

private fun BuiltInHomeSection.title(auth: AuthState): String = when (this) {
        BuiltInHomeSection.YOUR_PLAYLISTS -> "Your Playlists"
        BuiltInHomeSection.SUBSCRIBED_ARTISTS -> "Subscribed Artists"
        BuiltInHomeSection.TOP_SONGS -> if (auth is AuthState.SignedIn) "Listening Review" else "Top Songs"
        BuiltInHomeSection.RECOMMENDATIONS -> "Recommendations"
        BuiltInHomeSection.DOWNLOADED_PLAYLISTS -> "Downloaded Playlists"
        BuiltInHomeSection.DOWNLOADED_ARTISTS -> "Downloaded Artists"
        BuiltInHomeSection.DOWNLOADED_ALBUMS -> "Downloaded Albums"
        BuiltInHomeSection.DOWNLOADED_SONGS -> "Downloaded Songs"
    }
