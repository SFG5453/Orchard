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

import dev.sfg.orchard.mobile.model.DeviceAvailability
import dev.sfg.orchard.mobile.model.PlaybackDevice
import dev.sfg.orchard.mobile.model.PlaybackTarget
import dev.sfg.orchard.mobile.model.PlaybackTargetState

/**
 * Pure target-selection state machine.
 *
 * A transfer commits only after its destination is available. This invariant is
 * what prevents local and remote sessions from being marked active together.
 */
class PlaybackTargetCoordinator(localDevice: PlaybackDevice, private val selfWord: String = "phone") {
    private var phone = localDevice.copy(isLocal = true, isActive = true)
    var state: PlaybackTargetState = PlaybackTargetState(devices = listOf(phone))
        private set

    fun updateLocalDeviceName(name: String, customName: String = "") {
        phone = phone.copy(name = name, customName = customName)
        val selected = state.selected
        val remotes = state.devices.filterNot { it.isLocal }
        state = state.copy(
            devices = listOf(phone.copy(isActive = selected is PlaybackTarget.LocalPhone)) + remotes
        )
    }

    fun updateRemoteDevices(remotes: List<PlaybackDevice>) {
        val selectedId = (state.selected as? PlaybackTarget.Remote)?.deviceId
        val remoteAvailable = remotes.any {
            it.id == selectedId && it.availability == DeviceAvailability.ONLINE
        }
        val selected = if (selectedId != null && !remoteAvailable) PlaybackTarget.LocalPhone else state.selected
        state = state.copy(
            selected = selected,
            devices = listOf(phone.copy(isActive = selected is PlaybackTarget.LocalPhone)) + remotes.map {
                it.copy(isActive = selected is PlaybackTarget.Remote && selected.deviceId == it.id)
            },
            message = if (selectedId != null && !remoteAvailable) {
                "Control returned to this $selfWord; playback is paused."
            } else {
                state.message
            },
        )
    }

    fun beginTransfer(target: PlaybackTarget): Boolean {
        val available = when (target) {
            PlaybackTarget.LocalPhone -> true
            is PlaybackTarget.Remote -> state.devices.any {
                it.id == target.deviceId && it.availability == DeviceAvailability.ONLINE
            }
        }
        if (!available) {
            state = state.copy(message = "That playback device is unavailable.")
            return false
        }
        state = state.copy(isTransferring = true, message = "Transferring playback…")
        return true
    }

    fun completeTransfer(target: PlaybackTarget) {
        state = state.copy(
            selected = target,
            isTransferring = false,
            devices = state.devices.map { device ->
                device.copy(isActive = when (target) {
                    PlaybackTarget.LocalPhone -> device.isLocal
                    is PlaybackTarget.Remote -> device.id == target.deviceId
                })
            },
            message = when (target) {
                PlaybackTarget.LocalPhone -> "Playing on this $selfWord."
                is PlaybackTarget.Remote -> "Playing on ${state.devices.firstOrNull { it.id == target.deviceId }?.displayName ?: "Orchard device"}."
            },
        )
    }

    fun failTransfer(message: String) {
        state = state.copy(isTransferring = false, message = message)
    }
}
