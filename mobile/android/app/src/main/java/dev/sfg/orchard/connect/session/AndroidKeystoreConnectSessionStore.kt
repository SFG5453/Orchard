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

import android.content.Context
import dev.sfg.orchard.mobile.security.AndroidKeystoreCipher
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * Android session store with an AES/GCM device token protected by AndroidKeyStore.
 *
 * Supports storing and managing multiple paired Orchard Connect desktop devices,
 * with encrypted tokens, custom names/aliases, and seamless migration from legacy single-device stores.
 * Methods are synchronized because Socket.IO callbacks and Activity lifecycle work may reach the store concurrently.
 */
class AndroidKeystoreConnectSessionStore(context: Context) : ConnectSessionStore {
    private val cipher = AndroidKeystoreCipher("orchard_connect_device_token_v1")
    private val preferences = context.applicationContext.getSharedPreferences(
        ConnectStorageKeys.PREFERENCES,
        Context.MODE_PRIVATE
    )

    @Synchronized
    override fun load(): StoredConnectSession = protect("read Connect session") {
        val devices = loadDevicesInternal()
        if (devices.isNotEmpty()) {
            val primary = devices.first()
            return@protect StoredConnectSession(
                serverUrl = primary.serverUrl,
                serverHost = primary.serverHost,
                deviceToken = primary.deviceToken
            )
        }
        val stored = preferences.getString(ConnectStorageKeys.DEVICE_TOKEN, "").orEmpty()
        val token = when {
            stored.isEmpty() -> ""
            cipher.isEncrypted(stored) -> cipher.decrypt(stored)
            else -> stored.also { saveDeviceToken(it) }
        }
        StoredConnectSession(
            serverUrl = preferences.getString(ConnectStorageKeys.SERVER_URL, "").orEmpty(),
            serverHost = preferences.getString(ConnectStorageKeys.SERVER_HOST, "").orEmpty(),
            deviceToken = token
        )
    }

    @Synchronized
    override fun loadDevices(): List<StoredPairedDevice> = protect("load paired Connect devices") {
        loadDevicesInternal()
    }

    @Synchronized
    override fun saveDevice(device: StoredPairedDevice) = protect("save paired Connect device") {
        val current = loadDevicesInternal().toMutableList()
        val index = current.indexOfFirst {
            it.id == device.id || (it.serverUrl.isNotBlank() && it.serverUrl == device.serverUrl)
        }
        val updated = if (index >= 0) {
            val existing = current[index]
            device.copy(
                customName = device.customName.ifBlank { existing.customName },
                defaultName = device.defaultName.ifBlank { existing.defaultName },
                deviceToken = device.deviceToken.ifBlank { existing.deviceToken }
            )
        } else {
            device
        }

        if (index >= 0) {
            current[index] = updated
        } else {
            current.add(0, updated)
        }
        saveDevicesInternal(current)

        // Keep legacy keys in sync with the primary/most recently paired device
        preferences.edit()
            .putString(ConnectStorageKeys.SERVER_URL, updated.serverUrl)
            .putString(ConnectStorageKeys.SERVER_HOST, updated.serverHost)
            .apply {
                if (updated.deviceToken.isNotBlank()) {
                    putString(ConnectStorageKeys.DEVICE_TOKEN, cipher.encrypt(updated.deviceToken))
                }
            }
            .commitOrThrow()
    }

