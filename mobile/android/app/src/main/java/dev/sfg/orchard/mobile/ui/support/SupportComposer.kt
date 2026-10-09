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

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.support.SupportReport
import dev.sfg.orchard.mobile.support.SupportService
import dev.sfg.orchard.mobile.support.SupportState
import dev.sfg.orchard.mobile.support.decodePickedImage
import dev.sfg.orchard.mobile.support.supportDiagnostics
import dev.sfg.orchard.mobile.ui.screens.SettingsPill
import dev.sfg.orchard.mobile.ui.screens.SettingsSegmented
import dev.sfg.orchard.mobile.ui.screens.SettingsStyle
import dev.sfg.orchard.mobile.ui.screens.SettingsSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** New report form. Text lives in the service's draft, so leaving to capture a screen keeps it. */
@Composable
internal fun SupportComposer(
    state: SupportState,
    service: SupportService,
    page: String,
    onCapture: () -> Unit,
    onSent: (SupportReport) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val draft = state.draft
    var showDiagnostics by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch {
            val bitmap = withContext(Dispatchers.IO) { decodePickedImage(context, uri) }
            if (bitmap != null) service.attachScreenshot(bitmap)
        }
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        SettingsSegmented(
            options = listOf("bug" to "Bug", "feature" to "Idea", "feedback" to "Feedback"),
            selected = draft.kind,
            onSelect = { kind -> service.updateDraft { it.copy(kind = kind) } },
        )
        Field(
            value = draft.title,
            placeholder = "Short title, like \"Lyrics stop after a skip\"",
            singleLine = true,
            onChange = { text -> service.updateDraft { it.copy(title = text.take(140)) } },
        )
        Field(
            value = draft.body,
            placeholder = when (draft.kind) {
                "feature" -> "What would you like Orchard to do, and what would you use it for?"
                "feedback" -> "Tell us what you think."
                else -> "What happened, and what did you expect? Steps that make it happen again help the most."
            },
            minHeight = 140,
            onChange = { text -> service.updateDraft { it.copy(body = text) } },
        )

        ScreenshotCard(
            draft.screenshot,
            onCapture = onCapture,
            onPick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            onRemove = { service.attachScreenshot(null) },
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Include system details", color = SettingsStyle.Title, fontSize = 15.sp)
                Text(
                    if (showDiagnostics) "Hide details" else "App version, Android version and device model. Tap to see them.",
                    color = SettingsStyle.Description,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable(role = Role.Button) { showDiagnostics = !showDiagnostics },
                )
            }
            SettingsSwitch(checked = draft.diagnostics, onCheckedChange = { on -> service.updateDraft { it.copy(diagnostics = on) } })
        }
        if (showDiagnostics && draft.diagnostics) {
            val preview = remember(page) { supportDiagnostics(context, page).toString(2) }
            Text(
                preview,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = SettingsStyle.Description,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color(0xFF141815)).padding(12.dp),
            )
        }

        if (state.error.isNotBlank()) {
            Text(state.error, color = Color(0xFFE8A598), fontSize = 13.sp)
        }
        Text(
            "Posted publicly as an issue on ${state.repository.ifBlank { "GitHub" }} by @${state.github?.login.orEmpty()}" +
                if (draft.screenshot != null) ", screenshot included." else ".",
            color = SettingsStyle.Caption,
            fontSize = 12.sp,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(bottom = 24.dp)) {
            SettingsPill("Discard", onClick = service::discardDraft, modifier = Modifier.weight(1f), destructive = true)
            SettingsPill(
                if (state.submitting) "Sending…" else "Send report",
                onClick = { service.submit(page, onSent) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Field(value: String, placeholder: String, onChange: (String) -> Unit, singleLine: Boolean = false, minHeight: Int = 0) {
    val shape = RoundedCornerShape(14.dp)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = singleLine,
        textStyle = TextStyle(color = SettingsStyle.Title, fontSize = 15.sp),
        cursorBrush = SolidColor(SettingsStyle.SageSoft),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { field ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = maxOf(48, minHeight).dp)
                    .background(SettingsStyle.Panel, shape)
                    .border(1.dp, SettingsStyle.PanelBorder, shape)
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                if (value.isEmpty()) Text(placeholder, color = SettingsStyle.Caption, fontSize = 15.sp)
                field()
            }
        },
    )
}

@Composable
private fun ScreenshotCard(
    screenshot: android.graphics.Bitmap?,
    onCapture: () -> Unit,
    onPick: () -> Unit,
    onRemove: () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .background(SettingsStyle.Panel, shape)
            .border(1.dp, if (screenshot != null) Color(0x556F9A80) else SettingsStyle.PanelBorder, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            if (screenshot != null) {
                val image = remember(screenshot) { screenshot.asImageBitmap() }
                Image(
                    image,
                    contentDescription = "Screenshot preview",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.width(84.dp).heightIn(max = 168.dp).clip(RoundedCornerShape(10.dp)),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(if (screenshot != null) "Screenshot attached" else "Screenshot", color = SettingsStyle.Title, fontSize = 15.sp)
                Text(
                    if (screenshot != null) "Check it for anything private before sending: it is posted publicly."
                    else "Capture hides this sheet so you can open the screen with the problem first.",
                    color = SettingsStyle.Description,
                    fontSize = 13.sp,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(Icons.Rounded.PhotoCamera, if (screenshot != null) "Recapture" else "Capture", onCapture)
            Chip(Icons.Rounded.Image, "Choose", onPick)
            if (screenshot != null) Chip(Icons.Rounded.Delete, "Remove", onRemove)
        }
    }
}

@Composable
private fun Chip(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(SettingsStyle.ButtonFill)
            .border(1.dp, SettingsStyle.ButtonBorder, RoundedCornerShape(20.dp))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, tint = SettingsStyle.SageSoft, modifier = Modifier.size(16.dp))
        Text(label, color = SettingsStyle.Title, fontSize = 13.sp, style = MaterialTheme.typography.labelLarge)
    }
}
