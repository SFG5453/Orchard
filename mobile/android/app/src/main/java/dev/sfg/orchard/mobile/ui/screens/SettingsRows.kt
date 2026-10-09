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

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Headphones
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.ui.draw.alpha
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.model.AudioQuality
import dev.sfg.orchard.mobile.model.LocalQobuzLinked
import dev.sfg.orchard.mobile.model.NonMusicSkipMode
import dev.sfg.orchard.mobile.ui.motion.popOnChange
import dev.sfg.orchard.mobile.ui.motion.riseIn
import dev.sfg.orchard.mobile.ui.theme.CanopyColors

/** Small caps caption above a panel. */
@Composable
internal fun SectionLabel(value: String, index: Int = 0) {
    Text(
        value.uppercase(),
        style = MaterialTheme.typography.labelMedium.copy(
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.9.sp,
        ),
        color = SettingsStyle.Caption,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(start = 6.dp, top = 26.dp, bottom = 8.dp).riseIn(index, distance = 14f),
    )
}

/** Translucent panel; rows inside separate themselves with [PanelDivider]. */
@Composable
internal fun SettingsPanel(index: Int = 0, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        color = SettingsStyle.Panel,
        shape = SettingsStyle.PanelShape,
        border = BorderStroke(1.dp, SettingsStyle.PanelBorder),
        modifier = Modifier.fillMaxWidth().riseIn(index),
    ) {
        Column(content = content)
    }
}

/** Hairline between rows in the same panel. */
@Composable
internal fun PanelDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp)
            .height(1.dp)
            .background(SettingsStyle.Divider),
    )
}

/** Icon in a neutral rounded tile; every row shares it so the left edge lines up. */
@Composable
internal fun RowIcon(icon: ImageVector, tint: Color = SettingsStyle.TileIcon, fill: Color = SettingsStyle.Tile) {
    Box(
        modifier = Modifier
            .size(36.dp)
            .background(fill, RoundedCornerShape(10.dp))
            .border(1.dp, SettingsStyle.TileBorder, RoundedCornerShape(10.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
    }
}

@Composable
internal fun RowTitle(text: String, enabled: Boolean = true, color: Color = SettingsStyle.Title) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, fontWeight = FontWeight.Medium),
        color = if (enabled) color else SettingsStyle.Caption,
    )
}

@Composable
internal fun RowSubtitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp),
        color = SettingsStyle.Description,
        modifier = Modifier.padding(top = 3.dp),
    )
}

/** Icon tile, title and description on the left, [trailing] control on the right. */
@Composable
internal fun SettingsRow(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    titleColor: Color = SettingsStyle.Title,
    iconTint: Color = SettingsStyle.TileIcon,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 68.dp)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) RowIcon(icon, tint = iconTint)
        Column(Modifier.weight(1f)) {
            RowTitle(title, enabled, titleColor)
            if (subtitle.isNotEmpty()) RowSubtitle(subtitle)
        }
        trailing()
    }
}

@Composable
internal fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChecked: (Boolean) -> Unit,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    SettingsRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        enabled = enabled,
        // Tapping anywhere on the row toggles, not just the switch.
        modifier = Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChecked),
    ) {
        SettingsSwitch(
            checked = checked,
            onCheckedChange = null,
            enabled = enabled,
            modifier = Modifier.popOnChange(checked, peak = 1.12f),
        )
    }
}

@Composable
internal fun ActionRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    value: String? = null,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    // The chevron leans toward where the tap is about to take you.
    val nudge by animateDpAsState(
        if (pressed) 6.dp else 0.dp,
        spring(dampingRatio = 0.4f, stiffness = 700f),
        label = "ChevronNudge",
    )
    SettingsRow(
        title = title,
        subtitle = subtitle,
        icon = icon,
        modifier = Modifier.clickable(interactionSource = source, indication = ripple(), onClick = onClick),
    ) {
        if (value != null) {
            Text(value, color = SettingsStyle.SageSoft, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp))
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = SettingsStyle.Chevron,
            modifier = Modifier.offset { IntOffset(nudge.roundToPx(), 0) }.size(20.dp),
        )
    }
}

