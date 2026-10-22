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

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.LibraryFilter
import dev.sfg.orchard.mobile.ui.glass.GlassTone
import dev.sfg.orchard.mobile.ui.glass.glassPane
import dev.sfg.orchard.mobile.ui.motion.riseIn

private val EmptyFill = Color(0xA01E2521)

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
                style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
                color = SettingsStyle.Title,
                modifier = Modifier.riseIn(distance = 18f),
            )
            Text(summary, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp), color = SettingsStyle.Description)
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
    val base = Modifier.size(44.dp).clip(CircleShape)
    val surface = if (filled) base.background(SettingsStyle.Sage, CircleShape)
    else base.glassPane(CircleShape, GlassTone.CONTROL).border(1.dp, SettingsStyle.PanelBorder, CircleShape)
    Box(
        surface.clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (filled) OnSage else SettingsStyle.Title
        if (busy) CircularProgressIndicator(Modifier.size(20.dp), color = tint, strokeWidth = 2.dp)
        else Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
    }
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
    val shape = RoundedCornerShape(18.dp)
    val fill by animateColorAsState(if (on) SettingsStyle.SageSoft else Color.Transparent, label = "ChipFill")
    val text by animateColorAsState(if (on) OnSage else SettingsStyle.SegmentIdle, label = "ChipText")
    Box(
        Modifier
            .height(36.dp)
            .clip(shape)
            .glassPane(shape, GlassTone.CONTROL)
            .background(fill, shape)
            .border(1.dp, if (on) Color.Transparent else SettingsStyle.PanelBorder, shape)
            .clickable(role = Role.Tab, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold), color = text)
    }
}

@Composable
internal fun LibraryFilterField(query: String, placeholder: String, autoFocus: Boolean = true, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    val focus = remember { FocusRequester() }
    LaunchedEffect(autoFocus) { if (autoFocus) focus.requestFocus() }
    Row(
        Modifier
            .padding(horizontal = LibraryGutter)
            .padding(top = 12.dp)
            .fillMaxWidth()
            .height(44.dp)
            .clip(shape)
            .glassPane(shape, GlassTone.CONTROL)
            .border(1.dp, SettingsStyle.PanelBorder, shape)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = SettingsStyle.Description, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) {
                Text(placeholder, style = TextStyle(fontSize = 14.sp, color = SettingsStyle.Description), maxLines = 1)
            }
            BasicTextField(
                value = query,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth().focusRequester(focus).semantics { contentDescription = placeholder },
                textStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = SettingsStyle.Title),
                cursorBrush = SolidColor(SettingsStyle.Sage),
                singleLine = true,
            )
        }
        if (query.isNotEmpty()) {
            Box(
                Modifier.size(32.dp).clip(CircleShape).clickable(role = Role.Button) { onChange("") }
                    .semantics { contentDescription = "Clear" },
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, null, tint = SettingsStyle.Title, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/** Section caption above a group of rows, aligned with the row content. */
@Composable
internal fun LibraryLabel(value: String, color: Color = SettingsStyle.Sage) {
    Text(
        value.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp),
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
            .background(EmptyFill, SettingsStyle.PanelShape)
            .border(1.dp, SettingsStyle.PanelBorder, SettingsStyle.PanelShape)
            .padding(horizontal = 24.dp, vertical = 32.dp)
            .riseIn(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        RowIcon(icon)
        Text(title, style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp, fontWeight = FontWeight.SemiBold), color = SettingsStyle.Title, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = SettingsStyle.Description, textAlign = TextAlign.Center)
        if (actionLabel != null) {
            val shape = RoundedCornerShape(14.dp)
            Box(
                Modifier
                    .padding(top = 6.dp)
                    .height(44.dp)
                    .clip(shape)
                    .background(SettingsStyle.Sage.copy(alpha = 0.18f), shape)
                    .border(1.dp, SettingsStyle.Sage.copy(alpha = 0.5f), shape)
                    .clickable(role = Role.Button, onClick = onAction)
                    .padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = SettingsStyle.SageSoft)
            }
        }
    }
}
