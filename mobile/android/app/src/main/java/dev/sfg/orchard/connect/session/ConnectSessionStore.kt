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

package dev.sfg.orchard.connect.session

/** Last approved desktop endpoint and its opaque device credential. */
data class StoredConnectSession(
    val serverUrl: String = "",
    val serverHost: String = "",
    val deviceToken: String = ""
)

/** Persistent record of a paired Orchard Connect device. */
data class StoredPairedDevice(
    val id: String,
    val serverUrl: String,
    val serverHost: String,
    val deviceToken: String = "",
    val defaultName: String = "",
    val customName: String = "",
    val pairedAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long = System.currentTimeMillis()
)

/**
 * Credential persistence boundary owned by the application process.
 * Implementations must never log or expose [StoredConnectSession.deviceToken] or [StoredPairedDevice.deviceToken].
 */
interface ConnectSessionStore {
    @Throws(SessionStoreException::class)
    fun load(): StoredConnectSession

    @Throws(SessionStoreException::class)
    fun loadDevices(): List<StoredPairedDevice> = emptyList()

    @Throws(SessionStoreException::class)
    fun saveDevice(device: StoredPairedDevice) {}

    @Throws(SessionStoreException::class)
    fun removeDevice(idOrServerUrl: String) {}

    @Throws(SessionStoreException::class)
    fun updateDeviceName(idOrServerUrl: String, customName: String) {}

    @Throws(SessionStoreException::class)
    fun clearAllDevices() {}

    @Throws(SessionStoreException::class)
    fun saveLocation(serverUrl: String, serverHost: String)

    @Throws(SessionStoreException::class)
    fun saveDeviceToken(deviceToken: String)

    @Throws(SessionStoreException::class)
    fun clearDeviceToken()

    @Throws(SessionStoreException::class)
    fun clear()
}

class SessionStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Storage keys are centralized to preserve upgrades from the original app. */
object ConnectStorageKeys {
    const val PREFERENCES = "orchard-connect"
    const val DEVICE_TOKEN = "orchard-connect:device-token"
    const val SERVER_HOST = "orchard-connect:server-host"
    const val SERVER_URL = "orchard-connect:server-url"
    const val PAIRED_DEVICES = "orchard-connect:paired-devices"
}
