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

package dev.sfg.orchard.mobile.app

import android.app.Application
import android.os.Build
import dev.sfg.orchard.mobile.OrchardGraph
import dev.sfg.orchard.mobile.audio.selfDeviceLabel
import dev.sfg.orchard.mobile.connect.ConnectDevice
import dev.sfg.orchard.mobile.connect.ConnectState
import dev.sfg.orchard.mobile.model.DeviceAvailability
import dev.sfg.orchard.mobile.model.DeviceType
import dev.sfg.orchard.mobile.model.PlaybackDevice
import dev.sfg.orchard.mobile.model.PlaybackTarget
import dev.sfg.orchard.mobile.model.PlaybackTargetState
import dev.sfg.orchard.mobile.playback.ListeningPartyManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Where playback runs: this phone, or an Orchard Connect target it controls. The selection is
 * read from Connect's session state, so it switches only once a target is really connected.
 */
internal class PlaybackTargets(
    private val application: Application,
    private val graph: OrchardGraph,
    private val scope: CoroutineScope,
    private val party: ListeningPartyManager,
) {
    private val localName = graph.settings.settings.map { it.customDeviceName }.distinctUntilChanged()

    val state: StateFlow<PlaybackTargetState> = combine(graph.connect.state, localName) { connect, custom ->
        targetState(connect, custom)
    }.stateIn(scope, SharingStarted.Eagerly, PlaybackTargetState(devices = listOf(localDevice("", true))))

    private fun baseDeviceName() = Build.MODEL.takeIf(String::isNotBlank) ?: application.selfDeviceLabel()

    private fun localDevice(custom: String, active: Boolean) = PlaybackDevice(
        id = LOCAL_ID,
        name = baseDeviceName(),
        customName = custom,
        type = DeviceType.PHONE,
        availability = DeviceAvailability.ONLINE,
        isLocal = true,
        isActive = active,
    )

    private fun targetState(connect: ConnectState, custom: String): PlaybackTargetState {
        val controlling = connect.controllerSession?.takeIf { it.state == "CONNECTED" || it.state == "RECONNECTING" }
        val establishing = connect.pending ?: connect.controllerSession?.takeIf { it.establishing }?.peer
        val remotes = connect.devices.map { it.toPlaybackDevice(active = it.id == controlling?.peer?.id) }
        return PlaybackTargetState(
            selected = controlling?.let { PlaybackTarget.Remote(it.peer.id) } ?: PlaybackTarget.LocalPhone,
            devices = listOf(localDevice(custom, active = controlling == null)) + remotes,
            isTransferring = establishing != null,
            message = connect.message,
        )
    }

    private fun ConnectDevice.toPlaybackDevice(active: Boolean) = PlaybackDevice(
        id = id,
        name = name,
        type = if (isDesktop) DeviceType.COMPUTER else DeviceType.PHONE,
        availability = if (compatible) DeviceAvailability.ONLINE else DeviceAvailability.UNAVAILABLE,
        isActive = active,
    )

    fun select(target: PlaybackTarget) {
        when (target) {
            PlaybackTarget.LocalPhone -> graph.connect.stopControlling()
            is PlaybackTarget.Remote -> if (target != state.value.selected) graph.connect.connectTo(target.deviceId)
        }
    }

    fun renameLocal(newName: String) {
        graph.settings.updateSettings(graph.settings.settings.value.copy(customDeviceName = newName.trim()))
    }

    fun observe() {
        scope.launch {
            localName.collect { custom -> party.updateDisplayName(custom.ifBlank { baseDeviceName() }) }
        }
    }

    companion object {
        const val LOCAL_ID = "local-phone"
    }
}
