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
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebViewDatabase
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.sfg.orchard.mobile.auth.AuthState
import dev.sfg.orchard.mobile.auth.YouTubeSessionAuth
import dev.sfg.orchard.mobile.ui.theme.OrchardColors
import org.json.JSONArray
import org.json.JSONObject

/** In-app Android login that captures only YouTube's completed cookie session. */
@SuppressLint("SetJavaScriptEnabled")
@Suppress("DEPRECATION")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeLoginScreen(
    auth: AuthState,
    onBegin: () -> Unit,
    onSession: (cookie: String, visitorData: String, dataSyncId: String) -> Unit,
    onCancel: () -> Unit,
    onComplete: () -> Unit,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var visitorData by remember { mutableStateOf("") }
    var dataSyncId by remember { mutableStateOf("") }
    var captureStarted by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf("") }

    // Sign-in reaches SignedIn twice: once when the cookie is committed, and
    // again when the account name and avatar arrive. Both are distinct values,
    // so keying an effect on the state alone would dismiss this screen twice,
    // and the second dismissal takes whatever was underneath it with it.
    var dismissed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { onBegin() }
    LaunchedEffect(auth) {
        when (auth) {
            is AuthState.SignedIn -> if (!dismissed) {
                dismissed = true
                onComplete()
            }

            is AuthState.Error -> captureStarted = false
            else -> Unit
        }
    }

    fun close() {
        onCancel()
        if (!dismissed) {
            dismissed = true
            onComplete()
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Sign in to YouTube Music") },
            navigationIcon = {
                IconButton(onClick = ::close) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = OrchardColors.Night,
                titleContentColor = OrchardColors.Cream,
                navigationIconContentColor = OrchardColors.Cream,
            ),
        )
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { context ->
                    WebView(context).apply {
                        webView = this
                        val loginWebView = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.setSupportZoom(true)
                        settings.builtInZoomControls = true
                        settings.displayZoomControls = false
                        val cookieManager = CookieManager.getInstance().apply {
                            setAcceptCookie(true)
                            setAcceptThirdPartyCookies(loginWebView, true)
                        }
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String?) {
                                super.onPageFinished(view, url)
                                pageError = ""
                                if (!url.isYouTubeUrl()) return
                                view.evaluateJavascript(YOUTUBE_CONFIG_SCRIPT) { rawValue ->
                                    decodeYouTubeConfig(rawValue)?.let { config ->
                                        config.optString("visitorData").takeIf(String::isNotBlank)?.let {
                                            visitorData = it
                                        }
                                        config.optString("dataSyncId").takeIf(String::isNotBlank)?.let {
                                            dataSyncId = it
                                        }
                                    }
                                    val cookie = mergedYouTubeCookie(cookieManager, url)
                                    if (!captureStarted && YouTubeSessionAuth.loginCookieValue(cookie) != null) {
                                        captureStarted = true
                                        onSession(cookie, visitorData, dataSyncId)
                                    }
                                }
                            }

                            override fun onReceivedError(
                                view: WebView,
                                request: WebResourceRequest,
                                error: WebResourceError,
                            ) {
                                super.onReceivedError(view, request, error)
                                if (request.isForMainFrame) {
                                    pageError = error.description?.toString().orEmpty()
                                        .ifBlank { "The sign-in page could not be loaded." }
                                }
                            }
                        }
                        stopLoading()
                        clearHistory()
                        clearFormData()
                        clearCache(true)
                        WebStorage.getInstance().deleteAllData()
                        WebViewDatabase.getInstance(context.applicationContext).apply {
                            clearFormData()
                            clearHttpAuthUsernamePassword()
                            clearUsernamePassword()
                        }
                        cookieManager.removeAllCookies {
                            cookieManager.flush()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(loginWebView, true)
                            loadUrl(LOGIN_URL)
                        }
                    }
                },
            )
            val message = (auth as? AuthState.Error)?.message.orEmpty().ifBlank { pageError }
            if (message.isNotBlank()) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    shape = MaterialTheme.shapes.medium,
                ) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                        TextButton(
                            onClick = {
                                captureStarted = false
                                pageError = ""
                                onBegin()
                                webView?.reload()
                            },
                        ) { Text("Reload sign-in") }
                    }
                }
            }
        }
    }

    BackHandler {
        val current = webView
        if (current?.canGoBack() == true) current.goBack() else close()
    }
    DisposableEffect(Unit) {
        onDispose {
            webView?.apply {
                stopLoading()
                loadUrl("about:blank")
                clearHistory()
                destroy()
            }
            webView = null
        }
    }
}

private fun String?.isYouTubeUrl(): Boolean {
    val host = this?.let(Uri::parse)?.host?.lowercase() ?: return false
    return host == "youtube.com" || host.endsWith(".youtube.com")
}

private fun mergedYouTubeCookie(cookieManager: CookieManager, currentUrl: String?): String {
    val values = linkedMapOf<String, String>()
    val origins = linkedSetOf<String>()
    currentUrl?.takeIf { it.isYouTubeUrl() }?.let(origins::add)
    origins += listOf("https://music.youtube.com", "https://www.youtube.com", "https://youtube.com")
    cookieManager.flush()
    origins.forEach { origin ->
        cookieManager.getCookie(origin)?.split(';')?.forEach { part ->
            val separator = part.indexOf('=')
            if (separator <= 0) return@forEach
            val name = part.substring(0, separator).trim()
            if (name.isNotBlank()) values[name] = part.substring(separator + 1).trim()
        }
    }
    return values.entries.joinToString(separator = "; ") { (name, value) -> "$name=$value" }
}

private fun decodeYouTubeConfig(rawValue: String?): JSONObject? = runCatching {
    if (rawValue.isNullOrBlank() || rawValue == "null") return@runCatching null
    val jsonString = JSONArray("[$rawValue]").optString(0)
    JSONObject(jsonString)
}.getOrNull()

private const val LOGIN_URL =
    "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"

private const val YOUTUBE_CONFIG_SCRIPT = """
    (function() {
      try {
        var config = window.ytcfg;
        var get = config && typeof config.get === 'function' ? function(key) { return config.get(key); } : function() { return ''; };
        return JSON.stringify({ visitorData: get('VISITOR_DATA') || '', dataSyncId: get('DATASYNC_ID') || '' });
      } catch (error) {
        return JSON.stringify({ visitorData: '', dataSyncId: '' });
      }
    })();
"""
