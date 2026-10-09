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

package dev.sfg.orchard.mobile.ui.glass

import androidx.compose.ui.graphics.Color
import dev.sfg.orchard.mobile.ui.theme.CanopyColors

/**
 * Per-tone constants. The `solid` variants are the ones used when no backdrop was captured, where
 * the frost has to carry the whole pane on its own.
 */
internal class GlassSpec(
    val base: Color,
    val film: Float,
    val tintMix: Float,
    val contrastUndercoat: Float,
    val solidBase: Color,
    val solidFilm: Float,
    val solidTintMix: Float,
    val solidUndercoat: Float,
)

private val PanelSpec =
    GlassSpec(
        base = CanopyColors.Glass,
        film = 0.14f,
        tintMix = 0.06f,
        contrastUndercoat = 0.12f,
        solidBase = CanopyColors.Surface,
        solidFilm = 0.94f,
        solidTintMix = 0.03f,
        solidUndercoat = 0.0f,
    )

private val SecondarySpec =
    GlassSpec(
        base = CanopyColors.Glass,
        film = 0.18f,
        tintMix = 0.05f,
        contrastUndercoat = 0.18f,
        solidBase = CanopyColors.SurfaceHover,
        solidFilm = 0.95f,
        solidTintMix = 0.02f,
        solidUndercoat = 0.0f,
    )

private val ChromeSpec =
    GlassSpec(
        base = CanopyColors.GlassChrome,
        film = 0.18f,
        tintMix = 0.07f,
        contrastUndercoat = 0.18f,
        solidBase = CanopyColors.Chrome,
        solidFilm = 0.98f,
        solidTintMix = 0.04f,
        solidUndercoat = 0.0f,
    )

private val OverlaySpec =
    GlassSpec(
        base = CanopyColors.GlassChrome,
        film = 0.74f,
        tintMix = 0.05f,
        contrastUndercoat = 0.58f,
        solidBase = CanopyColors.Chrome,
        solidFilm = 0.98f,
        solidTintMix = 0.03f,
        solidUndercoat = 0.0f,
    )

private val ControlSpec =
    GlassSpec(
        base = CanopyColors.Glass,
        film = 0.16f,
        tintMix = 0.05f,
        contrastUndercoat = 0.14f,
        solidBase = CanopyColors.Surface,
        solidFilm = 0.92f,
        solidTintMix = 0.03f,
        solidUndercoat = 0.0f,
    )

internal fun GlassTone.spec(): GlassSpec =
    when (this) {
        GlassTone.PANEL -> PanelSpec
        GlassTone.SECONDARY -> SecondarySpec
        GlassTone.CHROME -> ChromeSpec
        GlassTone.OVERLAY -> OverlaySpec
        GlassTone.CONTROL -> ControlSpec
    }
