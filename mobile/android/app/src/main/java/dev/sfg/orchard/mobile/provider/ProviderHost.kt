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

package dev.sfg.orchard.mobile.provider

import java.io.IOException
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

class ProviderException(message: String) : IOException(message)

/** Bytecode bundles the native host embeds; the ordinal is the JNI selector. */
enum class ProviderBundle { YouTube, Qobuz }

/**
 * A desktop provider from the repo `providers` directory, running in the shared QuickJS host.
 * Every native call happens on one thread; OkHttp and timers hop back onto it.
 */
open class ProviderHost(
    http: OkHttpClient,
    private val bundleKind: ProviderBundle,
    private val bundle: (String) -> ByteArray?,
) {
    // Mirrors desktop: no cookie jar (providers sign their own cookie snapshot),
    // HTTP/1.1 only, and no redirect from HTTPS down to HTTP.
    private val http = http.newBuilder()
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .protocols(listOf(Protocol.HTTP_1_1))
        .followSslRedirects(false)
        .build()

    // QuickJS may use a 2 MiB stack; ART threads default to 1 MiB.
    protected val thread = ScheduledThreadPoolExecutor(1) { task ->
        Thread(null, task, "OrchardProvider-${bundleKind.name}", 8L shl 20).apply { isDaemon = true }
    }.apply { removeOnCancelPolicy = true }

    private class Reply(val binary: Boolean, val data: ByteArray)

    private var handle = 0L
    private var nextRequest = 0L
    private val requests = HashMap<Long, (Boolean, Boolean, ByteArray) -> Unit>()
    private val calls = HashMap<Long, Call>()
    private val timers = HashMap<Int, ScheduledFuture<*>>()

    private suspend fun call(method: String, payload: JSONObject): Reply =
        suspendCancellableCoroutine { continuation ->
            val body = payload.toString().toByteArray()
            thread.execute {
                if (handle == 0L) handle = ProviderNative.create(this, bundleKind.ordinal)
                val id = ++nextRequest
                requests[id] = { ok, binary, data ->
                    if (ok) continuation.resume(Reply(binary, data))
                    else continuation.resumeWithException(ProviderException(data.decodeToString()))
                }
                ProviderNative.invoke(handle, id, method, body)
            }
        }

    /** Resolves with the method's JSON result text. */
    suspend fun invokeRaw(method: String, payload: JSONObject = JSONObject()): String {
        val reply = call(method, payload)
        if (reply.binary) throw ProviderException("$method returned bytes where JSON was expected")
        return reply.data.decodeToString()
    }

    /** Resolves with a method that returns a top-level Uint8Array. */
    suspend fun invokeBytes(method: String, payload: JSONObject = JSONObject()): ByteArray {
        val reply = call(method, payload)
        if (!reply.binary) throw ProviderException("$method returned JSON where bytes were expected")
        return reply.data
    }

    suspend fun invoke(method: String, payload: JSONObject = JSONObject()): JSONObject =
        JSONObject(invokeRaw(method, payload))

    suspend fun invokeArray(method: String, payload: JSONObject = JSONObject()): JSONArray =
        JSONArray(invokeRaw(method, payload))

    suspend fun invokeValue(method: String, payload: JSONObject = JSONObject()): Any? =
        JSONTokener(invokeRaw(method, payload)).nextValue().takeUnless { it == JSONObject.NULL }

    /** In-flight requests fail with a transport error, which JS sees as a rejected fetch. */
    fun cancelAll() {
        thread.execute { calls.values.toList().forEach(Call::cancel) }
    }

    // Native callbacks below run on [thread] and must not throw.

    @Suppress("unused")
    private fun fetch(id: Long, url: String, method: String, body: ByteArray, headers: Array<String>) {
        val request = runCatching {
            val builder = Headers.Builder()
            for (index in 0 until headers.size - 1 step 2) builder.addUnsafeNonAscii(headers[index], headers[index + 1])
            val requestBody = if (method == "GET" || method == "HEAD") null else body.toRequestBody()
            Request.Builder().url(url).headers(builder.build()).method(method, requestBody).build()
        }.getOrElse { error ->
            thread.execute { complete(id, error.message ?: "Invalid request", null) }
            return
        }
        val call = http.newCall(request)
        calls[id] = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                thread.execute { complete(id, e.message ?: "Network request failed", null) }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = runCatching { response.use { it.body.bytes() } }
                thread.execute {
                    body.fold({ complete(id, null, response to it) }, { complete(id, it.message ?: "Read failed", null) })
                }
            }
        })
    }

    private fun complete(id: Long, error: String?, result: Pair<Response, ByteArray>?) {
        calls.remove(id)
        if (handle == 0L) return
        if (result == null) {
            ProviderNative.completeFetch(handle, id, 0, error.orEmpty(), "", "", false, "", ByteArray(0), emptyArray())
            return
        }
        val (response, body) = result
        // Qt joins repeated headers; keep one entry per name the same way.
        val headers = response.headers.names().flatMap { name ->
            val values = response.headers.values(name)
            listOf(name, values.joinToString(if (name.equals("set-cookie", true)) "\n" else ", "))
        }.toTypedArray()
        ProviderNative.completeFetch(
            handle, id, response.code, "", response.message, response.request.url.toString(),
            response.priorResponse != null, response.header("Content-Type").orEmpty(), body, headers,
        )
    }

    @Suppress("unused")
    private fun startTimer(id: Int, delayMs: Int, repeat: Boolean) {
        val fire = Runnable {
            if (!repeat) timers.remove(id)
            if (handle != 0L) ProviderNative.fireTimer(handle, id)
        }
        timers[id] = if (repeat) {
            val period = delayMs.coerceAtLeast(1).toLong()
            thread.scheduleWithFixedDelay(fire, period, period, TimeUnit.MILLISECONDS)
        } else {
            thread.schedule(fire, delayMs.toLong(), TimeUnit.MILLISECONDS)
        }
    }

    @Suppress("unused")
    private fun stopTimer(id: Int) {
        timers.remove(id)?.cancel(false)
    }

    @Suppress("unused")
    private fun loadBundle(name: String): ByteArray? = runCatching { bundle(name) }.getOrNull()

    // Providers without a persistent player cache keep these defaults.
    @Suppress("unused")
    protected open fun readPlayerCache(): ByteArray? = null

    @Suppress("unused")
    protected open fun writePlayerCache(data: ByteArray): Boolean = false

    @Suppress("unused")
    private fun settled(requestId: Long, ok: Boolean, result: ByteArray) {
        requests.remove(requestId)?.invoke(ok, false, result)
    }

    @Suppress("unused")
    private fun settledBytes(requestId: Long, result: ByteArray) {
        requests.remove(requestId)?.invoke(true, true, result)
    }

    // Web Crypto is absent from QuickJS; segmented streams reach the JCA through these.
    @Suppress("unused")
    private fun hkdfSha256(key: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray? =
        runCatching { MediaCrypto.hkdfSha256(key, salt, info, length) }.getOrNull()

    @Suppress("unused")
    private fun aes128(mode: Int, key: ByteArray, iv: ByteArray, data: ByteArray): ByteArray? =
        runCatching { MediaCrypto.aes128(mode, key, iv, data) }.getOrNull()
}

internal object ProviderNative {
    init { System.loadLibrary("orchard_js") }

    @JvmStatic external fun create(owner: ProviderHost, bundle: Int): Long
    @JvmStatic external fun invoke(handle: Long, requestId: Long, method: String, payload: ByteArray)
    @JvmStatic external fun completeFetch(
        handle: Long, fetchId: Long, status: Int, error: String, statusText: String, url: String,
        redirected: Boolean, contentType: String, body: ByteArray, headers: Array<String>,
    )
    @JvmStatic external fun fireTimer(handle: Long, id: Int)
}
