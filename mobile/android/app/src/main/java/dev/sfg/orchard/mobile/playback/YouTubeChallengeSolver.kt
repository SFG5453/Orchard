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
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Solves YouTube JS challenges (signature deciphering and 'n' parameter transform)
 * using a bundled pure JavaScript solver engine executed inside a headless WebView.
 */
class YouTubeChallengeSolver(
    private val context: Context,
    private val client: OkHttpClient,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bundleJs: String by lazy {
        context.assets.open("yt_solver.bundle.js").bufferedReader().use { it.readText() }
    }

    private val playerCache = ConcurrentHashMap<String, String>()
    private var webViewRef = AtomicReference<WebView?>()
    private val readyLatch = CountDownLatch(1)

    init {
        mainHandler.post {
            try {
                initWebView()
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to initialize challenge solver WebView", t)
                readyLatch.countDown()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initWebView() {
        val wv = WebView(context.applicationContext)
        val settings = wv.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.cacheMode = WebSettings.LOAD_NO_CACHE

        wv.evaluateJavascript(bundleJs) {
            Log.d(TAG, "YouTube challenge solver bundle evaluated")
            webViewRef.set(wv)
            readyLatch.countDown()
        }
    }

    fun getPlayerJs(playerUrl: String = DEFAULT_PLAYER_URL): String {
        playerCache[playerUrl]?.let { return it }
        val fullUrl = if (playerUrl.startsWith("http")) playerUrl else "https://www.youtube.com$playerUrl"
        val req = Request.Builder()
            .url(fullUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .build()
        client.newCall(req).execute().use { resp ->
            check(resp.isSuccessful) { "Failed to download player JS: ${resp.code}" }
            val js = resp.body.string()
            playerCache[playerUrl] = js
            return js
        }
    }

    /**
     * Deciphers an encrypted signature and 'n' parameter for a given stream URL.
     */
    fun decipherUrl(
        streamUrl: String,
        encryptedSig: String?,
        sigParam: String = "sig",
        playerUrl: String = DEFAULT_PLAYER_URL,
    ): String {
        val playerJs = getPlayerJs(playerUrl)
        val uri = android.net.Uri.parse(streamUrl)
        val nVal = uri.getQueryParameter("n")

        val (solvedSig, solvedN) = solve(playerJs, encryptedSig, nVal)

        val uriBuilder = uri.buildUpon()
        if (solvedSig != null) {
            uriBuilder.appendQueryParameter(sigParam, solvedSig)
        }
        if (solvedN != null) {
            // Replace the 'n' parameter
            val queryParams = mutableMapOf<String, String>()
            for (name in uri.queryParameterNames) {
                if (name == "n") {
                    queryParams["n"] = solvedN
                } else {
                    uri.getQueryParameter(name)?.let { queryParams[name] = it }
                }
            }
            if (!queryParams.containsKey("n")) {
                queryParams["n"] = solvedN
            }
            uriBuilder.clearQuery()
            for ((k, v) in queryParams) {
                uriBuilder.appendQueryParameter(k, v)
            }
            if (solvedSig != null && !queryParams.containsKey(sigParam)) {
                uriBuilder.appendQueryParameter(sigParam, solvedSig)
            }
        }

        return uriBuilder.build().toString()
    }

    /**
     * Solves challenges synchronously using the headless WebView.
     */
    fun solve(playerJs: String, sig: String?, n: String?): Pair<String?, String?> {
        readyLatch.await(5, TimeUnit.SECONDS)
        val wv = webViewRef.get() ?: error("WebView solver not ready")

        val resultRef = AtomicReference<String?>()
        val evalLatch = CountDownLatch(1)

        val sigsArray = if (sig != null) "[\"${JSONObject.quote(sig).drop(1).dropLast(1)}\"]" else "[]"
        val nsArray = if (n != null) "[\"${JSONObject.quote(n).drop(1).dropLast(1)}\"]" else "[]"

        val script = """
            (function() {
                try {
                    return window.solveYouTubeChallenges(${JSONObject.quote(playerJs)}, $sigsArray, $nsArray);
                } catch(e) {
                    return JSON.stringify({ type: "error", error: e.toString() });
                }
            })();
        """.trimIndent()

        mainHandler.post {
            wv.evaluateJavascript(script) { rawResult ->
                resultRef.set(rawResult)
                evalLatch.countDown()
            }
        }

        evalLatch.await(10, TimeUnit.SECONDS)
        val raw = resultRef.get() ?: error("Solver evaluation timed out")
        // evaluateJavascript returns a JSON-encoded string (or raw string with quotes)
        val cleanJson = if (raw.startsWith("\"") && raw.endsWith("\"")) {
            // Unescape JSON string
            JSONObject("{\"v\":$raw}").getString("v")
        } else {
            raw
        }

        val json = JSONObject(cleanJson)
        if (json.optString("type") == "error") {
            error("Solver failed: ${json.optString("error")}")
        }

        val responses = json.optJSONArray("responses") ?: error("Invalid solver response: $cleanJson")
        var solvedSig: String? = null
        var solvedN: String? = null

        for (i in 0 until responses.length()) {
            val resp = responses.optJSONObject(i) ?: continue
            val data = resp.optJSONObject("data") ?: continue
            if (sig != null && data.has(sig)) {
                solvedSig = data.optString(sig).takeIf(String::isNotBlank)
            }
            if (n != null && data.has(n)) {
                solvedN = data.optString(n).takeIf(String::isNotBlank)
            }
        }

        return Pair(solvedSig, solvedN)
    }

    companion object {
        const val DEFAULT_PLAYER_URL = "/s/player/854a788e/player_ias.vflset/en_US/base.js"
        private const val TAG = "YouTubeChallengeSolver"
    }
}
