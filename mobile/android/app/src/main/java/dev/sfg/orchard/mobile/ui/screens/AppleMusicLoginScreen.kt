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

import dev.sfg.orchard.mobile.i18n.tr

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.sfg.orchard.mobile.applemusic.AppleMusicAuth
import dev.sfg.orchard.mobile.applemusic.AppleMusicDeveloperTokenClient
import dev.sfg.orchard.mobile.applemusic.AppleMusicSignInException
import dev.sfg.orchard.mobile.applemusic.AppleMusicSignInWindows
import dev.sfg.orchard.mobile.applemusic.AppleMusicWebSession
import dev.sfg.orchard.mobile.auth.OrchardAccountService
import dev.sfg.orchard.mobile.ui.theme.CanopyColors
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** MusicKit JS sign-in. The WebViews exist only while this screen is shown; cookies persist. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppleMusicLoginScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val auth = remember(context) { AppleMusicAuth.get(context) }
    var developerToken by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }
    var attempt by remember { mutableIntStateOf(0) }
    var windows by remember { mutableStateOf<AppleMusicSignInWindows?>(null) }

    LaunchedEffect(attempt) {
        error = ""
        if (!AppleMusicWebSession.supported) {
            error = "Update Android System WebView to sign in to Apple Music."
            return@LaunchedEffect
        }
        if (developerToken.isNotEmpty()) {
            windows?.reload()
            return@LaunchedEffect
        }
        error = try {
            val account = OrchardAccountService.get(context)
            val accessToken = account.accessToken()
                ?: throw AppleMusicSignInException("Sign in to your Orchard account first.")
            developerToken = AppleMusicDeveloperTokenClient(account.serviceUrl).fetch(accessToken).value
            ""
        } catch (e: CancellationException) {
            throw e
        } catch (_: IOException) {
            "Could not reach Apple Music sign-in. Check your connection and try again."
        } catch (e: AppleMusicSignInException) {
            e.message.orEmpty()
        } catch (_: Exception) {
            "Could not start Apple Music sign-in. Try again later."
        }
    }

    BackHandler { if (windows?.back() != true) onDone() }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(tr("Sign in to Apple Music")) },
            navigationIcon = {
                IconButton(onClick = onDone) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = tr("Back"))
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = CanopyColors.Canvas,
                titleContentColor = CanopyColors.Text,
                navigationIconContentColor = CanopyColors.Text,
            ),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (developerToken.isNotEmpty()) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { viewContext ->
                        AppleMusicSignInWindows(viewContext) { message ->
                            when (message.optString("type")) {
                                "authorized" -> if (auth.complete(message.optString("token"))) onDone()
                                "error" -> error = message.optString("message")
                                    .ifBlank { "Apple Music sign-in failed. Try again." }
                            }
                        }.also {
                            windows = it
                            it.load(developerToken)
                        }.root
                    },
                    onRelease = {
                        windows?.destroy()
                        windows = null
                    },
                )
            }
            if (error.isNotBlank()) {
                Surface(color = CanopyColors.Surface, modifier = Modifier.fillMaxSize().padding(24.dp)) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            tr("Apple Music sign-in failed"),
                            style = MaterialTheme.typography.titleMedium,
                            color = CanopyColors.Danger,
                        )
                        Text(tr(error), style = MaterialTheme.typography.bodyMedium, color = CanopyColors.Muted)
                        TextButton(onClick = { attempt++ }) { Text(tr("Retry")) }
                    }
                }
            }
        }
    }
}
