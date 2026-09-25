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

package dev.sfg.orchard.mobile.ui.foldable

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Breakpoint in dp where phones transition to foldable (unfolded) or tablet UI. */
const val FOLDABLE_MIN_WIDTH_DP = 600

/** Width reserved for the vertical navigation rail on foldable and tablet screens. */
val FoldableNavRailWidth: Dp = 80.dp

/** Chrome bottom padding on foldables where only the mini-player floats without a bottom bar. */
val FoldableChromeHeight: Dp = 84.dp

/** Returns whether the current device viewport qualifies as foldable unfolded or tablet. */
@Composable
fun isFoldableOrWideLayout(): Boolean {
    val config = LocalConfiguration.current
    return config.screenWidthDp >= FOLDABLE_MIN_WIDTH_DP
}

/** Returns whether the underlying physical hardware is a book-style foldable device (excludes clamshell/flip phones). */
@Composable
fun isFoldableDevice(): Boolean {
    val context = androidx.compose.ui.platform.LocalContext.current
    return androidx.compose.runtime.remember(context) {
        val isFlip = android.os.Build.MODEL.contains("Flip", ignoreCase = true) ||
            android.os.Build.DEVICE.contains("flip", ignoreCase = true)
        if (isFlip) return@remember false

        context.packageManager.hasSystemFeature("android.hardware.sensor.hinge_angle") ||
            android.os.Build.MODEL.contains("Fold", ignoreCase = true) ||
            android.os.Build.DEVICE.contains("fold", ignoreCase = true)
    }
}

/**
 * Returns true if the device is a book-style foldable unfolded in its large inner display
 * (e.g. Pixel Fold, Galaxy Z Fold), or displaying a square-ish aspect ratio typical of foldables.
 * Standard widescreen tablets (16:10, 16:9) and clamshell flip phones return false.
 */
@Composable
fun isFoldableActive(): Boolean {
    val config = LocalConfiguration.current
    val minDim = minOf(config.screenWidthDp, config.screenHeightDp)
    val maxDim = maxOf(config.screenWidthDp, config.screenHeightDp)

    // A book foldable inner screen must have both dimensions large (at least 580dp)
    // and an aspect ratio near square (typically 1.0 to 1.25, <= 1.28).
    // Clamshell flip phones have minDim ~360-410dp and aspect ~2.4, so they are excluded.
    // Tablets have aspect ratios >= 1.33 (4:3) or 1.60 (16:10), so they are excluded.
    if (minDim < 580) return false

    val aspectRatio = maxDim.toFloat() / minDim.toFloat()
    val isSquareFoldableAspect = aspectRatio <= 1.28f

    val hasFoldableHardware = isFoldableDevice()
    val isWideEnough = config.screenWidthDp >= FOLDABLE_MIN_WIDTH_DP

    return (hasFoldableHardware && isWideEnough && aspectRatio <= 1.30f) || isSquareFoldableAspect
}

