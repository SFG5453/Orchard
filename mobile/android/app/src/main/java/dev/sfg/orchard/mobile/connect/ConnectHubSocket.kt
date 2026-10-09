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

package dev.sfg.orchard.mobile.connect

import dev.sfg.orchard.mobile.auth.OrchardAccountService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * The account service's Connect hub (`services/account` `/connect/hub`), with the Orchard access
 * token as a WebSocket subprotocol. Reconnects with backoff and hands the hub fresh tokens.
 * Callbacks arrive on OkHttp threads.
 */
internal class ConnectHubSocket(
    http: OkHttpClient,
    private val account: OrchardAccountService,
    private val scope: CoroutineScope,
    private val onOpen: () -> Unit,
    private val onMessage: (String) -> Unit,
    private val onClosed: () -> Unit,
) {
    // A dedicated client: pings keep NATs open, and the hub socket never times out reading.
    private val client = http.newBuilder()
        .pingInterval(25, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
    private val lock = Any()
    private var socket: WebSocket? = null
    private var job: Job? = null
    private var generation = 0L
    @Volatile private var lastToken = ""

    fun start() = synchronized(lock) {
        if (job?.isActive == true) return
        val current = ++generation
        job = scope.launch {
            var attempt = 0
            while (isActive) {
                val opened = runCatching { connectOnce(current) }.getOrDefault(false)
                if (opened) attempt = 0
                delay(minOf(60_000L, 2_000L shl minOf(attempt, 5)))
                attempt += 1
            }
        }
    }

    fun stop() = synchronized(lock) {
        ++generation
        job?.cancel()
        job = null
        socket?.close(1000, "signed out")
        socket = null
    }

    fun send(text: String) {
        synchronized(lock) { socket }?.send(text)
    }

    /** Runs one socket until it closes. True when it got as far as opening. */
    private suspend fun connectOnce(current: Long): Boolean {
        val token = account.accessToken() ?: return false
        lastToken = token
        val closed = CompletableDeferred<Boolean>()
        val url = account.serviceUrl.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") +
            "/connect/hub"
        val request = Request.Builder()
            .url(url)
            // The token rides in the subprotocol list, the one header every WebSocket client can set.
            .header("Sec-WebSocket-Protocol", "orchard-connect.2, bearer.$token")
            .build()
        var opened = false
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (!isCurrent(current)) return
                opened = true
                onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (isCurrent(current)) onMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish()

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = finish()

            private fun finish() {
                if (opened && isCurrent(current)) onClosed()
                closed.complete(opened)
            }
        }
        val created = client.newWebSocket(request, listener)
        synchronized(lock) {
            if (!isCurrent(current)) {
                created.cancel()
                return false
            }
            socket = created
        }
        val refresher = scope.launch { refreshTokens(current) }
        try {
            return closed.await()
        } finally {
            refresher.cancel()
            synchronized(lock) { if (socket === created) socket = null }
        }
    }

    // The account client reuses a token until shortly before expiry; forward each new one.
    private suspend fun refreshTokens(current: Long) {
        while (isCurrent(current)) {
            delay(30_000)
            val token = account.accessToken() ?: continue
            if (token == lastToken) continue
            lastToken = token
            send(JSONObject().put("type", "refresh").put("token", token).toString())
        }
    }

    private fun isCurrent(current: Long) = synchronized(lock) { current == generation }
}
