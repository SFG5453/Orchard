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

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.ui.glass.GlassTone
import dev.sfg.orchard.mobile.ui.glass.glassFill
import dev.sfg.orchard.mobile.ui.glass.glassPane
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

val CanopyRadius = RoundedCornerShape(14.dp)
val CanopyRadiusSmall = RoundedCornerShape(10.dp)
val CanopyGutter = 16.dp

@Composable
fun CanopyEyebrow(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = CanopyColors.Eyebrow,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
    )
}

@Composable
fun CanopySectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    OrchardSectionHeader(title = title, modifier = modifier, action = action, onAction = onAction)
}

@Composable
fun CanopyRule(modifier: Modifier = Modifier, strong: Boolean = false) {
    Box(
        modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(if (strong) CanopyColors.RuleStrong else CanopyColors.Rule),
    )
}

@Composable
fun CanopyPanel(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier.glassPane(CanopyRadius),
        color = glassFill(CanopyColors.Surface),
        shape = CanopyRadius,
    ) {
        Column(modifier = Modifier.padding(contentPadding), content = content)
    }
}

@Composable
fun <T> CanopyFilterTabs(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    OrchardFilterChips(
        options = options,
        selected = selected,
        label = label,
        onSelect = onSelect,
        modifier = modifier
    )
}

@Composable
fun CanopyButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
) {
    val containerColor by animateColorAsState(
        if (primary) LocalAccent.current else glassFill(CanopyColors.Surface),
        label = "BtnBg"
    )
    val contentColor by animateColorAsState(
        if (primary) Color.Black else CanopyColors.Text,
        label = "BtnText"
    )

    Surface(
        onClick = onClick,
        color = containerColor,
        shape = CircleShape,
        // The accent fill is the affordance on a primary button; only the quiet one is glass.
        modifier = modifier
            .heightIn(min = 36.dp)
            .then(if (primary) Modifier else Modifier.glassPane(CircleShape, GlassTone.CONTROL)),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                color = contentColor
            )
        }
    }
}
