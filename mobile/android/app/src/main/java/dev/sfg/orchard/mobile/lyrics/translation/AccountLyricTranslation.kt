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

package dev.sfg.orchard.mobile.lyrics.translation

import dev.sfg.orchard.mobile.auth.OrchardAccountService
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject

/** The whole song is one admission; the Worker owns batching, cache, model, and limits. */
internal class AccountLyricTranslation(http: OkHttpClient, private val account: OrchardAccountService) {
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false)
        .callTimeout(130, TimeUnit.SECONDS).readTimeout(130, TimeUnit.SECONDS).build()
    private val quotaClient = client.newBuilder().callTimeout(15, TimeUnit.SECONDS).build()
    private val mutableQuota = MutableStateFlow<AccountTranslationQuota?>(null)
    val quota = mutableQuota.asStateFlow()
    private val accountGeneration = AtomicInteger()
    private val quotaVersion = AtomicInteger()

    fun clearQuota() {
        accountGeneration.incrementAndGet()
        quotaVersion.incrementAndGet()
        mutableQuota.value = null
    }

    suspend fun refreshQuota() {
        if (!account.state.value.signedIn) { clearQuota(); return }
        if ((quota.value?.resetsAt ?: 0) <= System.currentTimeMillis() / 1000) mutableQuota.value = null
        request("/lyrics/quota")
    }

    suspend fun translate(songId: String, lines: List<String>, target: String): List<String> {
        check(account.state.value.signedIn) { "Sign in to Orchard to translate lyrics." }
        require(songId.isNotBlank()) { "This song has no ID." }
        val payload = JSONObject().put("song_id", songId).put("lines", JSONArray(lines)).put("target_language", target)
        val body = request("/lyrics/translate", payload)
        return parseAccountLines(body, lines.size) ?: error("The translation returned an invalid number of lines.")
    }

    private suspend fun request(path: String, payload: JSONObject? = null): String {
        val identity = account.state.value.let { it.email to it.deviceId }
        val generation = accountGeneration.get()
        val version = quotaVersion.get()
        val token = account.accessToken() ?: error("Sign in to Orchard again.")
        check(identity == account.state.value.let { it.email to it.deviceId }) { "Sign in to Orchard again." }
        val builder = Request.Builder().url("${account.serviceUrl}$path")
            .header("Authorization", "Bearer $token")
        if (payload != null) builder.post(payload.toString().toRequestBody("application/json".toMediaType()))
        return suspendCancellableCoroutine { continuation ->
            val call = (if (payload == null) quotaClient else client).newCall(builder.build())
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(IOException(if (e is java.io.InterruptedIOException)
                        "Translation took too long. Try again." else "Account translation network error."))
                }
                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        if (!continuation.isActive) return
                        val body = runCatching { it.body.string() }.getOrElse {
                            continuation.resumeWithException(IOException("Could not read account translation."))
                            return
                        }
                        val json = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
                        if (generation == accountGeneration.get() && identity == account.state.value.let { state -> state.email to state.deviceId } &&
                            (payload != null || version == quotaVersion.get())) {
                            if (payload != null) quotaVersion.incrementAndGet()
                            mutableQuota.value = parseAccountQuota(json)
                        }
                        if (!it.isSuccessful) {
                            val code = json.optString("error")
                            val message = when {
                                code == "translation_busy" -> "Translation is busy. Try again shortly."
                                code == "translation_invalid_result" -> "The model returned invalid lyric lines. Try again."
                                it.code == 504 -> "Translation took too long. Try again."
                                it.code == 429 -> "No translations left today. Resets at 00:00 UTC. Cached songs are still available."
                                it.code == 409 -> "This song is being translated. Try again shortly."
                                it.code == 401 -> "Sign in to Orchard again."
                                else -> "Account translation failed. Try again later."
                            }
                            continuation.resumeWithException(IOException(message))
                        } else {
                            continuation.resume(body)
                        }
                    }
                }
            })
        }
    }
}

internal fun parseAccountLines(body: String, count: Int): List<String>? {
    val lines = runCatching { JSONObject(body).getJSONArray("lines") }.getOrNull() ?: return null
    if (lines.length() != count) return null
    return (0 until count).map { index ->
        (lines.opt(index) as? String)?.trim()?.takeIf(String::isNotEmpty) ?: return null
    }
}
