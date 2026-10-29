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

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sfg.orchard.mobile.model.OrchardSettings
import dev.sfg.orchard.mobile.playback.smart.npu.NpuSupport
import dev.sfg.orchard.mobile.playback.smart.npu.NpuVendor
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import dev.sfg.orchard.mobile.ui.theme.LocalAccent

/** Set by the Adaptive mix toggle on a phone with a supported NPU that is not set up yet. */
internal object NpuPrompt {
    var requested by mutableStateOf(false)
}

/**
 * The NPU offer (from the toggle, or once at startup for people who already use adaptive mix),
 * the add-on download, the one-time compile notice and failures.
 */
@Composable
internal fun NpuDialogHost(settings: OrchardSettings, onSettings: (OrchardSettings) -> Unit) {
    val npu by NpuSupport.state.collectAsStateWithLifecycle()
    val device = npu.device
    val atStartup = settings.smartCrossfade && npu.supported && !npu.promptSeen && !npu.ready
    var downloadStarted by remember { mutableStateOf(false) }
    val offer = device != null && npu.supported && (NpuPrompt.requested || atStartup || downloadStarted)
    LaunchedEffect(offer) { if (offer) NpuSupport.fetchDownloadSize() }
    // The installer takes over once the download lands.
    LaunchedEffect(npu.downloading) { if (!npu.downloading) downloadStarted = false }

    when {
        npu.compiling && device != null -> NpuCompilingDialog(device.vendor.brand)
        npu.error != null -> NpuErrorDialog(npu.error!!) { NpuSupport.dismissError() }
        offer && device != null -> NpuOfferDialog(
            brand = device.vendor.brand,
            downloadMb = if (device.vendor == NpuVendor.QUALCOMM) (npu.downloadBytes + (1 shl 20) - 1) / (1 shl 20) else null,
            downloading = npu.downloading,
            onAccept = {
                if (!settings.smartCrossfade) onSettings(settings.copy(smartCrossfade = true))
                NpuSupport.accept()
                if (device.vendor == NpuVendor.QUALCOMM) downloadStarted = true
                NpuPrompt.requested = false
            },
            onDecline = {
                if (!settings.smartCrossfade) onSettings(settings.copy(smartCrossfade = true))
                NpuSupport.decline()
                NpuPrompt.requested = false
            },
        )
    }
}

@Composable
private fun NpuOfferDialog(brand: String, downloadMb: Long?, downloading: Boolean, onAccept: () -> Unit, onDecline: () -> Unit) {
    NpuDialog(Icons.Rounded.Memory, pulse = downloading, onDismiss = if (downloading) null else onDecline) {
        DialogTitle("WOAH THERE!")
        Spacer(Modifier.height(10.dp))
        DialogBody(
            "Your device has a supported $brand NPU, a chip built for AI. Adaptive Mix can use it to find " +
                "beats and vocals, so mixes are ready sooner and use less battery than on the CPU.",
        )
        if (downloadMb != null) {
            Spacer(Modifier.height(10.dp))
            Text(
                if (downloading) "Downloading NPU support…" else "One-time download, about $downloadMb MB",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = LocalAccent.current,
            )
        }
        Spacer(Modifier.height(20.dp))
        if (downloading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                color = LocalAccent.current,
                trackColor = CanopyColors.Canvas,
            )
            return@NpuDialog
        }
        PrimaryButton(if (downloadMb != null) "Download and enable now" else "Enable", onAccept)
        Spacer(Modifier.height(6.dp))
        TextButton(onClick = onDecline, shape = CircleShape, modifier = Modifier.fillMaxWidth().height(44.dp)) {
            Text("Not now", style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = CanopyColors.Muted)
        }
    }
}

@Composable
private fun NpuCompilingDialog(brand: String) {
    NpuDialog(Icons.Rounded.Memory, pulse = true, onDismiss = null) {
        DialogTitle("Optimizing for your NPU")
        Spacer(Modifier.height(10.dp))
        DialogBody("Orchard is preparing Adaptive Mix's models for your $brand NPU. This only happens once.")
        Spacer(Modifier.height(18.dp))
        LinearProgressIndicator(
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
            color = LocalAccent.current,
            trackColor = CanopyColors.Canvas,
        )
    }
}

@Composable
private fun NpuErrorDialog(message: String, onDismiss: () -> Unit) {
    NpuDialog(Icons.Rounded.ErrorOutline, pulse = false, onDismiss = onDismiss, tint = CanopyColors.Danger) {
        DialogTitle("NPU support")
        Spacer(Modifier.height(10.dp))
        DialogBody(message)
        Spacer(Modifier.height(20.dp))
        PrimaryButton("OK", onDismiss)
    }
}

@Composable
private fun NpuDialog(
    icon: ImageVector,
    pulse: Boolean,
    onDismiss: (() -> Unit)?,
    tint: Color = LocalAccent.current,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scale = if (pulse) {
        val transition = rememberInfiniteTransition(label = "npu")
        transition.animateFloat(
            0.92f, 1.08f,
            infiniteRepeatable(tween(1000, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "scale",
        ).value
    } else 1f
    Dialog(
        onDismissRequest = { onDismiss?.invoke() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = onDismiss != null,
            dismissOnBackPress = onDismiss != null),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = CanopyColors.Surface,
            border = BorderStroke(1.dp, CanopyColors.RuleStrong),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).widthIn(max = 400.dp),
        ) {
            Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(56.dp).scale(scale).background(tint.copy(alpha = 0.16f), CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.height(16.dp))
                content()
            }
        }
    }
}

@Composable
private fun DialogTitle(text: String) = Text(
    text,
    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
    color = CanopyColors.Text,
)

@Composable
private fun DialogBody(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp),
    color = CanopyColors.MutedStrong,
    textAlign = TextAlign.Center,
    modifier = Modifier.fillMaxWidth(),
)

@Composable
private fun PrimaryButton(label: String, onClick: () -> Unit) = Button(
    onClick = onClick,
    shape = CircleShape,
    colors = ButtonDefaults.buttonColors(containerColor = LocalAccent.current, contentColor = Color.Black),
    modifier = Modifier.fillMaxWidth().height(46.dp),
) {
    Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold))
}
