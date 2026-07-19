package dev.sfg.orchard.connect

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class OrchardSocket(
    private val serverUrl: String,
    private val listener: Listener
) {
    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onError(message: String)
        fun onEvent(name: String, payload: Any?)
    }

    private val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val ackId = AtomicInteger(1)
    private val acks = ConcurrentHashMap<Int, (JSONObject) -> Unit>()
    private var webSocket: WebSocket? = null
    private var openedNamespace = false

    fun connect() {
        val request = Request.Builder().url(socketUrl()).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) = handleMessage(text)
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = listener.onDisconnected()
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                listener.onError(t.message ?: "Desktop unreachable")
            }
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "closed")
        webSocket = null
        openedNamespace = false
        acks.clear()
    }

    fun emit(event: String, payload: Any? = JSONObject.NULL, ack: ((JSONObject) -> Unit)? = null) {
        val id = if (ack == null) "" else ackId.getAndIncrement().also { acks[it] = ack }.toString()
        val body = JSONArray().put(event).put(payload ?: JSONObject.NULL)
        webSocket?.send("42$id$body")
    }

    private fun handleMessage(text: String) {
        when {
            text == "2" -> webSocket?.send("3")
            text.startsWith("0") -> webSocket?.send("40")
            text.startsWith("40") && !openedNamespace -> {
                openedNamespace = true
                listener.onConnected()
            }
            text.startsWith("42") -> handleEvent(text.drop(2))
            text.startsWith("43") -> handleAck(text.drop(2))
            text.startsWith("41") -> listener.onDisconnected()
        }
    }

    private fun handleEvent(raw: String) {
        val payload = JSONArray(raw.dropWhile { it.isDigit() })
        val name = payload.optString(0)
        if (name.isNotEmpty()) listener.onEvent(name, payload.opt(1))
    }

    private fun handleAck(raw: String) {
        val digits = raw.takeWhile { it.isDigit() }
        val id = digits.toIntOrNull() ?: return
        val payload = JSONArray(raw.drop(digits.length))
        val response = payload.optJSONObject(0) ?: JSONObject()
        acks.remove(id)?.invoke(response)
    }

    private fun socketUrl(): String {
        val uri = URI(serverUrl)
        val scheme = if (uri.scheme == "https") "wss" else "ws"
        val port = if (uri.port >= 0) ":${uri.port}" else ""
        return "$scheme://${uri.host}$port/socket.io/?EIO=4&transport=websocket"
    }
}
