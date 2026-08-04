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
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.sfg.orchard.connect.client.OrchardConnectClient
import dev.sfg.orchard.connect.discovery.ConnectDiscovery
import dev.sfg.orchard.connect.discovery.LanEndpointDiscovery
import dev.sfg.orchard.connect.protocol.ConnectClientError
import dev.sfg.orchard.connect.protocol.ConnectClientStatus
import dev.sfg.orchard.connect.protocol.ConnectCommand
import dev.sfg.orchard.connect.protocol.ConnectResults
import dev.sfg.orchard.connect.protocol.ConnectSnapshot
import dev.sfg.orchard.connect.protocol.ConnectTrack
import dev.sfg.orchard.connect.session.AndroidKeystoreConnectSessionStore
import dev.sfg.orchard.connect.session.SecureDeviceTokenGenerator
import dev.sfg.orchard.connect.transport.OkHttpSocketIoTransportFactory
import dev.sfg.orchard.mobile.model.DeviceAvailability
import dev.sfg.orchard.mobile.model.DeviceType
import dev.sfg.orchard.mobile.model.PlaybackDevice
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.PlaybackStatus
import dev.sfg.orchard.mobile.model.RepeatMode
import dev.sfg.orchard.mobile.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URI
import java.util.concurrent.Executor

/**
 * Application-lifetime adapter from the desktop Connect protocol to observable
 * mobile device/playback state. Transport details never reach UI components.
 */
