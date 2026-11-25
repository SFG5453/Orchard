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

package dev.sfg.orchard.mobile

import android.content.Context
import androidx.core.content.edit
import dev.sfg.orchard.connect.BuildConfig
import dev.sfg.orchard.mobile.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

internal enum class ReleaseNotesAction { SHOW, RECORD, NONE }

/** A first run only records its version, so new users go straight into the app. */
internal fun releaseNotesAction(lastSeen: String?, current: String, onboarded: Boolean): ReleaseNotesAction = when {
    lastSeen == current -> ReleaseNotesAction.NONE
    onboarded -> ReleaseNotesAction.SHOW
    else -> ReleaseNotesAction.RECORD
}

/** Opens the release notes once after an update. */
class ReleaseNotesPrompt(context: Context, settings: SettingsRepository, scope: CoroutineScope) {
    private val preferences = context.applicationContext.getSharedPreferences("orchard_release_notes", Context.MODE_PRIVATE)
    private val mutablePending = MutableStateFlow(false)
    val pending: StateFlow<Boolean> = mutablePending.asStateFlow()

    init {
        scope.launch {
            val onboarded = settings.loadedSettings.first().onboardingCompleted
            when (releaseNotesAction(preferences.getString(LAST_SEEN, null), BuildConfig.VERSION_NAME, onboarded)) {
                ReleaseNotesAction.SHOW -> mutablePending.value = true
                ReleaseNotesAction.RECORD -> record()
                ReleaseNotesAction.NONE -> Unit
            }
        }
    }

    fun dismiss() {
        mutablePending.value = false
        record()
    }

    private fun record() = preferences.edit { putString(LAST_SEEN, BuildConfig.VERSION_NAME) }

    private companion object {
        const val LAST_SEEN = "last_seen_version"
    }
}
