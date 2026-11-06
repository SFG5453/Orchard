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

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.sfg.orchard.mobile.spotify.SpotifyCanvasRepository
import dev.sfg.orchard.mobile.spotify.SpotifyTokenHarvester
import dev.sfg.orchard.mobile.ui.theme.CanopyColors

/** In-app Spotify login screen that captures the sp_dc session cookie. */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpotifyLoginScreen(
    onSpdcCaptured: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var pageError by remember { mutableStateOf("") }
    var captured by remember { mutableStateOf(false) }

    fun checkAndCaptureCookie(cookieManager: CookieManager) {
        if (captured) return
        // "https://.spotify.com" is not a valid URL for getCookie; ask for the real
        // hosts and take the first jar that actually carries sp_dc.
        val spdc = listOf("https://open.spotify.com", "https://accounts.spotify.com")
            .firstNotNullOfOrNull { host ->
                SpotifyCanvasRepository.extractSpdc(cookieManager.getCookie(host).orEmpty())
                    .takeIf { it.isNotBlank() }
            }

        if (spdc != null) {
            captured = true
            onSpdcCaptured(spdc)
        }
    }

    BackHandler {
        if (webView?.canGoBack() == true) {
            webView?.goBack()
        } else {
            onCancel()
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Log in to Spotify") },
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
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    val loginView = WebView(context).apply {
                        webView = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = SpotifyTokenHarvester.BROWSER_USER_AGENT

                        val loginWebView = this
                        val cookieManager = CookieManager.getInstance().apply {
                            setAcceptCookie(true)
                            setAcceptThirdPartyCookies(loginWebView, true)
                        }

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                pageError = ""
                            }

                            override fun onPageFinished(view: WebView, url: String?) {
                                super.onPageFinished(view, url)
                                checkAndCaptureCookie(cookieManager)
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError,
                            ) {
                                super.onReceivedError(view, request, error)
                                if (request.isForMainFrame) {
                                    pageError = error.description?.toString().orEmpty()
                                        .ifBlank { "The Spotify login page could not be loaded." }
                                }
                            }

                            override fun onReceivedHttpError(
                                view: WebView,
                                request: WebResourceRequest,
                                errorResponse: WebResourceResponse,
                            ) {
                                super.onReceivedHttpError(view, request, errorResponse)
                                if (request.isForMainFrame) {
                                    pageError = "Spotify returned an error (${errorResponse.statusCode})."
                                }
                            }
                        }

                        loadUrl("https://accounts.spotify.com/en/login")
                    }
                    // Compose's direct WebView interop can leave the page blank.
                    FrameLayout(context).apply {
                        addView(loginView, FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                        ))
                    }
                },
                onRelease = { container ->
                    val view = container.getChildAt(0) as WebView
                    container.removeView(view)
                    webView = null
                    view.destroy()
                },
            )

            if (pageError.isNotBlank()) {
                Surface(
                    color = CanopyColors.Surface,
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Failed to load Spotify login",
                            style = MaterialTheme.typography.titleMedium,
                            color = CanopyColors.Danger,
                        )
                        Text(
                            pageError,
                            style = MaterialTheme.typography.bodyMedium,
                            color = CanopyColors.Muted,
                        )
                        TextButton(onClick = { webView?.reload() }) {
                            Text("Retry")
                        }
                    }
                }
            }
        }
    }
}
