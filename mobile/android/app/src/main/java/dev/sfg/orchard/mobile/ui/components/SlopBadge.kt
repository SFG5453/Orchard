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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sfg.orchard.mobile.OrchardGraph
import dev.sfg.orchard.mobile.playback.slop.SLOP_THRESHOLD
import dev.sfg.orchard.mobile.playback.slop.SlopAction

@Composable
internal fun SlopBadge(trackId: String) {
    val graph = OrchardGraph.from(LocalContext.current)
    val probabilities by graph.slopVerdicts.probabilities.collectAsStateWithLifecycle()
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val probability = probabilities[trackId] ?: return
    if (settings.slopAction == SlopAction.OFF || probability < SLOP_THRESHOLD) return
    Text("AI", style = MaterialTheme.typography.labelSmall, color = Color(0xFFFFC58A),
        modifier = Modifier.padding(start = 6.dp)
            .background(Color(0xFFFFC58A).copy(alpha = 0.15f), RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
            .semantics { contentDescription = "Likely AI-generated, ${(probability * 100).toInt()} percent confidence" })
}
