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

import android.content.Context
import android.util.Log
import dev.sfg.orchard.mobile.OrchardGraph
import dev.sfg.orchard.mobile.auth.OrchardAccountService
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.playback.LocalPlaybackController
import dev.sfg.orchard.mobile.playback.RemoteMix
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Orchard Connect on the phone. The protocol, roles and transports are the shared core; this
 * feeds it the account hub, the phone's player and provider work, and mirrors a remote target.
 *
 * Application-scoped: it keeps its own Media3 controller, so a desktop can drive this phone
 * whenever the process is alive, not only while the UI is.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class ConnectRepository(
    private val context: Context,
    private val scope: CoroutineScope,
    private val graph: OrchardGraph,
) : ConnectNative.Listener {
    private val account = OrchardAccountService.get(context)
    // Node calls run here, one at a time, so a handle is never used while being destroyed.
    private val nodeDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val events = Channel<JSONObject>(Channel.UNLIMITED)
    private val hub = ConnectHubSocket(
        graph.http, account, scope,
        onOpen = { node { ConnectNative.hubOpened(it) } },
        onMessage = { text -> node { ConnectNative.hubMessage(it, text) } },
        onClosed = { node { ConnectNative.hubClosed(it) } },
    )
    @Volatile private var handle = 0L
    private var deviceId = ""
    private var player: LocalConnectPlayer? = null
    private val requests = ConcurrentHashMap<String, CompletableDeferred<JSONObject>>()
    internal val providers = ConnectProviders(this, graph)
    private val mix = ConnectMix(this)

    private val mutableState = MutableStateFlow(ConnectState())
    val state: StateFlow<ConnectState> = mutableState.asStateFlow()
    private val mutableRemote = MutableStateFlow(PlaybackSnapshot())
    /** The target's state while this phone controls another device. */
    val remote: StateFlow<PlaybackSnapshot> = mutableRemote.asStateFlow()
    private val mutableRoles = MutableStateFlow(ConnectRoles())
    val roles: StateFlow<ConnectRoles> = mutableRoles.asStateFlow()

    init {
        scope.launch(Dispatchers.Main) { for (event in events) handleEvent(event) }
        scope.launch(Dispatchers.Main) {
            account.state.map { it.deviceId.takeIf { _ -> it.signedIn }.orEmpty() }.distinctUntilChanged()
                .collect(::accountChanged)
        }
    }

    // ── Controller API ────────────────────────────────────────────────────────────────────────

    fun connectTo(deviceId: String) = node { ConnectNative.connectTo(it, deviceId) }

    /** Stops controlling another device; music there keeps playing. */
    fun stopControlling() {
        val sid = state.value.controllerSession?.id ?: return
        node { ConnectNative.disconnect(it, sid) }
    }

    fun command(action: String, args: JSONObject = JSONObject()) =
        node { ConnectNative.command(it, JSONObject().put("action", action).put("args", args).toString()) }

    /** Absolute index into [remote]'s queue; history and the current track are not addressable. */
    fun queueCommand(action: String, vararg indices: Pair<String, Int>) {
        val args = JSONObject()
        for ((key, index) in indices) {
            val upcoming = ConnectWire.upcomingIndex(remote.value, index)
            if (upcoming < 0) return
            args.put(key, upcoming)
        }
        command(action, args)
    }

    fun clearMessage() = mutableState.update { it.copy(message = "") }

    /** RPC to whoever holds `host` ("artwork", "mix", "provider:qobuz"). Null on any failure. */
    internal suspend fun request(host: String, method: String, params: JSONObject, timeoutMs: Long = 30_000): JSONObject? {
        val id = withContext(nodeDispatcher) {
            val current = handle
            if (current == 0L) null
            else ConnectNative.request(current, JSONObject().put("host", host).put("method", method).put("params", params)
                .put("timeout_ms", timeoutMs).toString())
        } ?: return null
        val reply = CompletableDeferred<JSONObject>()
        requests[id] = reply
        val result = withTimeoutOrNull(timeoutMs) { reply.await() }
        requests.remove(id)
        return result?.takeIf { it.optBoolean("ok") }
    }

    internal fun respond(id: String, ok: Boolean, result: Any? = null, error: String = "") {
        val response = JSONObject().put("id", id).put("ok", ok).put("result", result ?: JSONObject.NULL)
        if (error.isNotBlank()) response.put("error", error)
        node { ConnectNative.respond(it, response.toString()) }
    }

    internal fun sendData(sessionId: String, header: JSONObject, payload: ByteArray) =
        node { ConnectNative.sendData(it, sessionId, header.toString(), payload) }

    /** Queues an AudioChunk stream; the stream id, or null when the core refused it. */
    internal suspend fun sendStream(sessionId: String, meta: JSONObject, payload: ByteArray): String? =
        withContext(nodeDispatcher) {
            handle.takeIf { it != 0L }?.let { ConnectNative.sendStream(it, sessionId, meta.toString(), payload) }
        }?.takeIf { it.isNotEmpty() }

    /** The desktop mixing for this phone, while this phone is a target and one is connected. */
    internal fun mixHost(): RemoteMix? = mixSession()?.let { mix }

    internal fun mixSession(): String? {
        val host = roleHost("mix") ?: return null
        return state.value.sessions.firstOrNull { it.peer.id == host && !it.isController && it.state == "CONNECTED" }?.id
    }

    /** The device holding a role, or null when it is this phone or nobody. */
    fun roleHost(role: String): String? {
        val roles = roles.value
        val host = when {
            role.startsWith("provider:") -> roles.providerHosts[role.removePrefix("provider:")]
            role == "artwork" -> roles.artworkHost
            role == "mix" -> roles.mixHost
            else -> null
        }
        return host?.takeIf { it.isNotBlank() && it != deviceId }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────────────────────

    private fun accountChanged(newDeviceId: String) {
        if (newDeviceId == deviceId) return
        deviceId = newDeviceId
        hub.stop()
        // One node per account session: no listener, keys or peers carry over.
        onNodeThread {
            if (handle != 0L) ConnectNative.destroy(handle)
            handle = if (newDeviceId.isBlank()) 0L else ConnectNative.create(this)
        }
        mutableState.value = ConnectState(available = newDeviceId.isNotBlank())
        mutableRemote.value = PlaybackSnapshot()
        mutableRoles.value = ConnectRoles()
        if (newDeviceId.isBlank()) return
        val local = player ?: LocalConnectPlayer(LocalPlaybackController(context, scope)).also { player = it }
        refreshDevice()
        refreshInterfaces()
        publishLocal(local)
        hub.start()
    }

    private fun publishLocal(local: LocalConnectPlayer) {
        if (publishing) return
        publishing = true
        scope.launch {
            combine(local.snapshots, graph.settings.settings.map { it.autoplayEnabled }) { snapshot, autoplay ->
                ConnectWire.snapshot(snapshot, autoplay).toString()
            }.distinctUntilChanged().debounce(150).collect { json ->
                node { ConnectNative.publishPlayback(it, json) }
            }
        }
        scope.launch {
            combine(graph.auth.state, graph.qobuz.status, graph.settings.settings) { _, _, _ -> Unit }
                .collect { refreshDevice() }
        }
        scope.launch {
            graph.networkMonitor.isOnline.collect { refreshInterfaces() }
        }
    }
    private var publishing = false

    private fun refreshDevice() {
        if (deviceId.isBlank()) return
        val json = ConnectDeviceInfo.build(context, graph, deviceId).toString()
        node { ConnectNative.setDevice(it, json) }
    }

    private fun refreshInterfaces() {
        scope.launch(Dispatchers.IO) {
            val json = ConnectDeviceInfo.interfaces().toString()
            node { ConnectNative.setInterfaces(it, json) }
        }
    }

    private fun node(block: (Long) -> Unit) {
        scope.launch(nodeDispatcher) { handle.takeIf { it != 0L }?.let(block) }
    }

    private fun onNodeThread(block: () -> Unit) {
        scope.launch(nodeDispatcher) { block() }
    }

    // ── Events (Connect thread -> main) ───────────────────────────────────────────────────────

    override fun onHubSend(text: String) = hub.send(text)

    override fun onEvent(json: String) {
        runCatching { JSONObject(json) }.onSuccess { events.trySend(it) }
    }

    override fun onData(sessionId: String, headerJson: String, payload: ByteArray) {
        val header = runCatching { JSONObject(headerJson) }.getOrNull() ?: return
        if (header.optString("kind") == "audio") mix.onAudio(header, payload) else providers.onData(header, payload)
    }

    private fun handleEvent(event: JSONObject) {
        when (event.optString("event")) {
            "hub" -> mutableState.update { it.copy(online = event.optBoolean("connected")) }
            "devices" -> {
                val list = event.optJSONArray("devices")
                val devices = (0 until (list?.length() ?: 0)).mapNotNull { list!!.optJSONObject(it) }
                    .map(ConnectDevice::from)
                mutableState.update { it.copy(devices = devices) }
            }
            "session" -> onSession(event)
            "session_ended" -> onSessionEnded(event)
            "remote_state" -> if (event.optString("session_id") == state.value.controllerSession?.id) {
                mutableRemote.value = ConnectWire.snapshot(event.optJSONObject("snapshot") ?: JSONObject())
            }
            "initial_playback" -> onInitialPlayback(event)
            "roles" -> mutableRoles.value = ConnectRoles.from(event.optJSONObject("roles") ?: JSONObject())
            "command" -> player?.let {
                ConnectTarget.apply(it, event.optString("action"), event.optJSONObject("args") ?: JSONObject(),
                    contextTitle = peerName(event.optString("session_id")))
            }
            "command_result" -> if (!event.optBoolean("ok")) message(event.optString("error"), currentPeerName())
            "rpc" -> providers.serve(event)
            "rpc_result" -> requests.remove(event.optString("id"))?.complete(event)
            "stream_sent" -> mix.onStreamSent(event.optString("stream"))
            "stream_failed" -> mix.onStreamFailed(event.optString("stream"))
            "error" -> {
                val device = state.value.devices.firstOrNull { it.id == event.optString("device_id") }
                if (device != null && state.value.pending?.id == device.id) mutableState.update { it.copy(pending = null) }
                message(event.optString("code"), device?.name.orEmpty())
            }
        }
    }

    private fun onSession(event: JSONObject) {
        val sid = event.optString("session_id")
        val peer = ConnectDevice.from(event.optJSONObject("peer") ?: JSONObject())
        if (sid.isBlank()) {
            mutableState.update { it.copy(pending = peer, message = "") }
            return
        }
        val session = ConnectSession(sid, event.optString("role"), event.optString("state"), peer, event.optString("transport"))
        mutableState.update { current ->
            current.copy(
                sessions = current.sessions.filterNot { it.id == sid } + session,
                pending = current.pending?.takeIf { it.id != peer.id },
                message = "",
            )
        }
    }

    private fun onSessionEnded(event: JSONObject) {
        val sid = event.optString("session_id")
        val ended = state.value.sessions.firstOrNull { it.id == sid }
        mutableState.update { it.copy(sessions = it.sessions.filterNot { session -> session.id == sid }) }
        if (ended?.isController == true) mutableRemote.value = PlaybackSnapshot()
        val reason = event.optString("reason")
        if (reason !in setOf("user_disconnected", "switched_target", "role_change")) message(reason, ended?.peer?.name.orEmpty())
    }

    private fun onInitialPlayback(event: JSONObject) {
        val outcome = event.optString("outcome")
        val local = player ?: return
        if (event.optString("role") == "target") {
            if (outcome == "transferred") {
                ConnectTarget.transfer(local, event.optJSONObject("snapshot") ?: return,
                    contextTitle = peerName(event.optString("session_id")))
            }
        } else if (outcome != "none") {
            // This phone becomes a remote control; its own speaker goes quiet.
            local.pause()
        }
    }

    private fun peerName(sessionId: String) =
        state.value.sessions.firstOrNull { it.id == sessionId }?.peer?.name.orEmpty().ifBlank { "Orchard Connect" }

    private fun currentPeerName() = state.value.controllerSession?.peer?.name.orEmpty()

    private fun message(code: String, deviceName: String) {
        val text = ConnectMessages.describe(code, deviceName)
        Log.i(TAG, "Connect: $code ($deviceName)")
        if (text.isNotBlank()) mutableState.update { it.copy(message = text) }
    }

    private companion object {
        const val TAG = "OrchardConnect"
    }
}
