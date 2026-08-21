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

package dev.sfg.orchard.mobile.playback

import android.content.Context
import android.util.Log
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.social.DEFAULT_PARTY_SERVICE_URL
import dev.sfg.orchard.mobile.social.ListeningPartyClient
import dev.sfg.orchard.mobile.social.PartyEvent
import dev.sfg.orchard.mobile.social.PartyPlaybackState
import dev.sfg.orchard.mobile.social.PartyState
import dev.sfg.orchard.mobile.social.PartyStateJson
import dev.sfg.orchard.mobile.social.PartyStatus
import dev.sfg.orchard.mobile.social.PartyTrackJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import org.json.JSONObject
import kotlin.math.abs

/**
 * Binds a [ListeningPartyClient] to local playback.
 *
 * The host is the only device that broadcasts. Everyone else applies what arrives and, when the
 * listener touches a transport control, asks the host to make the change rather than making it
 * locally — a guest that simply pressed play would drift until the host's next tick dragged it
 * back, which reads as the button not working.
 */
class ListeningPartyManager(
    private val context: Context,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
    private val player: LocalPlaybackController,
    private val serviceUrl: String = DEFAULT_PARTY_SERVICE_URL,
    private var displayName: String,
) {
    fun updateDisplayName(name: String) {
        if (name.isNotBlank()) displayName = name
    }
    private val mutableState = MutableStateFlow(PartyState())
    val state: StateFlow<PartyState> = mutableState.asStateFlow()

    private val mutableMessages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** Human-readable notices for the UI to surface, matching the web's share-message toasts. */
    val messages: SharedFlow<String> = mutableMessages.asSharedFlow()

    private var client: ListeningPartyClient? = null
    private var clientJobs = mutableListOf<Job>()

    /**
     * When the echo guard lapses, as epoch milliseconds.
     *
     * Applying a host snapshot moves the local player, which the host-side watcher would otherwise
     * read as a local change worth broadcasting — a loop the two devices would keep feeding. A
     * deadline rather than a flag-and-timer because it needs no cancellation and cannot be left
     * stuck on if the applying coroutine is cancelled midway.
     */
    @Volatile
    private var applyingUntil = 0L

    private val isApplying: Boolean get() = System.currentTimeMillis() < applyingUntil

    // ---------------------------------------------------------------- lifecycle

    suspend fun createParty() {
        runClient { it.createRoom(displayName) }
        mutableMessages.tryEmit("Listening party started. Share code ${mutableState.value.code}.")
    }

    suspend fun joinParty(code: String) {
        runClient { it.joinRoom(code, displayName) }
        mutableMessages.tryEmit("Joined the listening party.")
    }

    private suspend fun runClient(start: suspend (ListeningPartyClient) -> PartyState) {
        leaveParty(closeRoom = true)
        val created = ListeningPartyClient(context, http, serviceUrl, displayName)
        client = created
        // Set before binding: creating or joining a room is an HTTP round trip, and the client
        // stays idle for its whole duration. Without this the panel would show its join form again
        // for as long as the request took, which is exactly when a second tap would be tempting.
        mutableState.value = PartyState(status = PartyStatus.CONNECTING)
        bind(created)
        try {
            start(created)
        } catch (error: Throwable) {
            // Only tear down the client this call created. A second attempt may already have
            // replaced it, and disposing that one would kill a party that is connecting fine.
            if (client === created) leaveParty(closeRoom = false)
            mutableState.value = PartyState(error = error.message.orEmpty())
            throw error
        }
    }

    /** Leaves the party. A host closes the room behind it unless [closeRoom] says otherwise. */
    fun leaveParty(closeRoom: Boolean = true) {
        val active = client ?: return
        if (closeRoom && active.state.value.isHost) active.closeRoom()
        clientJobs.forEach(Job::cancel)
        clientJobs = mutableListOf()
        active.release()
        client = null
        applyingUntil = 0L
        mutableState.value = PartyState()
    }

    private fun bind(active: ListeningPartyClient) {
        clientJobs += scope.launch {
            active.state.collect { partyState ->
                // A fresh client publishes idle until its first real transition. Letting that
                // through would undo the connecting state set just above; a genuine failure
                // arrives as a thrown error instead, and is handled where the party is started.
                if (partyState.status == PartyStatus.IDLE &&
                    mutableState.value.status == PartyStatus.CONNECTING
                ) {
                    return@collect
                }
                val wasHost = mutableState.value.isHost
                mutableState.value = partyState
                // A device promoted mid-party has to publish immediately; the others are waiting on
                // a snapshot that would otherwise not arrive until playback next changed.
                if (!wasHost && partyState.isHost && partyState.status == PartyStatus.CONNECTED) {
                    broadcast("host-transfer")
                }
            }
        }
        clientJobs += scope.launch {
            active.events.collect { event ->
                // Read the role off the client rather than the mirror above. The two are updated by
                // separate coroutines, so on a welcome the mirror can still be a step behind, and a
                // host would briefly look like a guest and apply the snapshot it is meant to own.
                val self = active.state.value
                when (event) {
                    is PartyEvent.State -> if (!self.isHost) applyState(event.payload)
                    is PartyEvent.Request -> if (self.isHost) handleRequest(event)
                    is PartyEvent.HostChanged ->
                        if (event.hostId == self.participant?.id) {
                            mutableMessages.tryEmit("You are now hosting the listening party.")
                        }
                    is PartyEvent.Closed -> {
                        mutableMessages.tryEmit("The listening party ended.")
                        leaveParty(closeRoom = false)
                    }
                    is PartyEvent.Failed -> mutableMessages.tryEmit(event.message)
                }
            }
        }
        clientJobs += scope.launch { watchPlayback() }
        clientJobs += scope.launch { runSyncClock() }
    }

    // ---------------------------------------------------------------- host side

    /**
     * Broadcasts whenever the *shape* of playback changes.
     *
     * Position is deliberately not part of the signature: it advances every half second, and
     * sending a snapshot each time would put a hundred messages through the room per track for
     * information the drift correction already reconstructs from [PartyPlaybackState.sentAt].
     */
    private suspend fun watchPlayback() {
        player.snapshot
            .map { snapshot ->
                listOf(
                    snapshot.currentTrack?.id.orEmpty(),
                    snapshot.isPlaying.toString(),
                    snapshot.currentIndex.toString(),
                    snapshot.queue.joinToString(",") { it.id },
                )
            }
            .distinctUntilChanged()
            .collect { broadcast("playback") }
    }

    private suspend fun runSyncClock() {
        while (true) {
            delay(SYNC_INTERVAL_MS)
            broadcast("clock")
        }
    }

    private fun broadcast(reason: String) {
        val active = client ?: return
        if (!active.state.value.isHost || isApplying) return
        active.broadcast(PARTY_STATE, PartyStateJson.encode(snapshotState(reason)))
    }

    /**
     * Splits the local playlist into the history/current/queue triple the web models separately.
     *
     * Media3 keeps one list with a cursor, so what precedes the cursor is the history and what
     * follows is the queue. Sending the whole list as `queue` would replay everything already
     * played on every guest.
     */
    private fun snapshotState(reason: String): PartyPlaybackState {
        val snapshot = player.snapshot.value
        return PartyPlaybackState(
            reason = reason,
            track = snapshot.currentTrack,
            queue = snapshot.upcoming.take(PartyStateJson.MAX_QUEUE),
            history = snapshot.history.takeLast(PartyStateJson.MAX_HISTORY),
            mediaKind = "audio",
            isPlaying = snapshot.isPlaying,
            currentTime = snapshot.positionMs / 1000.0,
            duration = snapshot.durationMs / 1000.0,
            sentAt = System.currentTimeMillis(),
        )
    }

    private fun handleRequest(event: PartyEvent.Request) {
        val snapshot = player.snapshot.value
        val payload = event.payload
        when (event.action) {
            "play" -> player.play()
            "pause" -> player.pause()
            "next" -> player.next()
            "previous" -> player.previous()
            "seek" -> player.seek((payload.optDouble("currentTime", 0.0) * 1000).toLong())
            "toggle-shuffle" -> player.setShuffle(!snapshot.shuffle)
            "cycle-repeat" -> player.cycleRepeat()
            "clear-queue" -> player.clearUpcoming()
            "play-next" -> requestedTrack(payload)?.let(player::playNext)
            "add-queue" -> requestedTrack(payload)?.let(player::addToQueue)
            "play-track" -> requestedTrack(payload)?.let { track ->
                val existing = snapshot.queue.indexOfFirst { it.id == track.id }
                // Jumping to a copy already queued keeps the rest of the queue intact; only a track
                // the room has never seen justifies rebuilding it.
                if (existing >= 0) player.playQueueIndex(existing) else {
                    player.replaceQueue(listOf(track) + snapshot.upcoming)
                }
            }
            // Queue indices on the wire count from the first *upcoming* track, because that is what
            // the web's queue panel shows. Media3 indexes the whole playlist, history included.
            "remove-queue" -> upcomingIndex(payload.optInt("index", -1), snapshot)?.let(player::remove)
            "move-queue" -> {
                val from = upcomingIndex(payload.optInt("fromIndex", -1), snapshot)
                val to = upcomingIndex(payload.optInt("toIndex", -1), snapshot)
                if (from != null && to != null) player.move(from, to)
            }
            "state" -> payload.optJSONObject("state")?.let {
                scope.launch { applyState(PartyStateJson.decode(it)) }
            }
            else -> Log.d(TAG, "Ignoring unknown party request: ${event.action}")
        }
        broadcast("request:${event.action.ifBlank { "state" }}")
    }

    private fun requestedTrack(payload: JSONObject): Track? =
        payload.optJSONObject("track")?.let(PartyTrackJson::decode)?.takeIf { it.id.isNotBlank() }

    private fun upcomingIndex(index: Int, snapshot: PlaybackSnapshot): Int? {
        if (index < 0) return null
        val absolute = snapshot.currentIndex.coerceAtLeast(0) + 1 + index
        return absolute.takeIf { it in snapshot.queue.indices }
    }

    // ---------------------------------------------------------------- guest side

    /**
     * Applies a host snapshot to the local player.
     *
     * The elapsed time since the host sent it is added back so a guest lands where the host is
     * *now* rather than where it was when the packet left, and the correction is only applied to a
     * playing room — adding drift to a paused one would walk the position forward on every tick.
     */
    private fun applyState(state: PartyPlaybackState) {
        val track = state.track ?: return
        applyingUntil = System.currentTimeMillis() + ECHO_GUARD_MS

        val drift = if (state.isPlaying) {
            ((System.currentTimeMillis() - state.sentAt).coerceAtLeast(0L)) / 1000.0
        } else {
            0.0
        }
        val targetMs = ((state.currentTime + drift).coerceAtLeast(0.0) * 1000).toLong()
        val snapshot = player.snapshot.value

        if (snapshot.currentTrack?.id != track.id) {
            // Rebuilt as history + current + queue so the guest's "previous" walks back through the
            // same tracks the host's does, rather than dead-ending at the song it joined on.
            player.replaceQueue(
                tracks = state.history + track + state.queue,
                startIndex = state.history.size,
                positionMs = targetMs,
                play = state.isPlaying,
                contextTitle = PARTY_CONTEXT_TITLE,
            )
            return
        }

        if (!sameTrackOrder(snapshot.upcoming, state.queue)) player.replaceUpcoming(state.queue)
        if (abs(snapshot.positionMs - targetMs) > SEEK_TOLERANCE_MS) player.seek(targetMs)
        if (state.isPlaying && !snapshot.isPlaying) player.play()
        if (!state.isPlaying && snapshot.isPlaying) player.pause()
    }

    /**
     * Forwards a transport action to the host when this device is a guest.
     *
     * Returns true when the party consumed it, so the caller leaves the local player alone. A host,
     * an idle device, or one mid-apply all return false and act locally as usual.
     */
    fun interceptTransport(action: String, payload: JSONObject = JSONObject()): Boolean {
        val active = client ?: return false
        val current = active.state.value
        if (current.status != PartyStatus.CONNECTED || current.isHost || isApplying) return false
        active.requestHost(payload.put("action", action))
        return true
    }

    fun requestSeek(positionMs: Long): Boolean =
        interceptTransport("seek", JSONObject().put("currentTime", positionMs / 1000.0))

    fun requestPlayTrack(track: Track): Boolean =
        interceptTransport("play-track", JSONObject().put("track", PartyTrackJson.encode(track)))

    /** Hands the host seat to another participant. Ignored unless this device is the host. */
    fun transferHost(participantId: String) {
        val active = client ?: return
        if (!active.state.value.isHost) return
        active.transferHost(participantId)
    }

    private companion object {
        const val TAG = "ListeningPartyManager"
        const val PARTY_STATE = "party:state"
        const val PARTY_CONTEXT_TITLE = "Listening party"

        /** Matches the web's `PARTY_SYNC_INTERVAL_MS`, so both hosts tick at the same rate. */
        const val SYNC_INTERVAL_MS = 2_500L

        /** The web's tolerance too: below this a correction is more audible than the drift. */
        const val SEEK_TOLERANCE_MS = 1_250L

        /** How long an applied snapshot suppresses re-broadcasting, matching the web's 250ms. */
        const val ECHO_GUARD_MS = 250L
    }
}

private fun sameTrackOrder(left: List<Track>, right: List<Track>): Boolean =
    left.size == right.size && left.indices.all { left[it].id == right[it].id }
