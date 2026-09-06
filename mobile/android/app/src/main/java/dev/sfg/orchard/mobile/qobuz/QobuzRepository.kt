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

package dev.sfg.orchard.mobile.qobuz

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val PREFS_NAME = "orchard_qobuz"
private const val KEY_TOKEN = "qobuz_token"
private const val KEY_USER_ID = "qobuz_user_id"
private const val KEY_ENABLED = "qobuz_enabled"
private const val KEY_QUALITY = "qobuz_quality"

class QobuzRepository(
    context: Context,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _status = MutableStateFlow(loadInitialStatus())
    val status: StateFlow<QobuzStatus> = _status.asStateFlow()

    private fun loadInitialStatus(): QobuzStatus {
        val token = prefs.getString(KEY_TOKEN, "").orEmpty()
        val enabled = prefs.getBoolean(KEY_ENABLED, false)
        val qualityId = prefs.getString(KEY_QUALITY, QobuzQuality.AUTO.id)
        val quality = QobuzQuality.fromId(qualityId)
        return QobuzStatus(
            status = if (token.isNotBlank()) "connected" else "disconnected",
            enabled = enabled && token.isNotBlank(),
            quality = quality,
        )
    }

    fun getCredentials(): QobuzSession? {
        val token = prefs.getString(KEY_TOKEN, "").orEmpty()
        val userId = prefs.getLong(KEY_USER_ID, -1L)
        if (token.isBlank() || userId <= 0) return null
        return QobuzSession(token = token, userId = userId)
    }

    fun connect(token: String, userId: Long) {
        prefs.edit()
            .putString(KEY_TOKEN, token)
            .putLong(KEY_USER_ID, userId)
            .putBoolean(KEY_ENABLED, true)
            .apply()

        _status.value = _status.value.copy(
            status = "connected",
            enabled = true,
            lastError = "",
        )
    }

    fun disconnect() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_USER_ID)
            .putBoolean(KEY_ENABLED, false)
            .apply()

        _status.value = _status.value.copy(
            status = "disconnected",
            enabled = false,
            lastError = "",
        )
    }

    fun setEnabled(enabled: Boolean) {
        val hasToken = prefs.getString(KEY_TOKEN, "").orEmpty().isNotBlank()
        val effective = enabled && hasToken
        prefs.edit().putBoolean(KEY_ENABLED, effective).apply()
        _status.value = _status.value.copy(enabled = effective)
    }

    fun setQuality(quality: QobuzQuality) {
        prefs.edit().putString(KEY_QUALITY, quality.id).apply()
        _status.value = _status.value.copy(quality = quality)
    }

    fun setLastError(error: String) {
        _status.value = _status.value.copy(lastError = error)
    }
}