class ConnectDeviceRepository(
    context: Context,
    private val scope: CoroutineScope,
) : OrchardConnectClient.Listener {
    private val sessionStore = AndroidKeystoreConnectSessionStore(context)
    private val transportFactory = OkHttpSocketIoTransportFactory()
    private val discovery = LanEndpointDiscovery()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OrchardConnectClient(
        transportFactory = transportFactory,
        sessionStore = sessionStore,
        tokenGenerator = SecureDeviceTokenGenerator(),
        deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
        callbackExecutor = Executor(mainHandler::post),
        listener = this,
    )
    private val mutableStatus = MutableStateFlow(ConnectClientStatus.DISCONNECTED)
    val status: StateFlow<ConnectClientStatus> = mutableStatus.asStateFlow()
    private val mutableProtocolVersion = MutableStateFlow(1)
    val protocolVersion: StateFlow<Int> = mutableProtocolVersion.asStateFlow()
    private val mutableAudioEngine = MutableStateFlow(dev.sfg.orchard.connect.protocol.ConnectAudioEngine())
    val audioEngine: StateFlow<dev.sfg.orchard.connect.protocol.ConnectAudioEngine> = mutableAudioEngine.asStateFlow()
    private val mutableRemoteVolume = MutableStateFlow(1.0f)
    val remoteVolume: StateFlow<Float> = mutableRemoteVolume.asStateFlow()
    private val mutableSnapshot = MutableStateFlow(PlaybackSnapshot())
    val snapshot: StateFlow<PlaybackSnapshot> = mutableSnapshot.asStateFlow()
    private val mutableDevice = MutableStateFlow<PlaybackDevice?>(null)
    val device: StateFlow<PlaybackDevice?> = mutableDevice.asStateFlow()
    private val mutableMessage = MutableStateFlow("")
    val message: StateFlow<String> = mutableMessage.asStateFlow()
    @Volatile private var serverUrl = ""
    @Volatile private var manualDisconnect = false
    private var reconnectJob: Job? = null
    private var reconnectAttempt = 0
    private data class PendingTransfer(val track: Track, val positionMs: Long)
    private var pendingTransfer: PendingTransfer? = null

    init {
        restoreKnownDevice()
    }

    fun pair(input: String) {
        val parsed = ConnectDiscovery.parsePairingInput(input)
        val rawServer = parsed.serverUrl.ifEmpty { ConnectDiscovery.cleanServerUrl(input) }
        if (rawServer.isBlank()) {
            mutableMessage.value = "Enter or scan an Orchard pairing link."
            return
        }
        scope.launch(Dispatchers.IO) {
            runCatching {
                val target = discovery.discover(rawServer, ConnectDiscovery.serverHost(rawServer)).ifBlank { rawServer }
                sessionStore.saveLocation(target, ConnectDiscovery.serverHost(target))
                target
            }.onSuccess { target ->
                serverUrl = target
                manualDisconnect = false
                reconnectAttempt = 0
                reconnectJob?.cancel()
                client.connect(target, parsed.token)
                publishDevice()
            }.onFailure { mutableMessage.value = it.message ?: "The device could not be reached." }
        }
    }

    fun disconnect() {
        manualDisconnect = true
        reconnectJob?.cancel()
        client.disconnect()
        scope.launch(Dispatchers.IO) {
            runCatching(sessionStore::clear)
                .onFailure { Log.w(TAG, "Remembered Connect device could not be cleared", it) }
            serverUrl = ""
            mutableProtocolVersion.value = 1
            mutableAudioEngine.value = dev.sfg.orchard.connect.protocol.ConnectAudioEngine()
            mutableRemoteVolume.value = 1.0f
            mutableSnapshot.value = PlaybackSnapshot()
            publishDevice()
        }
    }

    fun send(command: ConnectCommand): Boolean {
        val accepted = client.send(command)
        if (!accepted) mutableMessage.value = "The selected Orchard device is offline."
        return accepted
    }

    fun setVolume(volume: Float): Boolean = send(ConnectCommand.Volume(volume.toDouble().coerceIn(0.0, 1.0)))

    fun toggleShuffle(): Boolean = send(ConnectCommand.ToggleShuffle)

    fun cycleRepeat(): Boolean = send(ConnectCommand.CycleRepeat)

    fun moveQueueIndex(from: Int, to: Int): Boolean = send(ConnectCommand.MoveQueueIndex(from, to))

    fun removeQueueIndex(index: Int): Boolean = send(ConnectCommand.RemoveQueueIndex(index))

    fun playQueueIndex(index: Int): Boolean = send(ConnectCommand.PlayQueueIndex(index))

    fun clearUpcoming(): Boolean = send(ConnectCommand.ClearUpcoming)

    fun playNext(track: Track): Boolean = send(ConnectCommand.PlayNext(track.toPayload()))

    fun addToQueue(track: Track): Boolean = send(ConnectCommand.AddToQueue(track.toPayload()))

    fun setAudioEnginePreset(preset: String): Boolean = send(ConnectCommand.AudioEnginePreset(preset))

    fun toggleAutoEq(enabled: Boolean): Boolean = send(ConnectCommand.AutoEq(enabled))

    fun toggleManualEq(enabled: Boolean): Boolean = send(ConnectCommand.ManualEq(enabled))

    /** Search the desktop for an equivalent playable payload before transfer. */
    fun transfer(track: Track?, positionMs: Long = 0): Boolean {
        if (track == null) {
            return send(ConnectCommand.Play)
        }
        pendingTransfer = PendingTransfer(track, positionMs)
        val requestId = "transfer-${System.currentTimeMillis()}"
        if (!client.search("${track.title} ${track.artist}", requestId)) {
            pendingTransfer = null
            mutableMessage.value = "Connect the device before transferring playback."
            return false
        }
        return true
    }

    override fun onStatusChanged(status: ConnectClientStatus) {
        mutableStatus.value = status
        mutableProtocolVersion.value = client.protocolVersion()
        publishDevice()
        when (status) {
            ConnectClientStatus.APPROVED -> {
                reconnectAttempt = 0
                reconnectJob?.cancel()
                mutableMessage.value = "Connected to ${deviceName()}."
            }
            ConnectClientStatus.DISCONNECTED -> if (!manualDisconnect && serverUrl.isNotBlank()) scheduleReconnect()
            ConnectClientStatus.AWAITING_APPROVAL -> mutableMessage.value = "Approve this phone in Orchard."
            ConnectClientStatus.PAIRING_EXPIRED -> mutableMessage.value = "The pairing code expired. Scan a new code."
            ConnectClientStatus.REJECTED -> mutableMessage.value = "The pairing request was declined."
            ConnectClientStatus.REVOKED -> mutableMessage.value = "This phone no longer has access to the device."
            else -> Unit
        }
    }

    override fun onSnapshot(snapshot: ConnectSnapshot) {
        mutableProtocolVersion.value = snapshot.protocolVersion
        mutableAudioEngine.value = snapshot.audioEngine
        mutableRemoteVolume.value = snapshot.playback.volume.toFloat()
        mutableSnapshot.value = snapshot.toPlaybackSnapshot()
    }

    override fun onSearchResults(results: ConnectResults) {
        val transfer = pendingTransfer ?: return
        pendingTransfer = null
        val match = results.items.firstOrNull { it.track.id == transfer.track.id }
            ?: results.items.firstOrNull {
                it.track.title.equals(transfer.track.title, true) && it.track.artist.contains(transfer.track.artist, true)
            }
        if (match == null) {
            mutableMessage.value = "That track is not available on the selected device."
            return
        }
        client.send(ConnectCommand.PlayTrack(match))
        if (transfer.positionMs > 1_500) client.send(ConnectCommand.Seek(transfer.positionMs / 1_000.0))
    }

    override fun onLibraryResults(results: ConnectResults) = Unit

    override fun onError(error: ConnectClientError) {
        Log.w(TAG, error.message, error)
        mutableMessage.value = error.message
    }

    private fun restoreKnownDevice() {
        scope.launch(Dispatchers.IO) {
            runCatching { sessionStore.load() }.onSuccess { stored ->
                serverUrl = stored.serverUrl
                publishDevice()
                if (serverUrl.isNotBlank()) pair(serverUrl)
            }.onFailure { Log.w(TAG, "Known Connect device could not be restored", it) }
        }
    }

    private fun scheduleReconnect() {
        if (reconnectJob?.isActive == true) return
        reconnectJob = scope.launch {
            while (!manualDisconnect && serverUrl.isNotBlank()) {
                delay(ConnectReconnectPolicy.delayMs(reconnectAttempt))
                reconnectAttempt += 1
                if (manualDisconnect || serverUrl.isBlank()) return@launch
                client.connect(serverUrl)

                // Ignore the StateFlow's pre-connect DISCONNECTED value, then
                // wait for this transport attempt to leave CONNECTING. Pending
                // approval remains connected and therefore needs no retry.
                val outcome = withTimeoutOrNull(CONNECTION_ATTEMPT_TIMEOUT_MS) {
                    status.dropWhile { it == ConnectClientStatus.DISCONNECTED }
                        .first { it != ConnectClientStatus.CONNECTING }
                } ?: ConnectClientStatus.DISCONNECTED.also { client.disconnect() }
                if (!ConnectReconnectPolicy.shouldRetry(outcome)) return@launch
            }
        }
    }

    private fun publishDevice() {
        mutableDevice.value = serverUrl.takeIf(String::isNotBlank)?.let {
            PlaybackDevice(
                id = it,
                name = deviceName(),
                type = DeviceType.COMPUTER,
                availability = if (client.status() == ConnectClientStatus.APPROVED) {
                    DeviceAvailability.ONLINE
                } else {
                    DeviceAvailability.OFFLINE
                },
            )
        }
    }

    private fun deviceName(): String = runCatching { URI(serverUrl).host }
        .getOrNull().orEmpty().ifBlank { "Orchard desktop" }

    private fun ConnectSnapshot.toPlaybackSnapshot(): PlaybackSnapshot {
        val tracks = queue.map { it.toTrack() }
        val active = track?.toTrack()
        val currentIndex = active?.let { current -> tracks.indexOfFirst { it.id == current.id } } ?: -1
        val mappedRepeat = when (playback.repeatMode.lowercase()) {
            "one" -> RepeatMode.ONE
            "all" -> RepeatMode.ALL
            else -> RepeatMode.OFF
        }
        return PlaybackSnapshot(
            status = when {
                playback.buffering -> PlaybackStatus.BUFFERING
                playback.isPlaying -> PlaybackStatus.PLAYING
                active != null -> PlaybackStatus.PAUSED
                else -> PlaybackStatus.IDLE
            },
            currentTrack = active,
            queue = tracks,
            currentIndex = currentIndex,
            positionMs = (playback.currentTime * 1_000).toLong(),
            durationMs = (playback.duration * 1_000).toLong(),
            bufferedPositionMs = 0,
            isPlaying = playback.isPlaying,
            shuffle = playback.shuffle,
            repeatMode = mappedRepeat,
        )
    }

    private fun ConnectTrack.toTrack() = Track(
        id = id,
        title = title,
        artist = artist,
        album = album,
        artworkUrl = artwork.ifBlank { thumbnail },
        animatedArtworkUrl = animatedArtwork,
        animatedArtworkVerticalUrl = animatedArtworkVertical,
    )

    private fun Track.toPayload(): org.json.JSONObject = org.json.JSONObject()
        .put("id", id)
        .put("title", title)
        .put("artist", artist)
        .put("album", album)
        .put("thumbnail", artworkUrl)
        .put("artwork", artworkUrl)
        .put("durationSeconds", durationMs / 1000.0)

    companion object {
        private const val TAG = "ConnectDeviceRepository"
        private const val CONNECTION_ATTEMPT_TIMEOUT_MS = 15_000L
    }
}
