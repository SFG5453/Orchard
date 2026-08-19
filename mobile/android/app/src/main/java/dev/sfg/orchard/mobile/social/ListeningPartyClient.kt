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

package dev.sfg.orchard.mobile.social

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import org.webrtc.DataChannel
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * The Kotlin counterpart of `src/app/social/listeningPartyClient.js`.
 *
 * Two transports carry a party, and which one carries what is not symmetric. Signalling and the
 * host's own playback snapshots go over the WebSocket: the host calls [broadcast] with
 * `party:state`, which the worker relays to the room as `party:update`. Only messages a *guest*
 * originates, or types other than `party:state`, take the peer-to-peer data channel. That means a
 * party keeps working when NAT traversal fails outright — the data channel is an optimisation and a
 * forward-compatibility surface, not the sync path.
 *
 * Everything that mutates [peers] or the socket runs on [scope], which is serialised to one thread.
 * WebRTC delivers its callbacks on its own signalling thread, and disposing a `PeerConnection` from
 * inside one of those callbacks deadlocks, so every callback hands off to [scope] instead of acting
 * in place.
 */
class ListeningPartyClient(
    context: Context,
    private val http: OkHttpClient,
    private val serviceUrl: String = DEFAULT_SERVICE_URL,
    private val displayName: String = "Listener",
) {
    private val appContext = context.applicationContext

    /**
     * Serialises everything that touches [peers] or [socket].
     *
     * Suspending on this dispatcher releases its thread rather than holding it, so awaiting the
     * socket handshake here still lets the socket's own callbacks run and complete that wait.
     */
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val mutableState = MutableStateFlow(PartyState())
    val state: StateFlow<PartyState> = mutableState.asStateFlow()

    private val mutableEvents = MutableSharedFlow<PartyEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<PartyEvent> = mutableEvents.asSharedFlow()

    private val baseUrl = serviceUrl.trimEnd('/')
    private var socket: WebSocket? = null
    private val peers = LinkedHashMap<String, PeerLink>()
    private var iceServers: List<PeerConnection.IceServer> = emptyList()

    /** A peer and the connection this device holds to it. */
    private class PeerLink(
        val id: String,
        var name: String = "",
        var role: PartyRole = PartyRole.GUEST,
        val connection: PeerConnection,
    ) {
        var channel: DataChannel? = null
        var open: Boolean = false
        var connectionState: String = "new"
        var hasRemoteDescription: Boolean = false

        /**
         * Candidates that arrived before the remote description did.
         *
         * `addIceCandidate` before `setRemoteDescription` is rejected, and the worker relays
         * signals in whatever order they reach it, so an early candidate has to be held rather
         * than dropped — dropping it can cost the only route that would have connected.
         */
        val pendingIce = ArrayDeque<IceCandidate>()

        fun dispose() {
            runCatching { channel?.close() }
            runCatching { channel?.dispose() }
            runCatching { connection.close() }
            runCatching { connection.dispose() }
        }
    }

    // ---------------------------------------------------------------- room lifecycle

    /** Creates a room and connects to it as host. */
    suspend fun createRoom(name: String = displayName): PartyState {
        val body = JSONObject().put("hostName", name)
        return start(post("/rooms", body))
    }

    /** Joins an existing room by its six-character code. */
    suspend fun joinRoom(code: String, name: String = displayName): PartyState {
        val cleaned = cleanRoomCode(code)
        if (cleaned.isBlank()) throw PartyException("Enter a room code.")
        val body = JSONObject().put("name", name)
        return start(post("/rooms/$cleaned/join", body))
    }

    private suspend fun start(data: JSONObject): PartyState {
        val roomJson = data.optJSONObject("room") ?: JSONObject()
        val participantJson = data.optJSONObject("participant") ?: JSONObject()
        val room = PartyRoom(
            id = roomJson.optString("id"),
            hostId = roomJson.optString("hostId"),
            createdAt = roomJson.optLong("createdAt"),
            expiresAt = roomJson.optLong("expiresAt"),
            closed = roomJson.optBoolean("closed"),
            maxParticipants = roomJson.optInt("maxParticipants"),
            participantCount = roomJson.optInt("participantCount"),
            socketUrl = roomJson.optString("socketUrl"),
            joinUrl = roomJson.optString("joinUrl"),
            shareUrl = roomJson.optString("shareUrl"),
        )
        val participant = PartyParticipant(
            id = participantJson.optString("id"),
            name = participantJson.optString("name"),
            role = PartyRole.of(participantJson.optString("role")),
            token = participantJson.optString("token"),
            joinedAt = participantJson.optLong("joinedAt"),
        )
        connect(room, participant)
        return mutableState.value
    }

    private suspend fun connect(room: PartyRoom, participant: PartyParticipant) = withContext(dispatcher) {
        // Torn down inline rather than through `disconnect()`, whose teardown is asynchronous and
        // would otherwise land after the state below and reset the party it was meant to precede.
        teardown()
        mutableState.value = PartyState(
            status = PartyStatus.CONNECTING,
            room = room,
            participant = participant,
        )

        val url = socketUrl(room, participant)
        val opened = CompletableDeferred<Result<Unit>>()
        val request = Request.Builder().url(url).build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (socket !== webSocket) {
                    opened.complete(Result.failure(PartyException("Listening party connection was cancelled.")))
                    return
                }
                patch { it.copy(status = PartyStatus.CONNECTED) }
                opened.complete(Result.success(Unit))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (socket !== webSocket) return
                scope.launch { handleSocketMessage(text) }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.w(TAG, "Listening party socket failed", t)
                opened.complete(Result.failure(PartyException("Listening party connection failed.")))
                if (socket !== webSocket) return
                socket = null
                scope.launch {
                    emit(PartyEvent.Failed("Listening party connection failed."))
                    disconnectPeers(offline = true)
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                opened.complete(
                    Result.failure(PartyException("Listening party connection closed before it was ready.")),
                )
                if (socket !== webSocket) return
                socket = null
                scope.launch { disconnectPeers(offline = true) }
            }
        }

        val webSocket = http.newWebSocket(request, listener)
        socket = webSocket

        // A null outcome is the timeout firing; a failed one is the socket reporting why. The two
        // have to stay distinguishable, which is why the handshake resolves a Result rather than a
        // nullable error that success and timeout would both spell as null.
        val outcome = withTimeoutOrNull(CONNECTION_TIMEOUT_MS) { opened.await() }
        val message = when {
            outcome == null -> "Listening party connection timed out."
            outcome.isFailure -> outcome.exceptionOrNull()?.message ?: "Listening party connection failed."
            else -> null
        }
        if (message != null) {
            if (socket === webSocket) {
                socket = null
                webSocket.close(1000, "client_disconnect")
            }
            patch { it.copy(status = PartyStatus.IDLE, error = message) }
            emit(PartyEvent.Failed(message))
            throw PartyException(message)
        }
    }

    private fun socketUrl(room: PartyRoom, participant: PartyParticipant): String {
        val base = room.socketUrl.ifBlank {
            "${baseUrl.replaceFirst("http", "ws")}/rooms/${room.id}/socket"
        }
        val separator = if (base.contains('?')) "&" else "?"
        return base +
            separator +
            "participantId=" + participant.id +
            "&token=" + participant.token
    }

    /** Tears the party down locally. Does not close the room; see [closeRoom]. */
    fun disconnect() {
        scope.launch {
            teardown()
            mutableState.value = PartyState()
        }
    }

    /** Releases the client for good. The instance cannot be reused afterwards. */
    fun release() {
        scope.launch { teardown() }.invokeOnCompletion { scope.cancel() }
    }

    /** Closes the socket and every peer. Callers must already be on [dispatcher]. */
    private fun teardown() {
        socket?.close(1000, "client_disconnect")
        socket = null
        peers.values.forEach(PeerLink::dispose)
        peers.clear()
    }

    private fun disconnectPeers(offline: Boolean) {
        peers.values.forEach(PeerLink::dispose)
        peers.clear()
        patch { it.copy(peers = emptyList(), status = if (offline) PartyStatus.OFFLINE else it.status) }
    }

    // ---------------------------------------------------------------- outbound

    /**
     * Sends a message to the party.
     *
     * A host's `party:state` goes over the socket as `party:update` so the worker can also store it
     * as the room's `lastState` and replay it to whoever joins next — a data channel reaches only
     * the peers already connected, and would leave a late joiner with a blank player.
     */
    fun broadcast(type: String, payload: JSONObject) {
        scope.launch {
            if (mutableState.value.isHost && type == PARTY_STATE) {
                sendSocket(JSONObject().put("type", "party:update").put("payload", payload))
                return@launch
            }
            val message = JSONObject()
                .put("type", type)
                .put("payload", payload)
                .put("sentAt", System.currentTimeMillis())
                .toString()
            peers.values.forEach { peer ->
                if (peer.open) {
                    runCatching { peer.channel?.send(message.toDataBuffer()) }
                        .onFailure { Log.w(TAG, "Failed to send to peer ${peer.id}", it) }
                }
            }
        }
    }

    /** Asks the host to change playback. A host applies its own request directly instead. */
    fun requestHost(payload: JSONObject) {
        scope.launch { sendSocket(JSONObject().put("type", "party:request").put("payload", payload)) }
    }

    /** Hands the host seat to another participant. Host only; the worker rejects it otherwise. */
    fun transferHost(participantId: String) {
        scope.launch {
            sendSocket(JSONObject().put("type", "party:host-transfer").put("participantId", participantId))
        }
    }

    /** Ends the room for everyone. Host only. */
    fun closeRoom() {
        scope.launch { sendSocket(JSONObject().put("type", "party:close")) }
    }

    private fun sendSocket(payload: JSONObject) {
        socket?.let { runCatching { it.send(payload.toString()) } }
    }

    private fun sendSignal(to: String, kind: String, data: Any) {
        sendSocket(
            JSONObject()
                .put("type", "signal")
                .put("to", to)
                .put("kind", kind)
                .put("data", data),
        )
    }

    // ---------------------------------------------------------------- inbound

    private suspend fun handleSocketMessage(raw: String) {
        val message = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (message.optString("type")) {
            "party:welcome" -> handleWelcome(message)

            "peer:joined" -> {
                val peer = message.optJSONObject("peer") ?: return
                val id = peer.optString("id")
                ensurePeer(id, shouldInitiate(id), peer)
                publishPeers()
            }

            "peer:left" -> {
                closePeer(message.optString("participantId"))
                publishPeers()
            }

            "signal" -> handleSignal(message)

            "party:update" -> emitState(message.optJSONObject("payload"))

            "party:request" -> {
                val payload = message.optJSONObject("payload") ?: JSONObject()
                emit(
                    PartyEvent.Request(
                        from = message.optString("from"),
                        action = payload.optString("action"),
                        payload = payload,
                    ),
                )
            }

            "party:host-changed" -> handleHostChanged(message.optString("hostId"))

            "party:closed" -> {
                emit(PartyEvent.Closed(message.optString("reason")))
                disconnectPeers(offline = false)
                mutableState.value = PartyState()
            }

            "error" -> {
                val error = message.optString("error", "Listening party failed.")
                patch { it.copy(error = error) }
                emit(PartyEvent.Failed(error))
            }
        }
    }

    private suspend fun handleWelcome(message: JSONObject) {
        iceServers = parseIceServers(message.optJSONArray("iceServers"))

        val roomJson = message.optJSONObject("room")
        patch { current ->
            val room = current.room?.let { existing ->
                if (roomJson == null) existing else existing.copy(
                    hostId = roomJson.optString("hostId", existing.hostId),
                    closed = roomJson.optBoolean("closed"),
                    participantCount = roomJson.optInt("participantCount", existing.participantCount),
                    maxParticipants = roomJson.optInt("maxParticipants", existing.maxParticipants),
                )
            }
            // The socket's view of our role is fresher than the join response's, and the two only
            // disagree when the host seat moved in between. Trusting the older value there would
            // leave this device convinced it is a guest while the room waits on it to broadcast.
            val role = message.optString("role").takeIf { it.isNotBlank() }
            current.copy(
                room = room,
                participant = role?.let { current.participant?.copy(role = PartyRole.of(it)) }
                    ?: current.participant,
            )
        }

        val existing = message.optJSONArray("peers")
        for (index in 0 until (existing?.length() ?: 0)) {
            val peer = existing?.optJSONObject(index) ?: continue
            val id = peer.optString("id")
            ensurePeer(id, shouldInitiate(id), peer)
        }
        publishPeers()

        // The worker replays the host's last snapshot to a late joiner. Without this a guest sits
        // silent until the host's next state change, which for a paused room never comes.
        message.optJSONObject("lastState")?.optJSONObject("payload")?.let { emitState(it) }
    }

    private suspend fun handleHostChanged(hostId: String) {
        if (hostId.isBlank()) return
        patch { current ->
            val participant = current.participant?.let {
                when {
                    it.id == hostId -> it.copy(role = PartyRole.HOST)
                    it.isHost -> it.copy(role = PartyRole.GUEST)
                    else -> it
                }
            }
            current.copy(participant = participant, room = current.room?.copy(hostId = hostId))
        }
        peers.values.forEach { peer ->
            peer.role = when {
                peer.id == hostId -> PartyRole.HOST
                peer.role == PartyRole.HOST -> PartyRole.GUEST
                else -> peer.role
            }
        }
        publishPeers()
        emit(PartyEvent.HostChanged(hostId))
    }

    private suspend fun emitState(payload: JSONObject?) {
        if (payload == null) return
        emit(PartyEvent.State(PartyStateJson.decode(payload)))
    }

    // ---------------------------------------------------------------- webrtc

    /**
     * The peer with the lexicographically smaller id makes the offer.
     *
     * Both sides run this against the same pair of ids and reach opposite answers, which is what
     * stops the two of them from offering simultaneously and colliding. Ids are lowercase hex, so
     * Kotlin's ordering and JavaScript's agree.
     */
    private fun shouldInitiate(peerId: String): Boolean =
        (mutableState.value.participant?.id.orEmpty()) < peerId

    private fun ensurePeer(peerId: String, initiate: Boolean, info: JSONObject?): PeerLink? {
        if (peerId.isBlank() || peerId == mutableState.value.participant?.id) return null
        peers[peerId]?.let { existing ->
            info?.optString("name")?.takeIf(String::isNotBlank)?.let { existing.name = it }
            info?.optString("role")?.takeIf(String::isNotBlank)?.let { existing.role = PartyRole.of(it) }
            return existing
        }

        val configuration = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
        }

        lateinit var link: PeerLink
        val observer = object : PeerConnection.Observer {
            override fun onIceCandidate(candidate: IceCandidate) {
                scope.launch { sendSignal(peerId, "ice", candidate.toJson()) }
            }

            override fun onDataChannel(channel: DataChannel) {
                scope.launch {
                    attachChannel(link, channel)
                    publishPeers()
                }
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                scope.launch {
                    link.connectionState = newState.name.lowercase()
                    if (newState == PeerConnection.PeerConnectionState.CLOSED ||
                        newState == PeerConnection.PeerConnectionState.FAILED ||
                        newState == PeerConnection.PeerConnectionState.DISCONNECTED
                    ) {
                        closePeer(peerId)
                    }
                    publishPeers()
                }
            }

            override fun onSignalingChange(state: PeerConnection.SignalingState) = Unit
            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) = Unit
            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) = Unit
            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onAddStream(stream: MediaStream?) = Unit
            override fun onRemoveStream(stream: MediaStream?) = Unit
            override fun onRenegotiationNeeded() = Unit
            override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) = Unit
        }

        val connection = WebRtcRuntime.factory(appContext).createPeerConnection(configuration, observer)
            ?: return null

        link = PeerLink(
            id = peerId,
            name = info?.optString("name").orEmpty(),
            role = PartyRole.of(info?.optString("role")),
            connection = connection,
        )
        peers[peerId] = link

        if (initiate) {
            val init = DataChannel.Init().apply { ordered = true }
            connection.createDataChannel(CHANNEL_LABEL, init)?.let { attachChannel(link, it) }
            createOffer(link)
        }
        return link
    }

    private fun createOffer(peer: PeerLink) {
        peer.connection.createOffer(
            sdpObserver(
                onCreate = { description ->
                    peer.connection.setLocalDescription(
                        sdpObserver(onSet = { scope.launch { sendSignal(peer.id, "offer", description.toJson()) } }),
                        description,
                    )
                },
            ),
            MediaConstraints(),
        )
    }

    private suspend fun handleSignal(message: JSONObject) {
        val from = message.optString("from")
        val peer = ensurePeer(from, initiate = false, info = null) ?: return
        val data = message.optJSONObject("data")

        when (message.optString("kind")) {
            "offer" -> {
                val description = data?.toSessionDescription() ?: return
                peer.connection.setRemoteDescription(
                    sdpObserver(
                        onSet = {
                            scope.launch {
                                peer.hasRemoteDescription = true
                                flushPendingIce(peer)
                                answer(peer)
                            }
                        },
                    ),
                    description,
                )
            }

            "answer" -> {
                val description = data?.toSessionDescription() ?: return
                peer.connection.setRemoteDescription(
                    sdpObserver(
                        onSet = {
                            scope.launch {
                                peer.hasRemoteDescription = true
                                flushPendingIce(peer)
                            }
                        },
                    ),
                    description,
                )
            }

            "ice" -> {
                val candidate = data?.toIceCandidate() ?: return
                if (peer.hasRemoteDescription) peer.connection.addIceCandidate(candidate)
                else peer.pendingIce.addLast(candidate)
            }
        }
    }

    private fun answer(peer: PeerLink) {
        peer.connection.createAnswer(
            sdpObserver(
                onCreate = { description ->
                    peer.connection.setLocalDescription(
                        sdpObserver(onSet = { scope.launch { sendSignal(peer.id, "answer", description.toJson()) } }),
                        description,
                    )
                },
            ),
            MediaConstraints(),
        )
    }

    private fun flushPendingIce(peer: PeerLink) {
        while (peer.pendingIce.isNotEmpty()) {
            peer.connection.addIceCandidate(peer.pendingIce.removeFirst())
        }
    }

    private fun attachChannel(peer: PeerLink, channel: DataChannel) {
        peer.channel = channel
        // An inbound channel can already be open by the time it is handed over, and `onStateChange`
        // only reports transitions from here on, so the current state has to be read once directly.
        peer.open = runCatching { channel.state() == DataChannel.State.OPEN }.getOrDefault(false)
        channel.registerObserver(object : DataChannel.Observer {
            override fun onStateChange() {
                scope.launch {
                    peer.open = runCatching { channel.state() == DataChannel.State.OPEN }.getOrDefault(false)
                    publishPeers()
                }
            }

            override fun onMessage(buffer: DataChannel.Buffer) {
                if (buffer.binary) return
                // The buffer is reused once this returns, so it must be copied before the hop.
                val text = StandardCharsets.UTF_8.decode(buffer.data).toString()
                scope.launch { handleChannelMessage(peer, text) }
            }

            override fun onBufferedAmountChange(previousAmount: Long) = Unit
        })
    }

    private suspend fun handleChannelMessage(peer: PeerLink, raw: String) {
        val message = runCatching { JSONObject(raw) }.getOrNull() ?: return
        when (message.optString("type")) {
            PARTY_STATE -> emitState(message.optJSONObject("payload"))
            "party:request" -> {
                val payload = message.optJSONObject("payload") ?: JSONObject()
                emit(PartyEvent.Request(peer.id, payload.optString("action"), payload))
            }
        }
    }

    private fun closePeer(peerId: String) {
        peers.remove(peerId)?.dispose()
    }

    private fun publishPeers() {
        val snapshot = peers.values.map { peer ->
            PartyPeer(
                id = peer.id,
                name = peer.name,
                role = peer.role,
                open = peer.open,
                state = peer.connectionState,
            )
        }
        patch { it.copy(peers = snapshot) }
    }

    // ---------------------------------------------------------------- plumbing

    /**
     * Atomic read-modify-write on the state.
     *
     * `update` rather than assignment because the socket's callbacks patch from OkHttp's thread
     * while the confined work patches from [dispatcher]; a plain `value = transform(value)` would
     * lose whichever of the two read first.
     */
    private fun patch(transform: (PartyState) -> PartyState) {
        mutableState.update(transform)
    }

    private suspend fun emit(event: PartyEvent) {
        mutableEvents.emit(event)
    }

    private suspend fun post(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("$baseUrl$path")
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            val payload = runCatching { JSONObject(text) }.getOrNull() ?: JSONObject()
            if (!response.isSuccessful || !payload.optBoolean("ok")) {
                val error = payload.optString("error").ifBlank { "Party request failed (${response.code})." }
                throw PartyException(error)
            }
            payload.optJSONObject("data") ?: JSONObject()
        }
    }

    private fun parseIceServers(value: JSONArray?): List<PeerConnection.IceServer> = buildList {
        for (index in 0 until (value?.length() ?: 0)) {
            val entry = value?.optJSONObject(index) ?: continue
            // `urls` is a string or an array of them in the WebRTC configuration dictionary, and
            // the worker currently sends the string form.
            val raw: Any? = entry.opt("urls")
            val urls = when {
                raw is JSONArray -> (0 until raw.length()).mapNotNull { raw.optString(it).takeIf(String::isNotBlank) }
                raw is String -> listOf(raw)
                else -> emptyList()
            }
            if (urls.isEmpty()) continue
            add(
                PeerConnection.IceServer.builder(urls)
                    .setUsername(entry.optString("username"))
                    .setPassword(entry.optString("credential"))
                    .createIceServer(),
            )
        }
    }

    private companion object {
        const val TAG = "ListeningParty"
        const val DEFAULT_SERVICE_URL = "https://party.sfg545.dev"
        const val CHANNEL_LABEL = "orchard-party"
        const val PARTY_STATE = "party:state"
        const val CONNECTION_TIMEOUT_MS = 10_000L
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

/** The party's default service, exposed so callers can override it without repeating the literal. */
const val DEFAULT_PARTY_SERVICE_URL = "https://party.sfg545.dev"

/**
 * A party failure with a message already fit to show a listener.
 *
 * Distinct from the generic exceptions the socket and HTTP layers throw so the UI can print
 * `message` directly instead of guessing whether it holds a stack-trace fragment.
 */
class PartyException(message: String) : Exception(message)

/**
 * Process-wide WebRTC initialisation.
 *
 * `PeerConnectionFactory.initialize` loads the native library and may only run once per process,
 * so it lives here rather than on the client, which is created and thrown away with each party.
 */
private object WebRtcRuntime {
    @Volatile
    private var factory: PeerConnectionFactory? = null

    fun factory(context: Context): PeerConnectionFactory = factory ?: synchronized(this) {
        factory ?: build(context).also { factory = it }
    }

    private fun build(context: Context): PeerConnectionFactory {
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .createInitializationOptions(),
        )
        // No audio or video module is configured: a listening party synchronises playback state
        // over a data channel and never opens a media track, so the factory has no codecs to set
        // up and the app needs no microphone permission.
        return PeerConnectionFactory.builder().createPeerConnectionFactory()
    }
}

