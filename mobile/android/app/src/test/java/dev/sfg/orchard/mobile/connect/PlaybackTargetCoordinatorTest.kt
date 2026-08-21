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
import dev.sfg.orchard.mobile.model.DeviceType
import dev.sfg.orchard.mobile.model.PlaybackDevice
import dev.sfg.orchard.mobile.model.PlaybackTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackTargetCoordinatorTest {
    private val phone = PlaybackDevice(
        id = "phone",
        name = "This phone",
        type = DeviceType.PHONE,
        availability = DeviceAvailability.ONLINE,
        isLocal = true,
    )
    private val desktop = PlaybackDevice(
        id = "desktop",
        name = "Studio",
        type = DeviceType.COMPUTER,
        availability = DeviceAvailability.ONLINE,
    )

    @Test
    fun localDeviceIsInitiallyTheOnlyActiveTarget() {
        val coordinator = PlaybackTargetCoordinator(phone)

        assertEquals(PlaybackTarget.LocalPhone, coordinator.state.selected)
        assertEquals(1, coordinator.state.devices.count { it.isActive })
        assertTrue(coordinator.state.devices.single().isLocal)
    }

    @Test
    fun localToRemoteTransferNeverMarksTwoTargetsActive() {
        val coordinator = PlaybackTargetCoordinator(phone)
        coordinator.updateRemoteDevices(listOf(desktop))
        val target = PlaybackTarget.Remote(desktop.id)

        assertTrue(coordinator.beginTransfer(target))
        coordinator.completeTransfer(target)

        assertEquals(target, coordinator.state.selected)
        assertEquals(1, coordinator.state.devices.count { it.isActive })
        assertTrue(coordinator.state.devices.single { it.isActive }.id == desktop.id)
    }

    @Test
    fun remoteToLocalTransferMakesPhoneAuthoritative() {
        val coordinator = PlaybackTargetCoordinator(phone)
        coordinator.updateRemoteDevices(listOf(desktop))
        coordinator.completeTransfer(PlaybackTarget.Remote(desktop.id))

        assertTrue(coordinator.beginTransfer(PlaybackTarget.LocalPhone))
        coordinator.completeTransfer(PlaybackTarget.LocalPhone)

        assertEquals(PlaybackTarget.LocalPhone, coordinator.state.selected)
        assertEquals(1, coordinator.state.devices.count { it.isActive })
        assertTrue(coordinator.state.devices.single { it.isActive }.isLocal)
    }

    @Test
    fun unavailableRemoteCannotStartTransfer() {
        val coordinator = PlaybackTargetCoordinator(phone)
        coordinator.updateRemoteDevices(listOf(desktop.copy(availability = DeviceAvailability.OFFLINE)))

        assertFalse(coordinator.beginTransfer(PlaybackTarget.Remote(desktop.id)))
        assertEquals(PlaybackTarget.LocalPhone, coordinator.state.selected)
    }

    @Test
    fun activeRemoteGoingOfflineReturnsSelectionToPhone() {
        val coordinator = PlaybackTargetCoordinator(phone)
        coordinator.updateRemoteDevices(listOf(desktop))
        coordinator.completeTransfer(PlaybackTarget.Remote(desktop.id))

        coordinator.updateRemoteDevices(listOf(desktop.copy(availability = DeviceAvailability.OFFLINE)))

        assertEquals(PlaybackTarget.LocalPhone, coordinator.state.selected)
        assertTrue(coordinator.state.devices.single { it.isActive }.isLocal)
    }

    @Test
    fun failedRemoteToLocalTransferKeepsRemoteAuthoritative() {
        val coordinator = PlaybackTargetCoordinator(phone)
        coordinator.updateRemoteDevices(listOf(desktop))
        val remote = PlaybackTarget.Remote(desktop.id)
        coordinator.completeTransfer(remote)

        coordinator.beginTransfer(PlaybackTarget.LocalPhone)
        coordinator.failTransfer("Remote pause was not confirmed.")

        assertEquals(remote, coordinator.state.selected)
        assertEquals(1, coordinator.state.devices.count { it.isActive })
        assertEquals("Remote pause was not confirmed.", coordinator.state.message)
    }

    @Test
    fun multipleRemoteDevicesAreTrackedAndSwitchedCorrectly() {
        val desktop2 = PlaybackDevice(
            id = "desktop-2",
            name = "Living Room Mac",
            type = DeviceType.COMPUTER,
            availability = DeviceAvailability.ONLINE,
        )
        val coordinator = PlaybackTargetCoordinator(phone)
        coordinator.updateRemoteDevices(listOf(desktop, desktop2))

        assertEquals(3, coordinator.state.devices.size)

        // Transfer to first desktop
        val target1 = PlaybackTarget.Remote(desktop.id)
        assertTrue(coordinator.beginTransfer(target1))
        coordinator.completeTransfer(target1)
        assertEquals(target1, coordinator.state.selected)
        assertTrue(coordinator.state.devices.first { it.id == desktop.id }.isActive)
        assertFalse(coordinator.state.devices.first { it.id == desktop2.id }.isActive)

        // Transfer from first desktop to second desktop
        val target2 = PlaybackTarget.Remote(desktop2.id)
        assertTrue(coordinator.beginTransfer(target2))
        coordinator.completeTransfer(target2)
        assertEquals(target2, coordinator.state.selected)
        assertFalse(coordinator.state.devices.first { it.id == desktop.id }.isActive)
        assertTrue(coordinator.state.devices.first { it.id == desktop2.id }.isActive)
    }

    @Test
    fun localDeviceRenameUpdatesCoordinatorStateAndDisplayName() {
        val coordinator = PlaybackTargetCoordinator(phone)
        assertEquals("This phone", coordinator.state.devices.first().displayName)

        coordinator.updateLocalDeviceName(name = "My Pixel 9", customName = "My Pixel 9")
        val updatedLocal = coordinator.state.devices.first()
        assertEquals("My Pixel 9", updatedLocal.name)
        assertEquals("My Pixel 9", updatedLocal.customName)
        assertEquals("My Pixel 9", updatedLocal.displayName)
    }

    @Test
    fun remoteDeviceCustomNameDisplayNameFallback() {
        val renamedDesktop = desktop.copy(customName = "Studio Main DAW")
        assertEquals("Studio Main DAW", renamedDesktop.displayName)
        assertEquals("Studio", renamedDesktop.name)

        val coordinator = PlaybackTargetCoordinator(phone)
        coordinator.updateRemoteDevices(listOf(renamedDesktop))
        val remoteTarget = PlaybackTarget.Remote(renamedDesktop.id)
        coordinator.beginTransfer(remoteTarget)
        coordinator.completeTransfer(remoteTarget)

        assertEquals("Playing on Studio Main DAW.", coordinator.state.message)
    }
}
