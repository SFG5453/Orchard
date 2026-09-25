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

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.ui.glass.glassFill
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

/**
 * Modal bottom sheet displaying all items of a given section in a grid format.
 * If [browseId] and [onFetchFullItems] are supplied, dynamically fetches the complete
 * collection of items for this section from YouTube Music.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistSectionBottomSheet(
    title: String,
    initialItems: List<CatalogItem>,
    browseId: String = "",
    params: String = "",
    onFetchFullItems: (suspend (String, String) -> List<CatalogItem>)? = null,
    onPlay: ((Track) -> Unit)? = null,
    onPlayItem: ((CatalogItem) -> Unit)? = null,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var displayItems by remember(initialItems) { mutableStateOf(initialItems) }
    var isLoadingFull by remember { mutableStateOf(browseId.isNotBlank() && onFetchFullItems != null) }
    var searchQuery by remember { mutableStateOf("") }
    var showSearchBar by remember { mutableStateOf(false) }

    if (browseId.isNotBlank() && onFetchFullItems != null) {
        LaunchedEffect(browseId, params) {
            isLoadingFull = true
            val fullList = runCatching { onFetchFullItems(browseId, params) }.getOrDefault(emptyList())
            if (fullList.isNotEmpty()) {
                displayItems = fullList
            }
            isLoadingFull = false
        }
    }

    val filteredItems = remember(displayItems, searchQuery) {
        if (searchQuery.isBlank()) displayItems
        else {
            val q = searchQuery.trim().lowercase()
            displayItems.filter { item ->
                item.title.lowercase().contains(q)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = CanopyColors.Chrome,
        dragHandle = { BottomSheetDefaults.DragHandle(color = CanopyColors.Muted.copy(alpha = 0.4f)) },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp,
                        ),
                        color = CanopyColors.Text,
                    )
                    if (displayItems.isNotEmpty()) {
                        Text(
                            text = "${displayItems.size} items",
                            style = MaterialTheme.typography.labelMedium,
                            color = CanopyColors.Muted,
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isLoadingFull) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = LocalAccent.current,
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                    if (displayItems.size > 8) {
                        IconButton(
                            onClick = {
                                showSearchBar = !showSearchBar
                                if (!showSearchBar) searchQuery = ""
                            },
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                if (showSearchBar) Icons.Rounded.Close else Icons.Rounded.Search,
                                contentDescription = "Search in section",
                                tint = CanopyColors.Muted,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            if (showSearchBar) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("Filter items in $title...", color = CanopyColors.Muted) },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = glassFill(CanopyColors.Surface),
                        unfocusedContainerColor = glassFill(CanopyColors.Surface),
                        focusedBorderColor = LocalAccent.current.copy(alpha = 0.6f),
                        unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
                        focusedTextColor = CanopyColors.Text,
                        unfocusedTextColor = CanopyColors.Text,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 14.dp),
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 140.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                contentPadding = PaddingValues(bottom = 36.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                itemsIndexed(filteredItems, key = { index, it -> "${it.stableId}_$index" }) { _, item ->
                    CatalogCard(
                        item = item,
                        onClick = {
                            if (item is CatalogItem.Song && onPlay != null) {
                                onPlay(item.track)
                            } else {
                                onDismiss()
                                onOpen(item.stableId)
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
fun CatalogSectionBottomSheet(
    title: String,
    initialItems: List<CatalogItem>,
    browseId: String = "",
    params: String = "",
    onFetchFullItems: (suspend (String, String) -> List<CatalogItem>)? = null,
    onPlay: ((Track) -> Unit)? = null,
    onPlayItem: ((CatalogItem) -> Unit)? = null,
    onOpen: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ArtistSectionBottomSheet(
        title = title,
        initialItems = initialItems,
        browseId = browseId,
        params = params,
        onFetchFullItems = onFetchFullItems,
        onPlay = onPlay,
        onPlayItem = onPlayItem,
        onOpen = onOpen,
        onDismiss = onDismiss,
    )
}