/** Single-choice pill; the selected segment is a lighter glass chip. */
@Composable
internal fun <T> SettingsSegmented(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    isEnabled: (T) -> Boolean = { true },
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(SettingsStyle.Tile, CircleShape)
            .border(1.dp, SettingsStyle.TileBorder, CircleShape)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            val optionEnabled = isEnabled(value)
            Box(
                Modifier
                    .weight(1f)
                    .height(38.dp)
                    .background(if (isSelected) SettingsStyle.Selected else Color.Transparent, CircleShape)
                    .clickable(enabled = optionEnabled, role = Role.RadioButton) { onSelect(value) }
                    .alpha(if (optionEnabled) 1f else 0.4f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge.copy(fontSize = 13.sp, fontWeight = FontWeight.Medium),
                    color = if (isSelected) SettingsStyle.Title else SettingsStyle.SegmentIdle,
                )
            }
        }
    }
}

/** Neutral outlined pill; [destructive] swaps the label to the danger colour. */
@Composable
internal fun SettingsPill(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    destructive: Boolean = false,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = SettingsStyle.ButtonFill,
        border = BorderStroke(1.dp, SettingsStyle.ButtonBorder),
        modifier = modifier.defaultMinSize(minHeight = 44.dp),
    ) {
        Box(Modifier.padding(horizontal = 18.dp), contentAlignment = Alignment.Center) {
            Text(
                label,
                style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = FontWeight.Medium),
                color = if (destructive) CanopyColors.Danger else SettingsStyle.Title,
            )
        }
    }
}

/** Audio quality as one segmented control with the active tier described above it. */
@Composable
internal fun QualityRow(stored: AudioQuality, onChange: (AudioQuality) -> Unit) {
    val linked = LocalQobuzLinked.current
    // Without a subscription MAX cannot play, so the row shows what actually streams.
    val value = if (stored == AudioQuality.MAX && !linked) AudioQuality.HIGH else stored
    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RowIcon(Icons.Rounded.Headphones)
            Column {
                RowTitle("Audio quality")
                RowSubtitle(
                    value.description + if (!linked) ". Max needs a linked Qobuz account." else "",
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        SettingsSegmented(
            options = AudioQuality.entries.map { it to it.label },
            selected = value,
            onSelect = onChange,
            isEnabled = { it != AudioQuality.MAX || linked },
        )
    }
}

/** What to do about talking intros, skits and applause, as one segmented control. */
@Composable
internal fun NonMusicSkipRow(value: NonMusicSkipMode, onChange: (NonMusicSkipMode) -> Unit) {
    Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            RowIcon(Icons.Rounded.SkipNext)
            Column {
                RowTitle("Non-music parts")
                RowSubtitle(
                    when (value) {
                        NonMusicSkipMode.OFF -> "Songs play through, talking and all"
                        NonMusicSkipMode.BUTTON -> "Show a Skip button over talking intros, skits and applause"
                        NonMusicSkipMode.AUTO -> "Skip talking intros, skits and applause automatically"
                    } + ". Sections come from SponsorBlock volunteers; lyrics stay in sync because skipping just seeks.",
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        SettingsSegmented(
            options = listOf(
                NonMusicSkipMode.OFF to "Off",
                NonMusicSkipMode.BUTTON to "Button",
                NonMusicSkipMode.AUTO to "Auto skip",
            ),
            selected = value,
            onSelect = onChange,
        )
    }
}

internal val AudioQuality.label: String
    get() = when (this) { AudioQuality.DATA_SAVER -> "Saver"; AudioQuality.NORMAL -> "Normal"; AudioQuality.HIGH -> "High"; AudioQuality.MAX -> "Max" }

private val AudioQuality.description: String
    get() = when (this) { AudioQuality.DATA_SAVER -> "Uses the least data"; AudioQuality.NORMAL -> "Balanced quality and data"; AudioQuality.HIGH -> "Best quality, more data"; AudioQuality.MAX -> "Lossless and Hi-Res from Qobuz. Adaptive mix and the equalizer are off" }