    @Synchronized
    override fun removeDevice(idOrServerUrl: String) = protect("remove paired Connect device") {
        val current = loadDevicesInternal().filterNot {
            it.id == idOrServerUrl || it.serverUrl == idOrServerUrl
        }
        saveDevicesInternal(current)
        if (current.isEmpty()) {
            preferences.edit()
                .remove(ConnectStorageKeys.DEVICE_TOKEN)
                .remove(ConnectStorageKeys.SERVER_HOST)
                .remove(ConnectStorageKeys.SERVER_URL)
                .commitOrThrow()
        } else {
            val primary = current.first()
            preferences.edit()
                .putString(ConnectStorageKeys.SERVER_URL, primary.serverUrl)
                .putString(ConnectStorageKeys.SERVER_HOST, primary.serverHost)
                .apply {
                    if (primary.deviceToken.isNotBlank()) {
                        putString(ConnectStorageKeys.DEVICE_TOKEN, cipher.encrypt(primary.deviceToken))
                    } else {
                        remove(ConnectStorageKeys.DEVICE_TOKEN)
                    }
                }
                .commitOrThrow()
        }
    }

    @Synchronized
    override fun updateDeviceName(idOrServerUrl: String, customName: String) = protect("update device name") {
        val current = loadDevicesInternal().map { dev ->
            if (dev.id == idOrServerUrl || dev.serverUrl == idOrServerUrl) {
                dev.copy(customName = customName.trim())
            } else {
                dev
            }
        }
        saveDevicesInternal(current)
    }

    @Synchronized
    override fun clearAllDevices() = protect("clear all paired Connect devices") {
        preferences.edit()
            .remove(ConnectStorageKeys.PAIRED_DEVICES)
            .remove(ConnectStorageKeys.DEVICE_TOKEN)
            .remove(ConnectStorageKeys.SERVER_HOST)
            .remove(ConnectStorageKeys.SERVER_URL)
            .commitOrThrow()
    }

    @Synchronized
    override fun saveLocation(serverUrl: String, serverHost: String) = protect("save Connect endpoint") {
        preferences.edit()
            .putString(ConnectStorageKeys.SERVER_URL, serverUrl)
            .putString(ConnectStorageKeys.SERVER_HOST, serverHost)
            .commitOrThrow()

        // Also update or add in multi-device list
        val current = loadDevicesInternal().toMutableList()
        val defaultName = runCatching { URI(serverUrl).host }.getOrNull().orEmpty().ifBlank { "Orchard desktop" }
        val id = serverUrl
        val index = current.indexOfFirst { it.id == id || it.serverUrl == serverUrl }
        if (index >= 0) {
            current[index] = current[index].copy(serverUrl = serverUrl, serverHost = serverHost)
        } else {
            current.add(0, StoredPairedDevice(id = id, serverUrl = serverUrl, serverHost = serverHost, defaultName = defaultName))
        }
        saveDevicesInternal(current)
    }

    @Synchronized
    override fun saveDeviceToken(deviceToken: String) = protect("save Connect credential") {
        require(deviceToken.isNotBlank() && deviceToken.length <= 128) { "Invalid device token" }
        preferences.edit()
            .putString(ConnectStorageKeys.DEVICE_TOKEN, cipher.encrypt(deviceToken))
            .commitOrThrow()

        // Also update active device in multi-device list if present
        val serverUrl = preferences.getString(ConnectStorageKeys.SERVER_URL, "").orEmpty()
        if (serverUrl.isNotBlank()) {
            val current = loadDevicesInternal().toMutableList()
            val index = current.indexOfFirst { it.id == serverUrl || it.serverUrl == serverUrl }
            if (index >= 0) {
                current[index] = current[index].copy(deviceToken = deviceToken)
                saveDevicesInternal(current)
            }
        }
    }

    @Synchronized
    override fun clearDeviceToken() = protect("clear Connect credential") {
        preferences.edit().remove(ConnectStorageKeys.DEVICE_TOKEN).commitOrThrow()
        val serverUrl = preferences.getString(ConnectStorageKeys.SERVER_URL, "").orEmpty()
        if (serverUrl.isNotBlank()) {
            val current = loadDevicesInternal().map {
                if (it.id == serverUrl || it.serverUrl == serverUrl) it.copy(deviceToken = "") else it
            }
            saveDevicesInternal(current)
        }
    }

