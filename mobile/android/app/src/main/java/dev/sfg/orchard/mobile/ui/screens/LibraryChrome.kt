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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.LibraryFilter
import dev.sfg.orchard.mobile.ui.motion.riseIn

/** Text and icons drawn on a [SettingsStyle.Sage] or [SettingsStyle.SageSoft] fill. */
internal val OnSage = Color(0xFF0F1A14)

/** Side gutter shared by every library row. */
internal val LibraryGutter = 16.dp

@Composable
internal fun LibraryHeader(
    summary: String,
    searchOpen: Boolean,
    onToggleSearch: () -> Unit,
    busy: Boolean,
    onCreate: (() -> Unit)?,
    showSearch: Boolean = true,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = LibraryGutter).padding(top = 16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "Library",
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.riseIn(distance = 18f),
            )
            Text(summary, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (showSearch) HeaderButton(
                icon = if (searchOpen) Icons.Rounded.Close else Icons.Rounded.Search,
                label = if (searchOpen) "Close search" else "Search library",
                filled = false,
                onClick = onToggleSearch,
            )
            if (onCreate != null) {
                HeaderButton(Icons.Rounded.Add, "Create", filled = true, busy = busy, onClick = onCreate)
            }
        }
    }
}

@Composable
private fun HeaderButton(icon: ImageVector, label: String, filled: Boolean, busy: Boolean = false, onClick: () -> Unit) {
    val content: @Composable () -> Unit = {
        if (busy) CircularProgressIndicator(Modifier.size(20.dp).semantics { contentDescription = label }, color = LocalContentColor.current, strokeWidth = 2.dp)
        else Icon(icon, contentDescription = label, modifier = Modifier.size(22.dp))
    }
    if (filled) FilledIconButton(onClick = onClick, modifier = Modifier.size(48.dp), content = content)
    else IconButton(onClick = onClick, modifier = Modifier.size(48.dp), content = content)
}

@Composable
internal fun LibraryChips(selected: LibraryFilter, onSelect: (LibraryFilter) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(selected) {
        val index = LibraryFilter.entries.indexOf(selected)
        val visible = listState.layoutInfo.visibleItemsInfo
        val fullyVisible = visible.any { it.index == index && it.offset >= 0 && it.offset + it.size <= listState.layoutInfo.viewportEndOffset }
        if (!fullyVisible) listState.animateScrollToItem(index)
    }
    LazyRow(
        state = listState,
        contentPadding = PaddingValues(start = LibraryGutter, end = LibraryGutter, top = 16.dp, bottom = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(LibraryFilter.entries.size) { index ->
            val filter = LibraryFilter.entries[index]
            LibraryChip(filter.label, filter == selected) { onSelect(filter) }
        }
    }
}

@Composable
private fun LibraryChip(label: String, on: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = on,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
        ),
    )
}

@Composable
internal fun LibraryFilterField(query: String, placeholder: String, autoFocus: Boolean = true, onChange: (String) -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) focus.requestFocus() }
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = Modifier.padding(horizontal = LibraryGutter).padding(top = 12.dp)
            .fillMaxWidth().focusRequester(focus).semantics { contentDescription = placeholder },
        placeholder = { Text(placeholder, maxLines = 1) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, contentDescription = "Clear") }
            }
        },
        singleLine = true,
        shape = MaterialTheme.shapes.extraLarge,
    )
}

/** Section caption above a group of rows, aligned with the row content. */
@Composable
internal fun LibraryLabel(value: String, color: Color = MaterialTheme.colorScheme.primary) {
    Text(
        value,
        style = MaterialTheme.typography.titleSmall,
        color = color,
        modifier = Modifier.padding(start = LibraryGutter, top = 20.dp, bottom = 6.dp).riseIn(distance = 12f),
    )
}

/** Empty shelf: icon tile, message and an optional action. */
@Composable
internal fun LibraryEmpty(
    icon: ImageVector,
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    Column(
        Modifier.padding(horizontal = LibraryGutter).padding(top = 16.dp).fillMaxWidth().clip(SettingsStyle.PanelShape)
            .background(MaterialTheme.colorScheme.surfaceVariant, SettingsStyle.PanelShape)
            .padding(horizontal = 24.dp, vertical = 32.dp)
            .riseIn(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RowIcon(icon)
        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold), color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (actionLabel != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}
