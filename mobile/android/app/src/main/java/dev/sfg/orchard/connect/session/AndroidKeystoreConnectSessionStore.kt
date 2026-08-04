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

/**
 * Android session store with an AES/GCM device token protected by AndroidKeyStore.
 *
 * The server URL and host are non-secret LAN routing hints. The device token is
 * encrypted at rest, migrated once from the original plaintext preference, and
 * never backed up (`android:allowBackup=false`). Methods are synchronized because
 * Socket.IO callbacks and Activity lifecycle work may reach the store concurrently.
 */
class AndroidKeystoreConnectSessionStore(context: Context) : ConnectSessionStore {
    private val cipher = AndroidKeystoreCipher("orchard_connect_device_token_v1")
    private val preferences = context.applicationContext.getSharedPreferences(
        ConnectStorageKeys.PREFERENCES,
        Context.MODE_PRIVATE
    )

    @Synchronized
    override fun load(): StoredConnectSession = protect("read Connect session") {
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
    override fun saveLocation(serverUrl: String, serverHost: String) = protect("save Connect endpoint") {
        preferences.edit()
            .putString(ConnectStorageKeys.SERVER_URL, serverUrl)
            .putString(ConnectStorageKeys.SERVER_HOST, serverHost)
            .commitOrThrow()
    }

    @Synchronized
    override fun saveDeviceToken(deviceToken: String) = protect("save Connect credential") {
        require(deviceToken.isNotBlank() && deviceToken.length <= 128) { "Invalid device token" }
        preferences.edit()
            .putString(ConnectStorageKeys.DEVICE_TOKEN, cipher.encrypt(deviceToken))
            .commitOrThrow()
    }

    @Synchronized
    override fun clearDeviceToken() = protect("clear Connect credential") {
        preferences.edit().remove(ConnectStorageKeys.DEVICE_TOKEN).commitOrThrow()
    }

    @Synchronized
    override fun clear() = protect("clear Connect session") {
        preferences.edit()
            .remove(ConnectStorageKeys.DEVICE_TOKEN)
            .remove(ConnectStorageKeys.SERVER_HOST)
            .remove(ConnectStorageKeys.SERVER_URL)
            .commitOrThrow()
    }

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