private fun String.toDataBuffer(): DataChannel.Buffer =
    DataChannel.Buffer(ByteBuffer.wrap(toByteArray(StandardCharsets.UTF_8)), false)

private fun IceCandidate.toJson(): JSONObject = JSONObject()
    .put("candidate", sdp)
    .put("sdpMid", sdpMid)
    .put("sdpMLineIndex", sdpMLineIndex)

private fun JSONObject.toIceCandidate(): IceCandidate? {
    val candidate = optString("candidate")
    if (candidate.isBlank()) return null
    return IceCandidate(optString("sdpMid"), optInt("sdpMLineIndex"), candidate)
}

private fun SessionDescription.toJson(): JSONObject = JSONObject()
    .put("type", type.canonicalForm())
    .put("sdp", description)

private fun JSONObject.toSessionDescription(): SessionDescription? {
    val sdp = optString("sdp")
    val type = optString("type")
    if (sdp.isBlank() || type.isBlank()) return null
    return SessionDescription(SessionDescription.Type.fromCanonicalForm(type), sdp)
}

/** WebRTC's observer wants four callbacks where a caller only ever needs one or two. */
private fun sdpObserver(
    onCreate: (SessionDescription) -> Unit = {},
    onSet: () -> Unit = {},
): SdpObserver = object : SdpObserver {
    override fun onCreateSuccess(description: SessionDescription) = onCreate(description)
    override fun onSetSuccess() = onSet()
    override fun onCreateFailure(error: String?) {
        Log.w("ListeningParty", "Failed to create session description: $error")
    }

    override fun onSetFailure(error: String?) {
        Log.w("ListeningParty", "Failed to set session description: $error")
    }
}
