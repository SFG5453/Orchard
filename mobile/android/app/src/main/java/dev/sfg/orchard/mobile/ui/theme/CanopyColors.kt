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

package dev.sfg.orchard.mobile.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Orchard Mobile theme palette - inspired by SimpMusic visual design.
 * Rich dark AMOLED surfaces, vibrant accents, dynamic color overlays, and semantic tokens.
 */
object CanopyColors {
    /** Main app background (AMOLED dark tone). */
    val Chrome = Color(0xFF0B0E11)

    /** Secondary background / surface container low. */
    val Canvas = Color(0xFF13171F)

    /** Card / container background. */
    val Surface = Color(0xFF1D232D)
    val SurfaceHover = Color(0xFF28303E)
    val ReadoutBg = Color(0xFF222935)

    /** Borders and dividers. */
    val Rule = Color(0xFFFFFFFF).copy(alpha = 0.08f)
    val RuleStrong = Color(0xFFFFFFFF).copy(alpha = 0.18f)

    /** Muted labels and subtitles. */
    val Eyebrow = Color(0xFF9AA7B4)

    /** Primary brand accent (vivid mint green). */
    val Accent = Color(0xFF2FDF93)
    val AccentDeep = Color(0xFF0E6B45)

    /** Secondary brand accent (soft electric blue). */
    val SecondaryAccent = Color(0xFF8ECAE6)

    /** Text colors. */
    val Text = Color(0xFFF3F5F7)
    val Muted = Color(0xFF9AA7B4)
    val MutedStrong = Color(0xFFC5CFD8)

    /** Semantic colors. */
    val Favorite = Color(0xFFFF4081)
    val LyricActive = Color(0xFFFFE500)
    val ShimmerBackground = Color(0x33FFFFFF)
    val ShimmerLine = Color(0x66FFFFFF)

    /** Overlays and backdrop colors. */
    val Overlay = Color(0x40000000)
    val PlayerBackdrop = Color(0xFF121212)

    /**
     * Frosted panes. [Glass] is the film laid over a pane's blurred backdrop, and is nearly white
     * on purpose — the film goes on at a fraction of full opacity, so anything darker just reads
     * as a grey card sitting on the blur rather than as frost in it. [GlassChrome] is the darker
     * film under bars, which have a scrolling list passing behind them to stay legible against.
     */
    val Glass = Color(0xFFE7ECF8)
    val GlassSecondary = Color(0xFFDCE4F0)
    val GlassChrome = Color(0xFF141A24)
    val GlassOverlay = Color(0xFF10141C)

    /** Subtle architectural glass perimeter borders. */
    val GlassBorder = Color(0xFFFFFFFF).copy(alpha = 0.10f)
    val GlassBorderHighlight = Color(0xFFFFFFFF).copy(alpha = 0.22f)

    val Warning = Color(0xFFFFC46B)
    val Danger = Color(0xFFFF746D)
}

