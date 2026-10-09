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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.LibraryFilter
import dev.sfg.orchard.mobile.ui.glass.GlassTone
import dev.sfg.orchard.mobile.ui.glass.glassPane
import dev.sfg.orchard.mobile.ui.motion.riseIn

private val SegmentFill = Color(0xA01E2521)
private val SegmentRadius = 20.dp

@Composable
internal fun LibraryHeader(summary: String) {
    Column(Modifier.padding(horizontal = 16.dp).padding(top = 16.dp)) {
        Text(
            "Library",
            style = MaterialTheme.typography.displayLarge.copy(fontWeight = FontWeight.Bold),
            color = SettingsStyle.Title,
            modifier = Modifier.riseIn(distance = 18f),
        )
        Text(
            summary,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
            color = SettingsStyle.Description,
        )
    }
}

@Composable
internal fun LibraryChips(selected: LibraryFilter, onSelect: (LibraryFilter) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(LibraryFilter.entries.size) { index ->
            val filter = LibraryFilter.entries[index]
            val on = filter == selected
            val shape = RoundedCornerShape(18.dp)
            Box(
                Modifier
                    .height(36.dp)
                    .clip(shape)
                    .glassPane(shape, GlassTone.CONTROL)
                    .border(1.dp, if (on) SettingsStyle.Sage.copy(alpha = 0.5f) else SettingsStyle.PanelBorder, shape)
                    .clickable { onSelect(filter) }
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    filter.label,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.SemiBold),
                    color = if (on) SettingsStyle.SageSoft else SettingsStyle.SegmentIdle,
                )
            }
        }
    }
}

@Composable
internal fun LibraryFilterField(query: String, placeholder: String, onChange: (String) -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Row(
        Modifier
            .padding(horizontal = 16.dp)
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
                modifier = Modifier.fillMaxWidth(),
                textStyle = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium, color = SettingsStyle.Title),
                cursorBrush = SolidColor(SettingsStyle.Sage),
                singleLine = true,
            )
        }
        if (query.isNotEmpty()) {
            Box(Modifier.size(28.dp).clip(CircleShape).clickable { onChange("") }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, "Clear", tint = SettingsStyle.Title, modifier = Modifier.size(14.dp))
            }
        }
    }
}

/**
 * One row of a list panel. A lazy list cannot wrap its items in a single surface, so the first
 * and last rows carry the panel's rounded ends and the rows between stay square.
 */
internal fun Modifier.panelSegment(first: Boolean, last: Boolean): Modifier {
    val top = if (first) SegmentRadius else 0.dp
    val bottom = if (last) SegmentRadius else 0.dp
    val shape = RoundedCornerShape(topStart = top, topEnd = top, bottomStart = bottom, bottomEnd = bottom)
    return this
        .padding(horizontal = 16.dp)
        .clip(shape)
        .background(SegmentFill, shape)
        .border(1.dp, SettingsStyle.PanelBorder, shape)
}

/** Section caption above a panel, aligned with the panel edge. */
@Composable
internal fun LibraryLabel(value: String, color: Color = SettingsStyle.Sage) {
    Text(
        value.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.9.sp),
        color = color,
        modifier = Modifier.padding(start = 22.dp, top = 20.dp, bottom = 8.dp).riseIn(distance = 12f),
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
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().clip(SettingsStyle.PanelShape)
            .background(SegmentFill, SettingsStyle.PanelShape)
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
                    .clickable(onClick = onAction)
                    .padding(horizontal = 22.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(actionLabel, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.SemiBold), color = SettingsStyle.SageSoft)
            }
        }
    }
}
