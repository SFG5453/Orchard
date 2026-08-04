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

package dev.sfg.orchard.mobile.auth

import android.annotation.SuppressLint
import android.content.Context
import dev.sfg.orchard.mobile.security.AndroidKeystoreCipher
import org.json.JSONObject

/** Keeps the YouTube cookie session encrypted and excluded from Android backup. */
@SuppressLint("UseKtx") // commit() is intentional for security-critical writes.
class SecureYouTubeSessionStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private val cipher = AndroidKeystoreCipher(KEY_ALIAS)

    @Synchronized
    fun load(): YouTubeSession? {
        val stored = preferences.getString(SESSION, null) ?: return null
        val root = JSONObject(cipher.decrypt(stored))
        if (!root.has("cookie")) {
            // Device-code OAuth data from older builds is incompatible with native cookie auth.
            check(preferences.edit().remove(SESSION).commit()) { "Legacy credential deletion failed" }
            return null
        }
        return YouTubeSession(
            cookie = root.getString("cookie"),
            visitorData = root.optString("visitorData"),
            dataSyncId = YouTubeSessionAuth.normalizeDataSyncId(root.optString("dataSyncId")),
            displayName = root.optString("displayName", "YouTube Music").ifBlank { "YouTube Music" },
            avatarUrl = root.optString("avatarUrl"),
        ).takeIf { YouTubeSessionAuth.loginCookieValue(it.cookie) != null }
    }

    @Synchronized
    fun save(value: YouTubeSession) {
        require(YouTubeSessionAuth.loginCookieValue(value.cookie) != null) { "YouTube session cookie is incomplete" }
        val root = JSONObject()
            .put("cookie", value.cookie)
            .put("visitorData", value.visitorData)
            .put("dataSyncId", YouTubeSessionAuth.normalizeDataSyncId(value.dataSyncId))
            .put("displayName", value.displayName)
            .put("avatarUrl", value.avatarUrl)
        check(preferences.edit().putString(SESSION, cipher.encrypt(root.toString())).commit()) {
            "YouTube session storage failed"
        }
    }

    @Synchronized
    fun clear() {
        check(preferences.edit().remove(SESSION).commit()) { "YouTube session deletion failed" }
    }

    private companion object {
        const val FILE = "orchard_secure_oauth"
        const val SESSION = "youtube_credentials"
        const val KEY_ALIAS = "orchard_youtube_oauth_v1"
    }
}
