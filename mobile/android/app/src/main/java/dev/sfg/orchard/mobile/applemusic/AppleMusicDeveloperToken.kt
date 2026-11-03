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

package dev.sfg.orchard.mobile.applemusic

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

internal data class AppleMusicDeveloperToken(val value: String, val expiresAt: Long)

internal class AppleMusicSignInException(message: String) : Exception(message)

internal fun parseAppleMusicDeveloperToken(body: String, now: Long): AppleMusicDeveloperToken {
    val json = JSONObject(body)
    val token = json.getString("developer_token")
    val expiry = json.getLong("expires_at")
    require(token.length <= 16_384 && token.matches(Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+"))) {
        "Invalid Apple Music developer token"
    }
    require(expiry > now + 60 && expiry <= now + 15_777_000) { "Invalid Apple Music token expiry" }
    return AppleMusicDeveloperToken(token, expiry)
}

internal class AppleMusicDeveloperTokenClient(serviceUrl: String, path: String = "/apple/developer-token") {
    private val url = "${serviceUrl.trimEnd('/')}$path"
    private val http = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()

    suspend fun fetch(accessToken: String): AppleMusicDeveloperToken = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Accept", "application/json")
            .header("Authorization", "Bearer $accessToken").build()
        http.newCall(request).execute().use { response ->
            when (response.code) {
                401 -> throw AppleMusicSignInException("Sign in to your Orchard account again.")
                429 -> throw AppleMusicSignInException(
                    if ("apple_daily_limit" in response.peekBody(4_096).string()) "Apple Music's daily limit for this account was reached. Try again tomorrow."
                    else "Too many attempts. Try again in a minute.",
                )
                503 -> throw AppleMusicSignInException("Apple Music sign-in is unavailable. Try again later.")
            }
            if (!response.isSuccessful) throw AppleMusicSignInException("Could not start Apple Music sign-in. Try again later.")
            val body = response.body
            require(body.contentLength() <= 24_576) { "Invalid Apple Music sign-in response" }
            val source = body.source()
            require(!source.request(24_577)) { "Invalid Apple Music sign-in response" }
            parseAppleMusicDeveloperToken(source.readUtf8(), System.currentTimeMillis() / 1000)
        }
    }
}
