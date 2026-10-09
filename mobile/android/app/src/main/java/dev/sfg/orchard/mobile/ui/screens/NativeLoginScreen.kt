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
import dev.sfg.orchard.mobile.auth.YouTubeSession
import dev.sfg.orchard.mobile.auth.YouTubeSessionAuth
import dev.sfg.orchard.mobile.ui.theme.OrchardColors
import org.json.JSONArray
import org.json.JSONObject

/** In-app Android login that captures only YouTube's completed cookie session. */
@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NativeLoginScreen(
    auth: AuthState,
    onBegin: () -> Unit,
    onSession: (cookie: String, visitorData: String, dataSyncId: String, accountIndex: Int, avatarUrl: String) -> Unit,
    onCancel: () -> Unit,
    onComplete: () -> Unit,
    switchingAccount: Boolean = false,
    initialSession: YouTubeSession? = null,
) {
    var webView by remember { mutableStateOf<WebView?>(null) }
    var captureStarted by remember { mutableStateOf(false) }
    var probeStarted by remember { mutableStateOf(false) }
    var switchBaseline by remember { mutableStateOf<YouTubeSession?>(null) }
    var authorizationObserved by remember { mutableStateOf(false) }
    var pageError by remember { mutableStateOf("") }

    // Sign-in reaches SignedIn twice: once when the cookie is committed, and
    // again when the account name and avatar arrive. Both are distinct values,
    // so keying an effect on the state alone would dismiss this screen twice,
    // and the second dismissal takes whatever was underneath it with it.
    var dismissed by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { onBegin() }
    LaunchedEffect(auth) {
        when (auth) {
            AuthState.Authorizing -> authorizationObserved = true
            is AuthState.SignedIn -> if (authorizationObserved && !dismissed) {
                dismissed = true
                onComplete()
            }

            is AuthState.Error -> {
                captureStarted = false
                probeStarted = false
            }
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

    fun probePage(view: WebView) {
        if (webView != view || captureStarted) return
        val url = view.url.orEmpty()
        if (!url.isYouTubeUrl() || url.isChooserRedirectUrl() || view.progress < 100) {
            view.postDelayed({ probePage(view) }, 500)
            return
        }
        view.evaluateJavascript(YOUTUBE_CONFIG_SCRIPT) { rawValue ->
            if (webView != view || captureStarted) return@evaluateJavascript
            if (view.url != url) {
                view.postDelayed({ probePage(view) }, 500)
                return@evaluateJavascript
            }
            val config = decodeYouTubeConfig(rawValue)
            val cookie = mergedYouTubeCookie(CookieManager.getInstance(), url)
            val candidate = YouTubeSession(
                cookie = cookie,
                visitorData = config?.optString("visitorData").orEmpty(),
                dataSyncId = YouTubeSessionAuth.delegatedId(
                    config?.optString("dataSyncId"), config?.optString("delegatedSessionId"),
                ),
                accountIndex = config?.optString("accountIndex")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                avatarUrl = config?.optString("avatarUrl").orEmpty(),
            )
            val signedIn = YouTubeSessionAuth.loginCookieValue(cookie) != null
            val pageIdentityReady = !switchingAccount || initialSession == null ||
                config?.optString("delegatedSessionId").orEmpty().isNotBlank() ||
                config?.optString("dataSyncId").orEmpty().isNotBlank() ||
                config?.optString("accountIndex").orEmpty().isNotBlank()
            if (switchingAccount && signedIn && pageIdentityReady && switchBaseline == null) {
                // The chooser's first identity is the baseline for older saved sessions.
                switchBaseline = candidate
            }
            val changed = !switchingAccount ||
                YouTubeSessionAuth.selectedDifferentAccount(initialSession, switchBaseline, candidate)
            if (signedIn && changed && pageIdentityReady) {
                captureStarted = true
                onSession(cookie, candidate.visitorData, candidate.dataSyncId, candidate.accountIndex, candidate.avatarUrl)
            } else {
                // Channel selection can update the page without a new load event.
                view.postDelayed({ probePage(view) }, 500)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(if (switchingAccount) "Choose a YouTube account" else "Sign in to YouTube Music") },
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
                                if (!probeStarted) {
                                    probeStarted = true
                                    probePage(view)
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
                        if (switchingAccount) {
                            loadUrl(CHANNEL_SWITCHER_URL)
                        } else {
                            stopLoading()
                            clearHistory()
                            clearCache(true)
                            WebStorage.getInstance().deleteAllData()
                            // WebView no longer stores form passwords. Cookies, web storage, cache,
                            // and HTTP auth are the credentials that can actually survive a login.
                            WebViewDatabase.getInstance(context.applicationContext).apply {
                                clearHttpAuthUsernamePassword()
                            }
                            cookieManager.removeAllCookies {
                                cookieManager.flush()
                                cookieManager.setAcceptCookie(true)
                                cookieManager.setAcceptThirdPartyCookies(loginWebView, true)
                                loadUrl(LOGIN_URL)
                            }
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
                        ) { Text(if (switchingAccount) "Retry account switch" else "Reload sign-in") }
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

private fun String?.isChooserRedirectUrl(): Boolean =
    this?.let(Uri::parse)?.path?.trimEnd('/') == "/signin"

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
            if (name.isNotBlank()) values.putIfAbsent(name, part.substring(separator + 1).trim())
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
private const val CHANNEL_SWITCHER_URL = "https://m.youtube.com/#channel_switcher"

private const val YOUTUBE_CONFIG_SCRIPT = """
    (function() {
      try {
        var config = window.ytcfg;
        var get = config && typeof config.get === 'function' ? function(key) { return config.get(key); } : function() { return ''; };
        var legacy = window.yt && window.yt.config_ ? window.yt.config_ : {};
        var findScriptValue = function(key) {
          for (var i = 0; i < document.scripts.length; i++) {
            var text = document.scripts[i].textContent || '';
            var match = text.match(new RegExp('"' + key + '":"([^"]+)"'));
            if (match) return match[1];
          }
          return '';
        };
        var delegatedSessionId = get('DELEGATED_SESSION_ID') || legacy.DELEGATED_SESSION_ID || findScriptValue('DELEGATED_SESSION_ID') || '';
        var avatar = document.querySelector('#avatar-btn img[src], ytmusic-settings-button button img[src], ytmusic-nav-bar #avatar img[src]');
        var avatarUrl = avatar ? (avatar.currentSrc || avatar.src || '') : '';
        var accountIndex = get('SESSION_INDEX');
        if (accountIndex === '' || accountIndex == null) accountIndex = legacy.SESSION_INDEX;
        if (accountIndex === '' || accountIndex == null) accountIndex = findScriptValue('SESSION_INDEX');
        return JSON.stringify({
          visitorData: get('VISITOR_DATA') || legacy.VISITOR_DATA || findScriptValue('VISITOR_DATA') || '',
          delegatedSessionId: delegatedSessionId,
          dataSyncId: get('DATASYNC_ID') || legacy.DATASYNC_ID || findScriptValue('DATASYNC_ID') || '',
          accountIndex: accountIndex == null ? '' : accountIndex,
          avatarUrl: /^data:/i.test(avatarUrl) ? '' : avatarUrl
        });
      } catch (error) {
        return JSON.stringify({ visitorData: '', delegatedSessionId: '', dataSyncId: '', accountIndex: 0 });
      }
    })();
"""
