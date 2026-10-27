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

package dev.sfg.orchard.mobile.shazam

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.sfg.orchard.mobile.OrchardGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

@Composable
internal fun IdentifySongButton(onMatch: (String) -> Unit) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val matched by rememberUpdatedState(onMatch)
    val recognizer = remember(context) {
        val appleMusic = OrchardGraph.from(context).appleMusic
        ShazamRecognizer(appleMusic::shazamToken, appleMusic::forgetShazamToken)
    }
    var visible by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    val dismiss = { listening = false; visible = false }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        visible = true
        listening = granted
        if (!granted) status = "Allow microphone access in Android settings to identify songs."
    }

    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) dismiss()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(listening) {
        if (!listening) return@LaunchedEffect
        try {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException("Microphone access is required")
            }
            val query = recognizer.identify { status = it }
            visible = false
            matched(query)
        } catch (_: TimeoutCancellationException) {
            status = "Song identification timed out. Try again."
        } catch (error: CancellationException) {
            throw error
        } catch (_: SecurityException) {
            status = "Allow microphone access in Android settings to identify songs."
        } catch (_: LinkageError) {
            status = "Shazam is unavailable on this device."
        } catch (error: Exception) {
            status = error.message ?: "Could not identify the song. Try again."
        } finally {
            listening = false
        }
    }

    IconButton(onClick = {
        keyboard?.hide()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            visible = true
            listening = true
        } else {
            permission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }, modifier = Modifier.size(44.dp)) {
        Icon(Icons.Rounded.GraphicEq, "Identify song with Shazam", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
    }
    if (visible) {
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text("Identify with Shazam") },
            text = {
                Column {
                    if (listening) CircularProgressIndicator(Modifier.padding(bottom = 16.dp).size(32.dp))
                    Text(status)
                }
            },
            confirmButton = {
                TextButton(onClick = dismiss) { Text(if (listening) "Cancel" else "Close") }
            },
        )
    }
}
