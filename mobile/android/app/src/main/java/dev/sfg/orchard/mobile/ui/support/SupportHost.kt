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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.sfg.orchard.mobile.ui.support

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.sfg.orchard.mobile.support.SupportNotice
import dev.sfg.orchard.mobile.support.SupportService
import dev.sfg.orchard.mobile.support.captureWindow
import dev.sfg.orchard.mobile.support.findActivity
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

internal enum class SupportMode { Closed, Open, Capturing, Shooting }
internal enum class SupportScreen { Compose, List, Report }

/** Where the report sheet is and what it shows. Lives above the NavHost so it survives navigation. */
@Stable
class SupportUi internal constructor(val service: SupportService) {
    internal var mode by mutableStateOf(SupportMode.Closed)
    internal var screen by mutableStateOf(SupportScreen.Compose)

    fun open() {
        screen = SupportScreen.Compose
        mode = SupportMode.Open
    }

    fun openReports() {
        screen = SupportScreen.List
        mode = SupportMode.Open
    }

    fun openReport(id: String) {
        service.openReport(id)
        screen = SupportScreen.Report
        mode = SupportMode.Open
    }

    internal fun close() {
        mode = SupportMode.Closed
    }
}

/** Null outside OrchardApp, such as in previews. */
val LocalSupportUi = staticCompositionLocalOf<SupportUi?> { null }

@Composable
fun rememberSupportUi(): SupportUi {
    val context = LocalContext.current
    return remember { SupportUi(SupportService.get(context)) }
}

/**
 * Report sheet, capture bubble and update banner. [page] is the current route,
 * recorded in diagnostics so a report says where it started.
 */
@Composable
fun SupportHost(ui: SupportUi, page: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var notice by remember { mutableStateOf<SupportNotice?>(null) }

    // Poll while the app is in front; coming back also catches a finished GitHub link.
    LaunchedEffect(ui) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            ui.service.refresh(SupportService.RESUME_REFRESH_MS)
            while (true) {
                delay(SupportService.POLL_INTERVAL_MS)
                ui.service.refresh()
            }
        }
    }
    LaunchedEffect(ui) {
        ui.service.notices.collect { notice = it }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            delay(6_000)
            notice = null
        }
    }
    // The bubble is gone from this frame on; wait for it to leave the screen, then shoot.
    LaunchedEffect(ui.mode) {
        if (ui.mode != SupportMode.Shooting) return@LaunchedEffect
        withFrameNanos {}
        withFrameNanos {}
        val activity = context.findActivity()
        val shot = activity?.let { captureWindow(it) }
        if (shot != null) ui.service.attachScreenshot(shot)
        ui.screen = SupportScreen.Compose
        ui.mode = SupportMode.Open
    }

    Box(modifier.fillMaxSize()) {
        if (ui.mode == SupportMode.Open) {
            SupportSheet(ui = ui, page = page)
        }
        if (ui.mode == SupportMode.Capturing) {
            CaptureBubble(
                onCapture = { ui.mode = SupportMode.Shooting },
                onCancel = { ui.mode = SupportMode.Open },
            )
        }
        AnimatedVisibility(
            visible = notice != null && ui.mode == SupportMode.Closed,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            val shown = notice
            UpdateBanner(
                message = shown?.message.orEmpty(),
                onOpen = {
                    notice = null
                    val id = shown?.reportId
                    if (id != null) ui.openReport(id) else ui.openReports()
                },
                onDismiss = { notice = null },
            )
        }
    }
}

@Composable
private fun UpdateBanner(message: String, onOpen: () -> Unit, onDismiss: () -> Unit) {
    Row(
        Modifier
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xF0182019))
            .border(1.dp, Color(0x556F9A80), RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(start = 14.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(Icons.Rounded.BugReport, contentDescription = null, tint = Color(0xFFC4E0CB), modifier = Modifier.size(20.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp),
            color = Color(0xFFF0EEE7),
            maxLines = 2,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.size(40.dp).clickable(role = Role.Button, onClick = onDismiss), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.Close, contentDescription = "Dismiss", tint = Color(0xFFA3ADA5), modifier = Modifier.size(18.dp))
        }
    }
}

/** Draggable so it never sits on top of the thing you are trying to photograph. */
@Composable
private fun CaptureBubble(onCapture: () -> Unit, onCancel: () -> Unit) {
    var offset by remember { mutableStateOf(IntOffset.Zero) }
    Box(Modifier.fillMaxSize().navigationBarsPadding().padding(bottom = 140.dp, end = 16.dp), contentAlignment = Alignment.BottomEnd) {
        Row(
            Modifier
                .offset { offset }
                .pointerInput(Unit) {
                    detectDragGestures { change, drag ->
                        change.consume()
                        offset += IntOffset(drag.x.roundToInt(), drag.y.roundToInt())
                    }
                }
                .clip(RoundedCornerShape(28.dp))
                .background(Color(0xF0141815))
                .border(1.dp, Color(0x556F9A80), RoundedCornerShape(28.dp))
                .padding(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(Color(0xFFA6D4BF))
                    .clickable(role = Role.Button, onClickLabel = "Capture this screen", onClick = onCapture)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(Icons.Rounded.PhotoCamera, contentDescription = null, tint = Color(0xFF0D120F), modifier = Modifier.size(18.dp))
                Text("Capture", color = Color(0xFF0D120F), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
            Box(
                Modifier.size(44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onCancel),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Close, contentDescription = "Back to the report", tint = Color(0xFFD6D9D3), modifier = Modifier.size(20.dp))
            }
        }
    }
}