    @Synchronized
    override fun clear() = protect("clear Connect session") {
        clearAllDevices()
    }

    private fun loadDevicesInternal(): List<StoredPairedDevice> {
        val raw = preferences.getString(ConnectStorageKeys.PAIRED_DEVICES, null)
        if (!raw.isNullOrBlank()) {
            return decodeDevicesJson(raw)
        }
        // Legacy migration: check if legacy serverUrl or deviceToken exists
        val legacyUrl = preferences.getString(ConnectStorageKeys.SERVER_URL, "").orEmpty()
        val legacyHost = preferences.getString(ConnectStorageKeys.SERVER_HOST, "").orEmpty()
        val legacyTokenEncrypted = preferences.getString(ConnectStorageKeys.DEVICE_TOKEN, "").orEmpty()
        if (legacyUrl.isNotBlank()) {
            val token = when {
                legacyTokenEncrypted.isEmpty() -> ""
                cipher.isEncrypted(legacyTokenEncrypted) -> cipher.decrypt(legacyTokenEncrypted)
                else -> legacyTokenEncrypted
            }
            val defaultName = runCatching { URI(legacyUrl).host }.getOrNull().orEmpty().ifBlank { "Orchard desktop" }
            val migrated = listOf(
                StoredPairedDevice(
                    id = legacyUrl,
                    serverUrl = legacyUrl,
                    serverHost = legacyHost,
                    deviceToken = token,
                    defaultName = defaultName,
                    customName = ""
                )
            )
            saveDevicesInternal(migrated)
            return migrated
        }
        return emptyList()
    }

    private fun saveDevicesInternal(devices: List<StoredPairedDevice>) {
        val jsonArray = JSONArray()
        for (device in devices) {
            val obj = JSONObject()
            obj.put("id", device.id)
            obj.put("serverUrl", device.serverUrl)
            obj.put("serverHost", device.serverHost)
            obj.put("defaultName", device.defaultName)
            obj.put("customName", device.customName)
            obj.put("pairedAt", device.pairedAt)
            obj.put("lastSeenAt", device.lastSeenAt)
            if (device.deviceToken.isNotBlank()) {
                obj.put("deviceToken", cipher.encrypt(device.deviceToken))
            } else {
                obj.put("deviceToken", "")
            }
            jsonArray.put(obj)
        }
        preferences.edit().putString(ConnectStorageKeys.PAIRED_DEVICES, jsonArray.toString()).commitOrThrow()
    }

    private fun decodeDevicesJson(jsonStr: String): List<StoredPairedDevice> = runCatching {
        val array = JSONArray(jsonStr)
        val list = mutableListOf<StoredPairedDevice>()
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            val storedToken = obj.optString("deviceToken", "")
            val decryptedToken = when {
                storedToken.isEmpty() -> ""
                cipher.isEncrypted(storedToken) -> cipher.decrypt(storedToken)
                else -> storedToken
            }
            list.add(
                StoredPairedDevice(
                    id = obj.optString("id", ""),
                    serverUrl = obj.optString("serverUrl", ""),
                    serverHost = obj.optString("serverHost", ""),
                    deviceToken = decryptedToken,
                    defaultName = obj.optString("defaultName", ""),
                    customName = obj.optString("customName", ""),
                    pairedAt = obj.optLong("pairedAt", System.currentTimeMillis()),
                    lastSeenAt = obj.optLong("lastSeenAt", System.currentTimeMillis())
                )
            )
        }
        list
    }.getOrDefault(emptyList())

    private inline fun <T> protect(action: String, block: () -> T): T = try {
        block()
    } catch (error: SessionStoreException) {
        throw error
    } catch (error: Throwable) {
        throw SessionStoreException("Could not $action securely.", error)
    }

    private fun android.content.SharedPreferences.Editor.commitOrThrow() {
        if (!commit()) throw IllegalStateException("SharedPreferences commit failed")
    }
}
