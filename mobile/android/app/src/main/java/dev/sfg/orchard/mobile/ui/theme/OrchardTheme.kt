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

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.ColorUtils
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val OrchardColorScheme = darkColorScheme(
    primary = CanopyColors.Accent,
    onPrimary = Color.Black,
    secondary = CanopyColors.SecondaryAccent,
    onSecondary = Color.Black,
    tertiary = CanopyColors.Favorite,
    background = CanopyColors.Chrome,
    onBackground = CanopyColors.Text,
    surface = CanopyColors.Canvas,
    onSurface = CanopyColors.Text,
    surfaceVariant = CanopyColors.Surface,
    onSurfaceVariant = CanopyColors.Muted,
    outline = CanopyColors.Rule,
    outlineVariant = CanopyColors.RuleStrong,
    error = CanopyColors.Danger,
)

/** Modern expressive rounded corners. */
private val OrchardShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Expressive typography scale inspired by SimpMusic. */
private val OrchardTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
        letterSpacing = (-0.8).sp,
        color = CanopyColors.Text,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.5).sp,
        color = CanopyColors.Text,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        color = CanopyColors.Text,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        color = CanopyColors.Text,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 20.sp,
        color = CanopyColors.Text,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = CanopyColors.Text,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        color = CanopyColors.Text,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = CanopyColors.Muted,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        color = CanopyColors.Muted,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        letterSpacing = 0.2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Bold,
        fontSize = 11.sp,
        letterSpacing = 0.6.sp,
    ),
)

/**
 * The accent in force. Defaults to Orchard's green; becomes the wallpaper's colour when the user
 * opts into system colours. Read this rather than [CanopyColors.Accent] so both paths are honoured.
 */
val LocalAccent = staticCompositionLocalOf { CanopyColors.Accent }

@Composable
fun OrchardTheme(useSystemColors: Boolean = false, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val accent = remember(useSystemColors) {
        if (useSystemColors) {
            dynamicDarkColorScheme(context).primary.legibleOnDarkChrome()
        } else {
            CanopyColors.Accent
        }
    }

    CompositionLocalProvider(LocalAccent provides accent) {
        MaterialTheme(
            colorScheme = OrchardColorScheme.copy(primary = accent, secondary = accent),
            typography = OrchardTypography,
            shapes = OrchardShapes,
            content = content,
        )
    }
}

/**
 * Wallpaper-derived colours are tuned for Material's lighter surfaces and can land too dark or too
 * washed out against Orchard's near-black chrome, so saturation and lightness are held to a range
 * that stays readable.
 */
fun Color.legibleOnDarkChrome(): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    if (hsl[1] < 0.10f) return CanopyColors.Accent
    hsl[1] = hsl[1].coerceIn(0.45f, 0.95f)
    hsl[2] = hsl[2].coerceIn(0.58f, 0.78f)
    return Color(ColorUtils.HSLToColor(hsl))
}

