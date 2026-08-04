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

package dev.sfg.orchard.connect.app

import android.Manifest
import android.app.SearchManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.sfg.orchard.mobile.app.OrchardApp
import dev.sfg.orchard.mobile.app.OrchardViewModel
import dev.sfg.orchard.mobile.ui.theme.OrchardTheme

/** Thin Android entry point; app behavior lives in repositories and state holders. */
class MainActivity : ComponentActivity() {
    private val viewModel by viewModels<OrchardViewModel>()
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Media3 remains usable in-app if the user declines notifications. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        requestNotificationPermission()
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            OrchardTheme(useSystemColors = settings.useSystemColors) {
                OrchardApp(viewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            // Assistant sends the spoken words as the query, and narrows them into the focus
            // extras when it recognised a specific artist or album. An empty query is the
            // documented "just play something" request.
            val query = intent.getStringExtra(SearchManager.QUERY).orEmpty().ifBlank {
                listOfNotNull(
                    intent.getStringExtra(MediaStore.EXTRA_MEDIA_TITLE),
                    intent.getStringExtra(MediaStore.EXTRA_MEDIA_ARTIST),
                    intent.getStringExtra(MediaStore.EXTRA_MEDIA_ALBUM),
                ).joinToString(" ")
            }
            viewModel.playFromSearch(query)
            return
        }
        if (intent.action == Intent.ACTION_SEND && intent.type?.startsWith("text/") == true) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            if (sharedText.isNotBlank()) {
                viewModel.handleIncomingLink(sharedText)
            }
            return
        }

        val uri = intent.data
        val dataString = uri?.toString().orEmpty()
        if (dataString.isBlank()) return
        val isDiscordCallback = (uri?.scheme == "orchard" && uri.host == "discord") ||
            (uri?.scheme?.startsWith("discord-") == true)
        if (isDiscordCallback) {
            val code = uri.getQueryParameter("code")
            val state = uri.getQueryParameter("state")
            if (!code.isNullOrBlank()) {
                viewModel.handleDiscordAuthCallback(code, state)
            }
            return
        }
        if (uri?.scheme == "orchard-connect") {
            viewModel.pairDevice(dataString)
        } else {
            viewModel.handleIncomingLink(dataString)
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
