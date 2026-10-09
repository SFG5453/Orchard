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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.sfg.orchard.mobile.model.LoadState
import dev.sfg.orchard.mobile.model.LyricLine
import dev.sfg.orchard.mobile.ui.components.LocalPlayerClock

/**
 * The line being sung, under the cover. Tapping it opens the full lyrics. Recomposes per line,
 * never per tick: the index is derived from the clock and only changes when the line does.
 */
@Composable
internal fun LyricPeek(
    lyrics: LoadState<List<LyricLine>>,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val lines = (lyrics as? LoadState.Content)?.value ?: return
    val synced = remember(lines) { lines.any { it.startMs != null } }
    if (!synced) return
    val clock = LocalPlayerClock.current
    val text by remember(lines) {
        derivedStateOf {
            val position = clock.reported().positionMs + LYRIC_LINE_LEAD_MS.toLong()
            val line = lines.lastOrNull { it.startMs != null && it.startMs <= position }
            val lapsed = line?.endMs?.let { position > it + INSTRUMENTAL_GRACE_MS } ?: false
            line?.text?.takeIf { it.isNotBlank() && !lapsed } ?: INSTRUMENTAL
        }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClickLabel = "Show lyrics", onClick = onOpen)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = text,
            transitionSpec = {
                (fadeIn(tween(260)) + slideInVertically(tween(260)) { it / 2 })
                    .togetherWith(fadeOut(tween(180)) + slideOutVertically(tween(180)) { -it / 2 })
            },
            label = "LyricPeekLine",
        ) { line ->
            Text(
                text = line,
                color = Color.White.copy(alpha = if (line == INSTRUMENTAL) 0.40f else 0.72f),
                style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

// The universal lyric for "the drummer is having a moment".
private const val INSTRUMENTAL = "♪"

/** A line outlives its end stamp this long before the peek falls back to the note. */
private const val INSTRUMENTAL_GRACE_MS = 1_500L
