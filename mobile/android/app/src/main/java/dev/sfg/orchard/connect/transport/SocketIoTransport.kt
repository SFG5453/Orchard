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

package dev.sfg.orchard.connect.transport

import dev.sfg.orchard.connect.protocol.ConnectProtocol
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.io.Closeable
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * OkHttp bridge to the desktop's Socket.IO endpoint.
 *
 * Only the JSON subset used by Orchard Connect is implemented. OkHttp owns the
 * reader thread; callbacks are never assumed to run on Android's main thread.
 * Pending acknowledgements fail when the socket closes, preventing callback
 * retention across Activity recreation.
 */
class SocketIoTransport internal constructor(
    private val serverUrl: String,
    private val client: OkHttpClient,
    private val listener: ConnectTransport.Listener
) : ConnectTransport {
    private val nextAckId = AtomicInteger(1)
    private val acknowledgements = ConcurrentHashMap<Int, (Result<JSONObject>) -> Unit>()
    private val namespaceOpened = AtomicBoolean(false)
    @Volatile private var webSocket: WebSocket? = null

    override fun connect() {
        if (webSocket != null) return
        val request = Request.Builder().url(socketUrl()).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(webSocket, text)

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                releaseSocket(IllegalStateException("Socket closed ($code): $reason"))
                listener.onClosed(reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                releaseSocket(t)
                listener.onFailure(t)
            }
        })
    }

    override fun emit(event: String, payload: Any?, ack: ((Result<JSONObject>) -> Unit)?) {
        val ackId = ack?.let {
            nextAckId.getAndIncrement().also { id -> acknowledgements[id] = it }
        }
        val sent = webSocket?.send(SocketIoPacketCodec.event(event, payload, ackId)) == true
        if (!sent && ackId != null) {
            acknowledgements.remove(ackId)?.invoke(Result.failure(IllegalStateException("Socket is not open")))
        }
    }

    override fun close() {
        val socket = webSocket
        webSocket = null
        namespaceOpened.set(false)
        socket?.close(1000, "client closed")
        failAcknowledgements(IllegalStateException("Connect transport closed"))
    }

    private fun handleMessage(socket: WebSocket, text: String) {
        try {
            when (val packet = SocketIoPacketCodec.decode(text)) {
                SocketIoPacketCodec.Incoming.EngineOpened -> socket.send(SocketIoPacketCodec.namespaceOpen())
                SocketIoPacketCodec.Incoming.Ping -> socket.send(SocketIoPacketCodec.pong())
                SocketIoPacketCodec.Incoming.NamespaceOpened -> {
                    if (namespaceOpened.compareAndSet(false, true)) listener.onOpened()
                }
                is SocketIoPacketCodec.Incoming.Event -> listener.onEvent(packet.name, packet.payload)
                is SocketIoPacketCodec.Incoming.Ack -> acknowledgements.remove(packet.id)?.invoke(Result.success(packet.payload))
                is SocketIoPacketCodec.Incoming.Closed -> listener.onClosed(packet.reason)
                is SocketIoPacketCodec.Incoming.Ignored -> Unit
            }
        } catch (error: Throwable) {
            listener.onFailure(IllegalArgumentException("Invalid Socket.IO packet", error))
        }
    }

    private fun releaseSocket(error: Throwable) {
        webSocket = null
        namespaceOpened.set(false)
        failAcknowledgements(error)
    }

    private fun failAcknowledgements(error: Throwable) {
        val callbacks = acknowledgements.values.toList()
        acknowledgements.clear()
        callbacks.forEach { it(Result.failure(error)) }
    }

    private fun socketUrl(): String {
        val uri = URI(serverUrl)
        val scheme = if (uri.scheme.equals("https", ignoreCase = true)) "wss" else "ws"
        return "$scheme://${uri.rawAuthority}/socket.io/?EIO=${ConnectProtocol.ENGINE_IO_VERSION}&transport=websocket"
    }
}

/**
 * Activity-owned transport factory. [close] cancels sockets and terminates the
 * private OkHttp dispatcher; it must be called once when the owner is destroyed.
 */
class OkHttpSocketIoTransportFactory : ConnectTransport.Factory, Closeable {
    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    override fun create(serverUrl: String, listener: ConnectTransport.Listener): ConnectTransport =
        SocketIoTransport(serverUrl, client, listener)

    override fun close() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        client.cache?.close()
    }
}
