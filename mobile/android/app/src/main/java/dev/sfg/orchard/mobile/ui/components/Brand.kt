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

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.connect.R

@Composable
fun OrchardMark(
    modifier: Modifier = Modifier,
    contentDescription: String? = "Orchard",
) {
    Image(
        painter = painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = contentDescription,
        // The launcher layer keeps the mark inside the adaptive safe zone; in app there is no
        // mask to clear, so it is scaled back up to fill its slot.
        modifier = modifier.graphicsLayer(scaleX = 1.55f, scaleY = 1.55f),
    )
}

@Composable
fun OrchardWordmark(modifier: Modifier = Modifier) {
    Row(modifier) {
        OrchardMark(Modifier.size(28.dp))
        Spacer(Modifier.width(9.dp))
        Text("Orchard", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
    }
}
