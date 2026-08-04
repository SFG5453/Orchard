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
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * Harvests Spotify Web Access Tokens using the logged-in sp_dc session cookie
 * via an offscreen WebView context on Android.
 */
class SpotifyTokenHarvester(private val context: Context) {

    @SuppressLint("SetJavaScriptEnabled")
    suspend fun harvestAccessToken(spdcToken: String): String? = withContext(Dispatchers.Main) {
        if (spdcToken.isBlank()) return@withContext null
        val deferred = CompletableDeferred<String?>()

        val cookieManager = CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setCookie("https://.spotify.com", "sp_dc=$spdcToken; Domain=.spotify.com; Path=/; Secure; HttpOnly")
            flush()
        }

        var webView: WebView? = null
        try {
            webView = WebView(context.applicationContext).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = BROWSER_USER_AGENT
                cookieManager.setAcceptThirdPartyCookies(this, true)

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String?) {
                        super.onPageFinished(view, url)
                        evaluateTokenFetch(view, deferred)
                    }
                }

                loadUrl("https://open.spotify.com/")
            }

            withTimeoutOrNull(HARVEST_TIMEOUT_MS) {
                deferred.await()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Spotify token harvest failed: ${e.message}")
            null
        } finally {
            runCatching {
                webView?.stopLoading()
                webView?.destroy()
            }
        }
    }

    private fun evaluateTokenFetch(view: WebView, deferred: CompletableDeferred<String?>) {
        val script = "(function(){ fetch('/api/token').then(r=>r.json()).then(d=>JSON.stringify(d)).catch(e=>''); })()"
        view.evaluateJavascript(script) { result ->
            if (result.isNullOrBlank() || result == "null") return@evaluateJavascript
            runCatching {
                val jsonStr = if (result.startsWith("\"") && result.endsWith("\"")) {
                    JSONObject("{\"val\":$result}").getString("val")
                } else result
                val json = JSONObject(jsonStr)
                val token = json.optString("accessToken")
                val isAnon = json.optBoolean("isAnonymous", false)
                if (token.isNotBlank() && !isAnon) {
                    deferred.complete(token)
                }
            }
        }
    }

    companion object {
        const val BROWSER_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/135.0.0.0 Safari/537.36 Edg/135.0.0.0"
        private const val HARVEST_TIMEOUT_MS = 20_000L
        private const val TAG = "SpotifyTokenHarvester"
    }
}
