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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.automirrored.rounded.ViewList
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

internal enum class LibrarySort(val label: String) {
    ADDED("Recently added"),
    TITLE("A to Z"),
}

/** Count on the left; sort menu and grid or list toggle on the right when given. */
@Composable
internal fun LibraryToolbar(
    count: String,
    sort: LibrarySort? = null,
    onSort: (LibrarySort) -> Unit = {},
    grid: Boolean? = null,
    onGrid: (Boolean) -> Unit = {},
) {
    Row(
        Modifier.fillMaxWidth().padding(start = LibraryGutter, end = LibraryGutter, top = 14.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            count,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (sort != null) SortButton(sort, onSort)
        if (grid != null) ViewToggle(grid, onGrid)
    }
}

@Composable
private fun SortButton(sort: LibrarySort, onSort: (LibrarySort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .clickable(role = Role.Button, onClickLabel = "Change sort order") { open = true }
                .padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.AutoMirrored.Rounded.Sort, null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(16.dp))
            Text(sort.label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            LibrarySort.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    trailingIcon = { if (option == sort) Icon(Icons.Rounded.Check, null, Modifier.size(18.dp)) },
                    onClick = { open = false; onSort(option) },
                )
            }
        }
    }
}

@Composable
private fun ViewToggle(grid: Boolean, onGrid: (Boolean) -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        ToggleSegment(Icons.Rounded.GridView, "Grid view", grid) { onGrid(true) }
        ToggleSegment(Icons.AutoMirrored.Rounded.ViewList, "List view", !grid) { onGrid(false) }
    }
}

@Composable
private fun ToggleSegment(icon: ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .size(width = 40.dp, height = 36.dp)
            .clip(shape)
            .background(if (on) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = label; selected = on },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
    }
}

/** Play and Shuffle for the visible song list. */
@Composable
internal fun LibraryPlayButtons(onPlay: () -> Unit, onShuffle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = LibraryGutter).padding(top = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PillButton("Play", Icons.Rounded.PlayArrow, filled = true, onClick = onPlay, modifier = Modifier.weight(1f))
        PillButton("Shuffle", Icons.Rounded.Shuffle, filled = false, onClick = onShuffle, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun PillButton(label: String, icon: ImageVector, filled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val content: @Composable () -> Unit = {
        Icon(icon, null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
    if (filled) Button(onClick = onClick, modifier = modifier.height(48.dp)) { content() }
    else OutlinedButton(onClick = onClick, modifier = modifier.height(48.dp)) { content() }
}

/** Liked songs and Downloads tiles above the playlist grid. */
@Composable
internal fun LibraryShortcuts(likedCount: Int, downloadedCount: Int, onLiked: () -> Unit, onDownloads: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = LibraryGutter).padding(top = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ShortcutTile(
            "Liked songs", songCountLabel(likedCount), Icons.Rounded.Favorite,
            onLiked, Modifier.weight(1f),
        )
        ShortcutTile(
            "Downloads", if (downloadedCount == 0) "None yet" else "${songCountLabel(downloadedCount)} offline", Icons.Rounded.DownloadDone,
            onDownloads, Modifier.weight(1f),
        )
    }
}

@Composable
private fun ShortcutTile(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier
            .height(60.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceVariant, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 8.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

internal fun songCountLabel(count: Int): String = if (count == 1) "1 song" else "%,d songs".format(count)

internal fun countLabel(count: Int, one: String, many: String): String =
    if (count == 1) "1 $one" else "%,d $many".format(count)

