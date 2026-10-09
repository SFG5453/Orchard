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

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.*
import dev.sfg.orchard.mobile.model.LyricLine
import kotlinx.coroutines.flow.first

internal sealed interface LyricDisplayItem {
    val key: String

    data class Line(
        val line: LyricLine,
        val originalIndex: Int,
    ) : LyricDisplayItem {
        override val key: String get() = "line-$originalIndex"
    }

    data class Pause(
        val afterLineIndex: Int,
        val startMs: Long,
        val endMs: Long,
    ) : LyricDisplayItem {
        override val key: String get() = "pause-$afterLineIndex"
    }
}

private const val LYRIC_PAUSE_MIN_MS = 7_000L
private const val LYRIC_PAUSE_LINE_TAIL_ACCURATE_MS = 400L
private const val LYRIC_PAUSE_LINE_TAIL_FALLBACK_MS = 2_400L

internal fun lyricPauseWindow(line: LyricLine, nextLine: LyricLine): Pair<Long, Long>? {
    val lineStart = line.startMs ?: return null
    val nextStart = nextLine.startMs ?: return null

    val gapLength = nextStart - lineStart
    if (gapLength < LYRIC_PAUSE_MIN_MS) return null

    val (lineEnd, hasAccurateEnd) = when {
        line.endMs != null && line.endMs > lineStart -> line.endMs to true
        line.words.isNotEmpty() -> {
            val lastWord = line.words.last()
            if (lastWord.endMs != null && lastWord.endMs > lineStart) {
                lastWord.endMs to true
            } else if (lastWord.startMs > lineStart) {
                (lastWord.startMs + 400L) to true
            } else {
                lineStart to false
            }
        }
        else -> lineStart to false
    }

    val tail = if (hasAccurateEnd) LYRIC_PAUSE_LINE_TAIL_ACCURATE_MS else LYRIC_PAUSE_LINE_TAIL_FALLBACK_MS
    val pauseStart = lineEnd + tail
    val pauseEnd = nextStart
    if (pauseStart >= pauseEnd) return null

    return pauseStart to pauseEnd
}

internal fun buildLyricDisplayItems(lines: List<LyricLine>): List<LyricDisplayItem> = buildList {
    lines.forEachIndexed { index, line ->
        add(LyricDisplayItem.Line(line, index))
        val nextLine = lines.getOrNull(index + 1)
        if (nextLine != null) {
            val pause = lyricPauseWindow(line, nextLine)
            if (pause != null) {
                add(LyricDisplayItem.Pause(index, pause.first, pause.second))
            }
        }
    }
}
