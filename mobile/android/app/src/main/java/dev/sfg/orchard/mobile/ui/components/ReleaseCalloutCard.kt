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

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.ReleaseCallout
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

/** Announcement above the release notes, with one link. */
@Composable
internal fun ReleaseCalloutCard(callout: ReleaseCallout) {
    val uriHandler = LocalUriHandler.current
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = LocalAccent.current.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, LocalAccent.current.copy(alpha = 0.34f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                callout.title,
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = CanopyColors.Text,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                callout.text,
                style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 19.sp),
                color = CanopyColors.MutedStrong,
            )
            Spacer(Modifier.height(10.dp))
            Button(
                // openUri throws when no app handles the link.
                onClick = { runCatching { uriHandler.openUri(callout.url) } },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = LocalAccent.current, contentColor = Color.Black),
            ) {
                Text(callout.action, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
            }
        }
    }
}
