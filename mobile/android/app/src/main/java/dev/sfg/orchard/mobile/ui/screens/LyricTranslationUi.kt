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

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sfg.orchard.mobile.OrchardGraph
import dev.sfg.orchard.mobile.lyrics.translation.LyricTranslationState
import dev.sfg.orchard.mobile.lyrics.translation.LyricTranslationState.Status
import dev.sfg.orchard.mobile.model.LyricLine

/** Registers [lines] as on screen, which is what lets the translator spend CPU on them. */
@Composable
internal fun rememberLyricTranslation(lines: List<LyricLine>): LyricTranslationState {
    val translator = OrchardGraph.from(LocalContext.current).lyricTranslation
    DisposableEffect(translator, lines) {
        translator.show(lines)
        onDispose { translator.hide(lines) }
    }
    val state by translator.state.collectAsStateWithLifecycle()
    return state
}

/** The translation as the main line, with the original small beneath it like an adlib. */
@Composable
internal fun TranslatedLyricLine(
    line: LyricLine,
    translation: String,
    synced: Boolean,
    active: Boolean,
    isAlternate: Boolean,
    accent: Color,
    positionMs: State<Float>,
) {
    val align = if (isAlternate) TextAlign.End else TextAlign.Start
    Text(
        text = translation,
        color = Color.White,
        textAlign = align,
        modifier = Modifier.fillMaxWidth(),
        style = (if (synced) lyricTextStyle() else unsyncedLyricStyle()).copy(
            shadow = if (active) Shadow(accent.copy(alpha = 0.4f), blurRadius = 24f) else null,
        ),
    )
    if (line.words.isNotEmpty()) {
        TimedWords(
            words = line.words,
            positionMs = positionMs,
            lineActive = active,
            adlib = true,
            isAlternate = isAlternate,
            accent = accent,
        )
    } else {
        Text(
            text = line.text,
            color = Color.White.copy(alpha = 0.72f),
            textAlign = align,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            style = if (synced) lyricTextStyle(adlib = true) else unsyncedLyricStyle().copy(fontSize = 14.sp, lineHeight = 20.sp),
        )
    }
}

/** Translate toggle with a short status caption; tapping a failure retries. */
@Composable
internal fun LyricTranslateChip(state: LyricTranslationState, modifier: Modifier = Modifier) {
    val graph = OrchardGraph.from(LocalContext.current)
    val settings by graph.settings.settings.collectAsStateWithLifecycle()
    val enabled = settings.translateLyrics
    val caption = when (state.status) {
        Status.OFF, Status.IDLE -> null
        // Same wording as desktop's LyricsTranslateBar.
        Status.DOWNLOADING -> "${state.message} ${(state.progress * 100).toInt()}%"
        Status.UNSUPPORTED, Status.TRANSLATING, Status.READY -> state.message
        Status.FAILED -> state.message.ifBlank { "Translation failed" }
    }.takeIf { enabled }
    val shape = RoundedCornerShape(999.dp)
    Row(
        modifier = modifier
            .background(Color(0xFF1E1E24).copy(alpha = if (enabled) 0.82f else 0.55f), shape)
            .border(1.dp, Color.White.copy(alpha = if (enabled) 0.18f else 0.10f), shape)
            .clickable {
                if (enabled && state.status == Status.FAILED) graph.lyricTranslation.retry()
                else graph.settings.updateSettings(settings.copy(translateLyrics = !enabled))
            }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            imageVector = Icons.Rounded.Translate,
            contentDescription = if (enabled) "Stop translating lyrics" else "Translate lyrics to English",
            tint = Color.White.copy(alpha = if (enabled) 0.95f else 0.6f),
            modifier = Modifier.size(16.dp),
        )
        AnimatedVisibility(
            visible = caption != null,
            enter = fadeIn() + expandHorizontally(),
            exit = fadeOut() + shrinkHorizontally(),
        ) {
            Text(
                text = caption.orEmpty(),
                color = Color.White.copy(alpha = 0.82f),
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
            )
        }
    }
}
