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

package dev.sfg.orchard.mobile.spotify

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/** An access token as minted by the Spotify web player, with its own expiry. */
data class HarvestedToken(val token: String, val expiresAt: Long)

/**
 * Harvests Spotify Web Access Tokens using the logged-in sp_dc session cookie
 * via an offscreen WebView on Android.
 *
 * Spotify retired the old get_access_token endpoint, and its replacement
 * (/api/token) rejects requests that are not signed the way its own web player
 * signs them. So rather than forge that signature we load the real web player
 * offscreen and read the token it obtains for itself, by hooking fetch/XHR in
 * the page before its own scripts run. This mirrors what the desktop build does
 * over CDP in electron/integrations/spotify.js.
 */
class SpotifyTokenHarvester(private val context: Context) {

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    suspend fun harvestAccessToken(spdcToken: String): HarvestedToken? = withContext(Dispatchers.Main) {
        if (spdcToken.isBlank()) return@withContext null
        val deferred = CompletableDeferred<HarvestedToken?>()

        val cookieManager = CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setCookie("https://open.spotify.com/", "sp_dc=$spdcToken; Domain=.spotify.com; Path=/; Secure")
            setCookie("https://accounts.spotify.com/", "sp_dc=$spdcToken; Domain=.spotify.com; Path=/; Secure")
            flush()
        }

        // The player caches its access token in web storage and will not re-request
        // /api/token if a live one is already there — leaving the hook with nothing
        // to observe. Wiping storage forces a fresh mint on every harvest.
        runCatching { WebStorage.getInstance().deleteAllData() }

        var webView: WebView? = null
        try {
            webView = WebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = BROWSER_USER_AGENT
                cookieManager.setAcceptThirdPartyCookies(this, true)

                addJavascriptInterface(TokenBridge(deferred), BRIDGE_NAME)

                // The hook has to be installed before the page's own bundle runs,
                // otherwise the token request has already gone out by the time we
                // patch fetch. addDocumentStartJavaScript guarantees that ordering;
                // onPageStarted injection is the best-effort fallback without it.
                val documentStartSupported =
                    WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                if (documentStartSupported) {
                    runCatching {
                        WebViewCompat.addDocumentStartJavaScript(
                            this,
                            HOOK_SCRIPT,
                            setOf("https://open.spotify.com"),
                        )
                    }
                }

                webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        if (!documentStartSupported) {
                            view.evaluateJavascript(HOOK_SCRIPT, null)
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        // Re-assert the hook in case the player navigated client-side
                        // after our document-start injection ran.
                        view.evaluateJavascript(HOOK_SCRIPT, null)
                    }
                }

                loadUrl("https://open.spotify.com/")
            }

            withTimeoutOrNull(HARVEST_TIMEOUT_MS) {
                deferred.await()
            }.also { if (it == null) Log.w(TAG, "Spotify token harvest timed out") }
        } catch (e: Exception) {
            Log.w(TAG, "Spotify token harvest failed: ${e.message}")
            null
        } finally {
            runCatching {
                webView?.removeJavascriptInterface(BRIDGE_NAME)
                webView?.stopLoading()
                webView?.destroy()
            }
        }
    }

    /** Receives raw /api/token response bodies from the hooked page. */
    private class TokenBridge(private val deferred: CompletableDeferred<HarvestedToken?>) {
        @JavascriptInterface
        fun onTokenPayload(payload: String?) {
            if (payload.isNullOrBlank()) return
            runCatching {
                val json = JSONObject(payload)
                val token = json.optString("accessToken")
                // The player also mints an anonymous token before the cookie is
                // applied; that one cannot read canvases, so keep waiting for the
                // logged-in one.
                if (token.isBlank() || json.optBoolean("isAnonymous", false)) return
                val expiresAt = json.optLong("accessTokenExpirationTimestampMs")
                    .takeIf { it > System.currentTimeMillis() }
                    ?: (System.currentTimeMillis() + DEFAULT_TOKEN_LIFETIME_MS)
                deferred.complete(HarvestedToken(token, expiresAt))
            }
        }
    }

    companion object {
        const val BROWSER_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36 Edg/135.0.0.0"
        private const val HARVEST_TIMEOUT_MS = 20_000L
        private const val DEFAULT_TOKEN_LIFETIME_MS = 3_600_000L
        private const val BRIDGE_NAME = "OrchardTokenBridge"
        private const val TAG = "SpotifyTokenHarvester"

        private val HOOK_SCRIPT = """
            (function () {
              if (window.__orchardTokenHook) return;
              window.__orchardTokenHook = true;
              var report = function (body) {
                try { $BRIDGE_NAME.onTokenPayload(body); } catch (e) {}
              };
              var isToken = function (u) {
                try { return String(u).indexOf('/api/token') !== -1; } catch (e) { return false; }
              };
              var origFetch = window.fetch;
              if (origFetch) {
                window.fetch = function (input, init) {
                  var url = (input && input.url) ? input.url : input;
                  var result = origFetch.apply(this, arguments);
                  if (isToken(url)) {
                    try {
                      result.then(function (res) {
                        res.clone().text().then(report).catch(function () {});
                      }).catch(function () {});
                    } catch (e) {}
                  }
                  return result;
                };
              }
              var origOpen = XMLHttpRequest.prototype.open;
              XMLHttpRequest.prototype.open = function (method, url) {
                this.__orchardUrl = url;
                return origOpen.apply(this, arguments);
              };
              var origSend = XMLHttpRequest.prototype.send;
              XMLHttpRequest.prototype.send = function () {
                var xhr = this;
                try {
                  xhr.addEventListener('load', function () {
                    if (isToken(xhr.__orchardUrl)) {
                      try { report(xhr.responseText); } catch (e) {}
                    }
                  });
                } catch (e) {}
                return origSend.apply(this, arguments);
              };
            })();
        """.trimIndent()
    }
}
