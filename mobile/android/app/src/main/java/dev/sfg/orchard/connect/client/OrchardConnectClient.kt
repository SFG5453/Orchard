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

package dev.sfg.orchard.connect.client

import dev.sfg.orchard.connect.protocol.ConnectClientError
import dev.sfg.orchard.connect.protocol.ConnectClientStatus
import dev.sfg.orchard.connect.protocol.ConnectCommand
import dev.sfg.orchard.connect.protocol.ConnectJsonCodec
import dev.sfg.orchard.connect.protocol.ConnectProtocol
import dev.sfg.orchard.connect.protocol.ConnectResults
import dev.sfg.orchard.connect.protocol.ConnectSnapshot
import dev.sfg.orchard.connect.protocol.HelloStatus
import dev.sfg.orchard.connect.session.ConnectSessionStore
import dev.sfg.orchard.connect.session.SecureDeviceTokenGenerator
import dev.sfg.orchard.connect.transport.ConnectTransport
import org.json.JSONObject
import java.net.URI
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong

/**
 * Lifecycle owner for pairing, authorization, commands, and state replication.
 *
 * One instance owns at most one transport. Transport callbacks may arrive on
 * OkHttp threads; every public listener callback is posted to [callbackExecutor].
 * A monotonically increasing generation drops callbacks from a replaced socket.
 * Call [close] exactly once when the application owner is destroyed.
 */
