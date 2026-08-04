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

package dev.sfg.orchard.mobile.discord

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages direct Discord Gateway WebSocket connection for presence updates.
 */
class DiscordGatewayClient(
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow<GatewayConnectionState>(GatewayConnectionState.Disconnected)
    val connectionState: StateFlow<GatewayConnectionState> = mutableState.asStateFlow()

    private var webSocket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null
    private val sequenceNumber = AtomicInteger(-1)
    private var sessionId: String? = null
    private var resumeGatewayUrl: String? = null
    private var activeToken: String? = null
    private var lastActivityPayload: JSONObject? = null
    private val socketMutex = Mutex()
    private var isExplicitlyClosed = false
    private var reconnectAttempts = 0

    fun connect(token: String) {
        scope.launch {
            socketMutex.withLock {
                isExplicitlyClosed = false
                activeToken = token
                if (webSocket != null) return@withLock
                startConnection(token)
            }
        }
    }

    fun disconnect() {
        scope.launch {
            socketMutex.withLock {
                isExplicitlyClosed = true
                activeToken = null
                sessionId = null
                resumeGatewayUrl = null
                sequenceNumber.set(-1)
                lastActivityPayload = null
                heartbeatJob?.cancel()
                heartbeatJob = null
                reconnectJob?.cancel()
                reconnectJob = null
                webSocket?.close(1000, "Normal closure")
                webSocket = null
                mutableState.value = GatewayConnectionState.Disconnected
            }
        }
    }

    fun updateActivity(activity: DiscordPresenceActivity?) {
        scope.launch {
            socketMutex.withLock {
                val payload = buildPresenceUpdateJson(activity)
                lastActivityPayload = payload
                sendPayload(payload)
            }
        }
    }

    private fun startConnection(token: String) {
        mutableState.value = GatewayConnectionState.Connecting
        val url = resumeGatewayUrl?.let { "$it/?v=9&encoding=json" } ?: DISCORD_GATEWAY_URL
        val request = Request.Builder().url(url).build()

        webSocket = http.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Gateway WebSocket connected")
                mutableState.value = GatewayConnectionState.Connected
                reconnectAttempts = 0
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleGatewayMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Gateway WebSocket closing: $code / $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.d(TAG, "Gateway WebSocket closed: $code / $reason")
                handleSocketClosed(code)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Gateway WebSocket failure: ${t.message}")
                handleSocketClosed(-1)
            }
        })
    }

    private fun handleGatewayMessage(text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        val op = json.optInt("op", -1)
        val seq = json.optInt("s", -1)
        if (seq >= 0) sequenceNumber.set(seq)

        when (op) {
            GatewayOp.HELLO -> {
                val data = json.optJSONObject("d") ?: return
                val heartbeatInterval = data.optLong("heartbeat_interval", 41250L)
                startHeartbeat(heartbeatInterval)
                identifyOrResume()
            }
            GatewayOp.HEARTBEAT -> {
                sendHeartbeat()
            }
            GatewayOp.HEARTBEAT_ACK -> {
                // Heartbeat acknowledged by server
            }
            GatewayOp.DISPATCH -> {
                val eventName = json.optString("t")
                val data = json.optJSONObject("d")
                if (eventName == "READY" && data != null) {
                    val sId = data.optString("session_id")
                    val resumeUrl = data.optString("resume_gateway_url").takeIf(String::isNotBlank)
                    sessionId = sId
                    if (resumeUrl != null) resumeGatewayUrl = resumeUrl
                    mutableState.value = GatewayConnectionState.Ready(sId, resumeUrl)
                    Log.d(TAG, "Gateway READY; session_id=$sId")

                    // Resend last activity if pending
                    lastActivityPayload?.let(::sendPayload)
                } else if (eventName == "RESUMED") {
                    mutableState.value = GatewayConnectionState.Ready(sessionId.orEmpty(), resumeGatewayUrl)
                    Log.d(TAG, "Gateway session RESUMED")
                    lastActivityPayload?.let(::sendPayload)
                }
            }
            GatewayOp.RECONNECT -> {
                Log.i(TAG, "Gateway requested RECONNECT")
                scheduleReconnect(delayMs = 1000L)
            }
            GatewayOp.INVALID_SESSION -> {
                val resumable = json.optBoolean("d", false)
                Log.w(TAG, "Gateway INVALID_SESSION (resumable=$resumable)")
                if (!resumable) {
                    sessionId = null
                    resumeGatewayUrl = null
                }
                scheduleReconnect(delayMs = 2000L)
            }
        }
    }

    private fun identifyOrResume() {
        val token = activeToken ?: return
        val sId = sessionId
        val seq = sequenceNumber.get()

        if (sId != null && seq >= 0) {
            // Send Resume (Op 6)
            val resumePayload = JSONObject().apply {
                put("op", GatewayOp.RESUME)
                put("d", JSONObject().apply {
                    put("token", "Bearer $token")
                    put("session_id", sId)
                    put("seq", seq)
                })
            }
            sendPayload(resumePayload)
        } else {
            // Send Identify (Op 2)
            val capabilities = (1 shl 4) or (1 shl 5) or (1 shl 12) or (1 shl 16)
            val intents = (1 shl 12) or (1 shl 18) or (1 shl 19) or (1 shl 22) or (1 shl 23) or (1 shl 27) or (1 shl 28) or (1 shl 29)
            val identifyPayload = JSONObject().apply {
                put("op", GatewayOp.IDENTIFY)
                put("d", JSONObject().apply {
                    put("capabilities", capabilities)
                    put("intents", intents)
                    put("token", "Bearer $token")
                    put("properties", JSONObject().apply {
                        put("os", "Android")
                        put("browser", "Orchard")
                        put("device", "Android")
                        put("browser_user_agent", "Orchard")
                        put("browser_version", "1.0")
                        put("client_version", "1.0")
                        put("client_build_number", 1)
                        put("native_build_number", 1)
                        put("release_channel", "unknown")
                    })
                })
            }
            sendPayload(identifyPayload)
        }
    }

    private fun startHeartbeat(intervalMs: Long) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch(Dispatchers.IO) {
            val jitter = (Math.random() * intervalMs).toLong()
            delay(jitter)
            while (isActive) {
                sendHeartbeat()
                delay(intervalMs)
            }
        }
    }

    private fun sendHeartbeat() {
        val seq = sequenceNumber.get()
        val payload = JSONObject().apply {
            put("op", GatewayOp.HEARTBEAT)
            if (seq >= 0) put("d", seq) else put("d", JSONObject.NULL)
        }
        sendPayload(payload)
    }

    private fun sendPayload(payload: JSONObject) {
        val text = payload.toString()
        Log.d(TAG, "Gateway send: op=${payload.optInt("op")} text=$text")
        webSocket?.send(text)
    }

    private fun buildPresenceUpdateJson(activity: DiscordPresenceActivity?): JSONObject {
        val activitiesArray = JSONArray()
        if (activity != null) {
            activitiesArray.put(activity.toJson())
        }
        return JSONObject().apply {
            put("op", GatewayOp.PRESENCE_UPDATE)
            put("d", JSONObject().apply {
                put("since", JSONObject.NULL)
                put("activities", activitiesArray)
                put("status", "online")
                put("afk", false)
            })
        }
    }

    private fun handleSocketClosed(code: Int) {
        heartbeatJob?.cancel()
        heartbeatJob = null
        webSocket = null
        mutableState.value = GatewayConnectionState.Disconnected

        if (isExplicitlyClosed) return
        if (code in 4004..4014) {
            Log.e(TAG, "Gateway closed with unrecoverable code $code")
            return
        }

        scheduleReconnect()
    }

    private fun scheduleReconnect(delayMs: Long? = null) {
        reconnectJob?.cancel()
        val waitTime = delayMs ?: (1000L * (1 shl reconnectAttempts.coerceAtMost(5))).coerceAtMost(30_000L)
        reconnectAttempts++

        reconnectJob = scope.launch(Dispatchers.IO) {
            delay(waitTime)
            socketMutex.withLock {
                if (!isExplicitlyClosed && activeToken != null) {
                    startConnection(activeToken!!)
                }
            }
        }
    }

    companion object {
        private const val TAG = "DiscordGateway"
    }
}
