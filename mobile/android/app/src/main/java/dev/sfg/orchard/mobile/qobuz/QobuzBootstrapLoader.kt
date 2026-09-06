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

package dev.sfg.orchard.mobile.qobuz

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64

class QobuzBootstrapLoader(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val maxAgeMs: Long = 6 * 60 * 60_000L,
) {
    private val mutex = Mutex()
    @Volatile
    private var cached: CachedBootstrap? = null

    private data class CachedBootstrap(
        val bootstrap: QobuzBootstrap,
        val expiresAtMs: Long,
    )

    suspend fun get(refresh: Boolean = false): QobuzBootstrap = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!refresh) {
            cached?.takeIf { it.expiresAtMs > now }?.let { return@withContext it.bootstrap }
        }
        mutex.withLock {
            if (!refresh) {
                cached?.takeIf { it.expiresAtMs > System.currentTimeMillis() }?.let { return@withContext it.bootstrap }
            }
            val loaded = fetchBootstrap()
            cached = CachedBootstrap(loaded, System.currentTimeMillis() + maxAgeMs)
            loaded
        }
    }

    fun clear() {
        cached = null
    }

    private fun fetchBootstrap(): QobuzBootstrap {
        val loginRequest = Request.Builder()
            .url("$QOBUZ_PLAY_URL/login")
            .header("User-Agent", QOBUZ_USER_AGENT)
            .build()
        val loginHtml = httpClient.newCall(loginRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Qobuz login bootstrap failed (${response.code})")
            }
            response.body.string()
        }

        val bundleRegex = Regex("""<script[^>]+src=["'](/resources/[^"']+/bundle\.js)["'][^>]*></script>""")
        val bundleMatch = bundleRegex.find(loginHtml)
            ?: throw IllegalStateException("Could not derive Qobuz web bundle URL from the current web player")
        val bundlePath = bundleMatch.groupValues[1]

        val bundleRequest = Request.Builder()
            .url("$QOBUZ_PLAY_URL$bundlePath")
            .header("User-Agent", QOBUZ_USER_AGENT)
            .build()
        val bundleJs = httpClient.newCall(bundleRequest).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Qobuz web bundle failed (${response.code})")
            }
            response.body.string()
        }

        return extractBootstrap(bundleJs, bundlePath)
    }

    companion object {
        fun extractBootstrap(bundle: String, bundlePath: String = ""): QobuzBootstrap {
            val appIdRegex = Regex("""production:\{api:\{appId:"(\d{9})",appSecret:"[^"]+"""")
            val appId = appIdRegex.find(bundle)?.groupValues?.get(1)
                ?: throw IllegalStateException("Could not derive Qobuz app id from the current web player")

            val oauthKeyRegex = Regex("""authenticate\(\{privateKey:"([^"]+)",code:""")
            val oauthPrivateKey = oauthKeyRegex.find(bundle)?.groupValues?.get(1)
                ?: throw IllegalStateException("Could not derive Qobuz OAuth key from the current web player")

            val seedRegex = Regex("""initialSeed\("([^"]+)",window\.utimezone\.berlin\)""")
            val seed = seedRegex.find(bundle)?.groupValues?.get(1)
                ?: throw IllegalStateException("Could not derive Qobuz stream initialization seed from the current web player")

            val tzRegex = Regex("""name:"Europe/Berlin",info:"([^"]+)",extras:"([^"]+)"""")
            val tzMatch = tzRegex.find(bundle)
                ?: throw IllegalStateException("Could not derive Qobuz stream initialization data from the current web player")

            val encoded = "$seed${tzMatch.groupValues[1]}${tzMatch.groupValues[2]}"
            if (encoded.length < 44) {
                throw IllegalStateException("Qobuz stream initialization string is too short")
            }
            val slice = encoded.substring(0, encoded.length - 44)
            val decodedBytes = Base64.getDecoder().decode(slice)
            val rngInit = String(decodedBytes, Charsets.UTF_8)
            if (!rngInit.matches(Regex("^[a-fA-F0-9]{32}$"))) {
                throw IllegalStateException("The current Qobuz stream initialization data has an unknown format")
            }

            return QobuzBootstrap(
                appId = appId,
                oauthPrivateKey = oauthPrivateKey,
                rngInit = rngInit,
                bundlePath = bundlePath,
            )
        }
    }
}
