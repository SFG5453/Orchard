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
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderColors
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

/** Settings palette, shared with the desktop settings dialog so both read as one app. */
internal object SettingsStyle {
    val Panel = Color(0x0AFFFFFF)
    val PanelBorder = Color(0x1CFFFFFF)
    val Divider = Color(0x12FFFFFF)
    val Tile = Color(0x12FFFFFF)
    val TileBorder = Color(0x10FFFFFF)
    val TileIcon = Color(0xFFA8CDB6)
    val Title = Color(0xFFF0EEE7)
    val Description = Color(0xFFA3ADA5)
    val Caption = Color(0xFF8D968E)
    val Chevron = Color(0xFF7C857F)

    /** Text and control accent; lighter than the switch fill so it holds up on the warped cover. */
    val Sage = Color(0xFF8CC4A0)
    val SageSoft = Color(0xFFC4E0CB)
    val SwitchOn = Color(0xFF5F9A76)
    val SwitchOff = Color(0x2CFFFFFF)
    val ThumbOff = Color(0xFFE4E9E6)
    val Selected = Color(0x2CFFFFFF)
    val SegmentIdle = Color(0xFFB4BEB9)
    val ButtonFill = Color(0x0FFFFFFF)
    val ButtonBorder = Color(0x26FFFFFF)

    val PanelShape = RoundedCornerShape(16.dp)
}

/**
 * 40x24 pill switch. With a null [onCheckedChange] it is display-only, for rows that toggle on
 * tap of the whole row.
 */
@Composable
internal fun SettingsSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val thumbX by animateDpAsState(
        if (checked) 19.dp else 3.dp,
        spring(dampingRatio = 0.55f, stiffness = 500f),
        label = "SwitchThumb",
    )
    val track by animateColorAsState(
        if (checked) SettingsStyle.SwitchOn else SettingsStyle.SwitchOff,
        tween(180),
        label = "SwitchTrack",
    )
    val hit = if (onCheckedChange != null) {
        // Touch slop around the 24dp visual, since 24dp alone is a tap lottery.
        Modifier.minimumInteractiveComponentSize()
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
    } else {
        Modifier
    }
    Box(modifier.then(hit).alpha(if (enabled) 1f else 0.45f), contentAlignment = androidx.compose.ui.Alignment.Center) {
        Box(Modifier.size(40.dp, 24.dp).clip(CircleShape).background(track)) {
            Box(
                Modifier
                    .offset(x = thumbX, y = 3.dp)
                    .size(18.dp)
                    .clip(CircleShape)
                    .background(if (checked) Color.White else SettingsStyle.ThumbOff),
            )
        }
    }
}

/** Slider tinted like the switch; ticks hidden so stepped sliders still read as continuous. */
@Composable
internal fun settingsSliderColors(): SliderColors = SliderDefaults.colors(
    thumbColor = SettingsStyle.SageSoft,
    activeTrackColor = SettingsStyle.SwitchOn,
    inactiveTrackColor = SettingsStyle.SwitchOff,
    activeTickColor = Color.Transparent,
    inactiveTickColor = Color.Transparent,
)