class OrchardConnectClient(
    private val transportFactory: ConnectTransport.Factory,
    private val sessionStore: ConnectSessionStore,
    private val tokenGenerator: SecureDeviceTokenGenerator,
    private val deviceName: String,
    private val callbackExecutor: Executor,
    private val listener: Listener
) {
    interface Listener {
        fun onStatusChanged(status: ConnectClientStatus)
        fun onSnapshot(snapshot: ConnectSnapshot)
        fun onSearchResults(results: ConnectResults)
        fun onLibraryResults(results: ConnectResults)
        fun onError(error: ConnectClientError)
    }

    private val generation = AtomicLong(0)
    private val lock = Any()
    @Volatile private var status = ConnectClientStatus.DISCONNECTED
    @Volatile private var protocolVersion = 1
    private var transport: ConnectTransport? = null
    private var proposedDeviceToken = ""

    fun status(): ConnectClientStatus = status
    fun protocolVersion(): Int = protocolVersion

    fun connect(serverUrl: String, pairingToken: String = "") {
        val currentGeneration = generation.incrementAndGet()
        publishStatus(ConnectClientStatus.CONNECTING)
        synchronized(lock) {
            transport?.close()
            proposedDeviceToken = loadOrCreateDeviceToken(serverUrl)
            transport = transportFactory.create(
                serverUrl,
                TransportListener(currentGeneration, pairingToken)
            ).also { it.connect() }
        }
    }

    fun disconnect() {
        generation.incrementAndGet()
        synchronized(lock) {
            transport?.close()
            transport = null
            protocolVersion = 1
        }
        publishStatus(ConnectClientStatus.DISCONNECTED)
    }

    fun close() = disconnect()

    fun send(command: ConnectCommand): Boolean {
        if (status != ConnectClientStatus.APPROVED) return false
        currentTransport()?.emit(ConnectProtocol.Event.COMMAND, ConnectJsonCodec.command(command))
        return true
    }

    fun search(query: String, requestId: String): Boolean {
        if (status != ConnectClientStatus.APPROVED || query.isBlank()) return false
        currentTransport()?.emit(ConnectProtocol.Event.SEARCH, ConnectJsonCodec.search(query.trim(), requestId))
        return true
    }

    fun requestLibrary(requestId: String): Boolean {
        if (status != ConnectClientStatus.APPROVED) return false
        currentTransport()?.emit(ConnectProtocol.Event.LIBRARY, ConnectJsonCodec.library(requestId))
        return true
    }

    private fun currentTransport(): ConnectTransport? = synchronized(lock) { transport }

    private fun loadOrCreateDeviceToken(serverUrl: String): String {
        return try {
            val stored = sessionStore.load()
            val storedHost = stored.serverHost.ifEmpty { runCatching { URI(stored.serverUrl).host }.getOrNull().orEmpty() }
            val targetHost = runCatching { URI(serverUrl).host }.getOrNull().orEmpty()
            stored.deviceToken.takeIf { it.isNotEmpty() && storedHost.equals(targetHost, ignoreCase = true) }.orEmpty().ifEmpty {
                tokenGenerator.create().also(sessionStore::saveDeviceToken)
            }
        } catch (error: Throwable) {
            publishError("load session", error)
            // The live pairing can proceed, but it will require reapproval if
            // secure persistence remains unavailable after this process exits.
            tokenGenerator.create()
        }
    }

    private fun sendHello(pairingToken: String, expectedGeneration: Long) {
        val hello = ConnectJsonCodec.hello(pairingToken, proposedDeviceToken, deviceName, ConnectProtocol.PROTOCOL_VERSION)
        currentTransport()?.emit(ConnectProtocol.Event.HELLO, hello) { result ->
            if (expectedGeneration != generation.get()) return@emit
            result.onSuccess(::handleHelloReply).onFailure { publishError("pairing handshake", it) }
        }
        publishStatus(ConnectClientStatus.AWAITING_APPROVAL)
    }

    private fun handleHelloReply(reply: JSONObject) {
        try {
            val result = ConnectJsonCodec.helloResult(reply)
            protocolVersion = result.protocolVersion
            when (result.status) {
                HelloStatus.APPROVED -> {
                    publishStatus(ConnectClientStatus.APPROVED)
                    result.state?.let(::publishSnapshot)
                }
                HelloStatus.PENDING -> publishStatus(ConnectClientStatus.AWAITING_APPROVAL)
                HelloStatus.EXPIRED -> publishStatus(ConnectClientStatus.PAIRING_EXPIRED)
                HelloStatus.UNKNOWN -> publishError("pairing handshake", IllegalStateException("Unknown hello status"))
            }
        } catch (error: Throwable) {
            publishError("pairing handshake", error)
        }
    }

    private fun handleEvent(name: String, payload: Any?) {
        try {
            when (name) {
                ConnectProtocol.Event.APPROVED -> handleApproval(payload as? JSONObject ?: JSONObject())
                ConnectProtocol.Event.STATE -> {
                    val snap = ConnectJsonCodec.snapshot(payload as? JSONObject ?: JSONObject(), protocolVersion)
                    protocolVersion = snap.protocolVersion
                    publishSnapshot(snap)
                }
                ConnectProtocol.Event.SEARCH_RESULTS -> publishSearch(ConnectJsonCodec.results(payload as? JSONObject ?: JSONObject()))
                ConnectProtocol.Event.LIBRARY_RESULTS -> publishLibrary(ConnectJsonCodec.results(payload as? JSONObject ?: JSONObject()))
                ConnectProtocol.Event.REJECTED -> publishStatus(ConnectClientStatus.REJECTED)
                ConnectProtocol.Event.REVOKED -> {
                    tryStore("clear revoked credential") { sessionStore.clearDeviceToken() }
                    publishStatus(ConnectClientStatus.REVOKED)
                }
            }
        } catch (error: Throwable) {
            publishError("decode $name", error)
        }
    }

    private fun handleApproval(payload: JSONObject) {
        val approvedToken = payload.optString(ConnectProtocol.Field.DEVICE_TOKEN)
        if (approvedToken.isNotEmpty()) {
            proposedDeviceToken = approvedToken
            tryStore("save approved credential") { sessionStore.saveDeviceToken(approvedToken) }
        }
        protocolVersion = payload.optInt(ConnectProtocol.Field.PROTOCOL_VERSION, protocolVersion)
        publishStatus(ConnectClientStatus.APPROVED)
        payload.optJSONObject(ConnectProtocol.Field.STATE)?.let { ConnectJsonCodec.snapshot(it, protocolVersion) }?.let(::publishSnapshot)
    }

    private fun tryStore(operation: String, action: () -> Unit) {
        try {
            action()
        } catch (error: Throwable) {
            publishError(operation, error)
        }
    }

    private fun publishStatus(next: ConnectClientStatus) {
        status = next
        callbackExecutor.execute { listener.onStatusChanged(next) }
    }

    private fun publishSnapshot(value: ConnectSnapshot) = callbackExecutor.execute { listener.onSnapshot(value) }
    private fun publishSearch(value: ConnectResults) = callbackExecutor.execute { listener.onSearchResults(value) }
    private fun publishLibrary(value: ConnectResults) = callbackExecutor.execute { listener.onLibraryResults(value) }

    private fun publishError(operation: String, error: Throwable) {
        val detail = error.message?.takeIf(String::isNotBlank) ?: error.javaClass.simpleName
        callbackExecutor.execute {
            listener.onError(ConnectClientError(operation, "$operation failed: $detail", error))
        }
    }

    private inner class TransportListener(
        private val expectedGeneration: Long,
        private val pairingToken: String
    ) : ConnectTransport.Listener {
        private fun current(): Boolean = expectedGeneration == generation.get()

        override fun onOpened() {
            if (current()) sendHello(pairingToken, expectedGeneration)
        }

        override fun onClosed(reason: String) {
            if (current()) publishStatus(ConnectClientStatus.DISCONNECTED)
        }

        override fun onFailure(error: Throwable) {
            if (!current()) return
            publishError("Connect transport", error)
            publishStatus(ConnectClientStatus.DISCONNECTED)
        }

        override fun onEvent(name: String, payload: Any?) {
            if (current()) handleEvent(name, payload)
        }
    }
}
