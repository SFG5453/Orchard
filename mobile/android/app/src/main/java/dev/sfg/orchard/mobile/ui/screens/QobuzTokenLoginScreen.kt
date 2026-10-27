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

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.sfg.orchard.mobile.qobuz.QobuzResolver
import dev.sfg.orchard.mobile.qobuz.QobuzSession
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QobuzTokenLoginScreen(
    qobuz: QobuzResolver,
    onSuccess: (QobuzSession) -> Unit,
    onCancel: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var userId by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var appId by remember { mutableStateOf("") }
    var appSecret by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(CanopyColors.Canvas)) {
        TopAppBar(
            title = { Text("Sign in with a token") },
            navigationIcon = {
                IconButton(onClick = onCancel) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = CanopyColors.Canvas,
                titleContentColor = CanopyColors.Text,
                navigationIconContentColor = CanopyColors.Text,
            ),
        )
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            Text(
                "Signed in at play.qobuz.com, the browser's local storage entry \"localuser\" holds your user ID and auth token.",
                style = MaterialTheme.typography.bodySmall,
                color = CanopyColors.Muted,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = userId,
                onValueChange = { userId = it.filter(Char::isDigit) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("User ID") },
                singleLine = true,
                enabled = !checking,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Auth token") },
                singleLine = true,
                enabled = !checking,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Spacer(Modifier.height(20.dp))
            Text(
                "Optional: with your own app ID and secret, Orchard streams FLAC files from the Qobuz API (track/getFileUrl). The token must come from the same app.",
                style = MaterialTheme.typography.bodySmall,
                color = CanopyColors.Muted,
                lineHeight = 18.sp,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = appId,
                onValueChange = { appId = it.filter(Char::isDigit) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("App ID") },
                singleLine = true,
                enabled = !checking,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = appSecret,
                onValueChange = { appSecret = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("App secret") },
                singleLine = true,
                enabled = !checking,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            if (errorMessage.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(errorMessage, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(16.dp))
            IntegrationAction(if (checking) "Checking…" else "Sign in") {
                if (checking || userId.isBlank() || token.isBlank()) return@IntegrationAction
                checking = true
                errorMessage = ""
                scope.launch {
                    try {
                        onSuccess(qobuz.verifyToken(userId, token, appId, appSecret))
                    } catch (e: Exception) {
                        errorMessage = e.message?.substringBefore('\n')?.ifBlank { null } ?: "Qobuz token sign-in failed"
                        checking = false
                    }
                }
            }
        }
    }
}
