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

package dev.sfg.orchard.mobile.playback

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.content.Context
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Mints WebPO ("proof of origin") tokens for googlevideo stream URLs.
 *
 * YouTube now refuses direct stream URLs that carry no `pot` parameter for an increasing share
 * of sessions — it is IP- and account-dependent, which is why the same track plays for one
 * listener and returns 403 for another. Desktop gained this in 716f5f0; this is the Android half.
 *
 * The token is produced by BotGuard, an obfuscated VM YouTube ships as JavaScript. Desktop runs
 * it under Node with a jsdom shim. Here it runs in a headless WebView, which is a real browser
 * and so the environment the VM is written for. The page is loaded under a youtube.com base URL
 * so BotGuard's two attestation calls are same-origin; from `about:blank` they are blocked by
 * CORS and the mint fails with no useful error.
 */
class YouTubePoTokenMinter(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val webViewRef = AtomicReference<WebView?>()
    private val readyLatch = CountDownLatch(1)

    private data class CachedToken(val token: String, val expiresAtMs: Long)

    private val tokens = ConcurrentHashMap<String, CachedToken>()
    private val pending = ConcurrentHashMap<String, CountDownLatch>()
    private val results = ConcurrentHashMap<String, JSONObject>()
    private val requestIds = AtomicLong(0)

    /** Serialises mints so a queue of tracks cannot start several attestations at once. */
    private val mintLock = Any()

    init {
        mainHandler.post {
            runCatching { initWebView() }
                .onFailure {
                    Log.e(TAG, "Failed to initialize the PO token WebView", it)
                    readyLatch.countDown()
                }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        val webView = WebView(context.applicationContext)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_NO_CACHE
            // BotGuard fingerprints the client. Use the exact browser identity that WEB_REMIX
            // declares in its player request and that Media3 later presents to googlevideo. A
            // mobile attestation paired with a desktop player/fetch identity produces a token
            // that looks valid to the player endpoint but is refused by the CDN.
            userAgentString = YouTubeStreamRequestIdentity.WEB_REMIX_USER_AGENT
        }
        webView.addJavascriptInterface(Bridge(), BRIDGE_NAME)
        webView.webViewClient = object : android.webkit.WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                webViewRef.set(view)
                readyLatch.countDown()
                Log.d(TAG, "PO token bundle ready")
            }
        }
        val bundle = context.assets.open(BUNDLE_ASSET).bufferedReader().use { it.readText() }
        // loadDataWithBaseURL is what gives the page a youtube.com origin. The script is inlined
        // rather than fetched so the page has no network dependency of its own.
        webView.loadDataWithBaseURL(
            BASE_URL,
            "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><title>orchard</title></head>" +
                "<body><script>$bundle</script></body></html>",
            "text/html",
            "utf-8",
            null,
        )
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onResult(requestId: String, json: String) {
            runCatching { JSONObject(json) }
                .onSuccess { results[requestId] = it }
                .onFailure { Log.w(TAG, "Unparseable PO token result", it) }
            pending.remove(requestId)?.countDown()
        }
    }

    /**
     * A token bound to [contentBinding] — a video id for a stream URL — or null when one cannot
     * be produced.
     *
     * Null is a normal answer, not an error: plenty of sessions still stream without a token, so
     * a failed mint degrades to the previous behaviour instead of blocking playback.
     */
    fun token(contentBinding: String): String? {
        if (contentBinding.isBlank()) return null
        tokens[contentBinding]?.takeIf { it.expiresAtMs > System.currentTimeMillis() }
            ?.let { return it.token }
        return synchronized(mintLock) {
            // Another caller may have minted this while we waited for the lock.
            tokens[contentBinding]?.takeIf { it.expiresAtMs > System.currentTimeMillis() }?.token
                ?: runCatching { mint(contentBinding) }
                    .onFailure { Log.w(TAG, "Could not mint a PO token for $contentBinding", it) }
                    .getOrNull()
        }
    }

    private fun mint(contentBinding: String): String? {
        if (!readyLatch.await(READY_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            Log.w(TAG, "PO token WebView did not become ready")
            return null
        }
        val webView = webViewRef.get() ?: return null
        val requestId = "orchard-${requestIds.incrementAndGet()}"
        val latch = CountDownLatch(1)
        pending[requestId] = latch

        mainHandler.post {
            webView.evaluateJavascript(
                "window.orchardMintPoToken(${JSONObject.quote(requestId)}," +
                    "${JSONObject.quote(contentBinding)});",
                null,
            )
        }

        if (!latch.await(MINT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            pending.remove(requestId)
            Log.w(TAG, "PO token mint timed out for $contentBinding")
            return null
        }
        val result = results.remove(requestId) ?: return null
        result.optString("error").takeIf(String::isNotBlank)?.let {
            Log.w(TAG, "PO token mint failed for $contentBinding: $it")
            return null
        }
        val token = result.optString("token").takeIf(String::isNotBlank) ?: return null
        // The mint is only valid while the integrity token behind it is. The bundle reports when
        // that expires; without a usable value, keep the entry briefly rather than forever.
        val minterExpiry = result.optLong("minterExpiresAt")
            .takeIf { it > System.currentTimeMillis() }
            ?: (System.currentTimeMillis() + DEFAULT_TOKEN_TTL_MS)
        tokens[contentBinding] = CachedToken(token, minterExpiry)
        Log.d(TAG, "Minted a PO token for $contentBinding")
        return token
    }

    /**
     * Discards a token the CDN refused, so the next attempt attests again rather than presenting
     * the same rejected proof. Re-attesting is the only useful response: the token is what was
     * rejected, and it does not become valid by being retried.
     */
    fun invalidate(contentBinding: String, rejectedToken: String) {
        val cached = tokens[contentBinding]
        if (rejectedToken.isNotBlank() && cached != null && cached.token != rejectedToken) return
        tokens.remove(contentBinding)
        webViewRef.get()?.let { webView ->
            mainHandler.post { webView.evaluateJavascript("window.orchardResetPoToken();", null) }
        }
        Log.d(TAG, "Dropped the PO token for $contentBinding after a rejection")
    }

    /**
     * Builds the BotGuard VM before it is needed. The first mint pays for downloading and running
     * the interpreter, which is the slowest part; later mints reuse it and cost almost nothing.
     */
    fun warmUp(contentBinding: String) {
        if (contentBinding.isBlank()) return
        runCatching { token(contentBinding) }
    }

    companion object {
        private const val TAG = "YouTubePoTokenMinter"
        private const val BRIDGE_NAME = "OrchardPoTokenBridge"
        private const val BUNDLE_ASSET = "yt_potoken.bundle.js"
        private const val BASE_URL = "https://www.youtube.com"
        /**
         * Both deadlines are deliberately short. Minting now happens inside the player request,
         * on the resolve path, where the whole fallback chain has 25 seconds to find a stream. A
         * mint that has not answered in a few seconds is not going to rescue this track, and
         * waiting on it spends the budget the remaining clients need. The measured happy path is
         * ~285ms for the first mint and ~0ms afterwards, so the ceiling only ever catches a
         * genuinely stuck WebView.
         */
        private const val READY_TIMEOUT_SECONDS = 6L
        private const val MINT_TIMEOUT_SECONDS = 8L
        private val DEFAULT_TOKEN_TTL_MS = TimeUnit.HOURS.toMillis(6)

        /**
         * Appends a minted token to a googlevideo URL, or returns it unchanged without one.
         *
         * Parsed with okhttp rather than `android.net.Uri` so the rule stays reachable from a
         * plain JVM test, the same reason [YouTubeStreamRequestIdentity] reads URLs that way.
         */
        fun withPoToken(url: String, token: String?): String {
            if (token.isNullOrBlank() || url.isBlank()) return url
            val parsed = url.toHttpUrlOrNull() ?: return url
            // Re-adding it would send two, and googlevideo reads the first.
            if (parsed.queryParameter(POT_PARAM) != null) return url
            return parsed.newBuilder().addQueryParameter(POT_PARAM, token).build().toString()
        }

        /** The token a URL is currently carrying, so a rejection can invalidate the right one. */
        fun poTokenOf(url: String): String =
            url.toHttpUrlOrNull()?.queryParameter(POT_PARAM).orEmpty()

        private const val POT_PARAM = "pot"
    }
}
