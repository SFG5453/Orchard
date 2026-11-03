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

import android.content.Context
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker

/** A half-open horizontal hinge, in window px. Content above [top] faces the user, below [bottom] lies flat. */
data class TabletopHinge(val top: Float, val bottom: Float)

/** Covers flip phones too, which [dev.sfg.orchard.mobile.audio.isFoldableHardware] leaves out. */
private fun Context.hasHinge(): Boolean =
    packageManager.hasSystemFeature("android.hardware.sensor.hinge_angle") ||
        listOf("fold", "flip", "razr").any {
            Build.MODEL.contains(it, ignoreCase = true) || Build.DEVICE.contains(it, ignoreCase = true)
        }

/** Non-null while the device sits folded about 90 degrees with the hinge across the screen. */
@Composable
fun rememberTabletopHinge(): TabletopHinge? {
    val context = LocalContext.current
    // Skip the window-layout subscription on slab phones; it only costs open time there.
    if (!remember(context) { context.hasHinge() }) return null
    val tracker = remember(context) { WindowInfoTracker.getOrCreate(context) }
    val flow = remember(tracker, context) { tracker.windowLayoutInfo(context) }
    val info by flow.collectAsState(initial = null)
    val fold = info?.displayFeatures?.filterIsInstance<FoldingFeature>()?.firstOrNull() ?: return null
    if (fold.state != FoldingFeature.State.HALF_OPENED || fold.orientation != FoldingFeature.Orientation.HORIZONTAL) {
        return null
    }
    return TabletopHinge(fold.bounds.top.toFloat(), fold.bounds.bottom.toFloat())
}
