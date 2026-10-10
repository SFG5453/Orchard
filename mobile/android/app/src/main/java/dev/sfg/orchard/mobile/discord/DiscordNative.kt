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

package dev.sfg.orchard.mobile.discord

import android.app.Activity
import android.util.Log
import com.discord.socialsdk.DiscordSocialSdkInit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface DiscordPresenceStatus {
    data object Unavailable : DiscordPresenceStatus
    data object Ready : DiscordPresenceStatus
    data object Sharing : DiscordPresenceStatus
    data class Error(val message: String) : DiscordPresenceStatus
}

/** Social SDK Rich Presence through the installed Discord app. */
object DiscordNative {
    private const val TAG = "DiscordNative"
    private val mutableStatus = MutableStateFlow<DiscordPresenceStatus>(DiscordPresenceStatus.Ready)
    val status: StateFlow<DiscordPresenceStatus> = mutableStatus.asStateFlow()

    @Volatile private var loaded = false

    /** The SDK takes its application context from an activity and loads libdiscord_partner_sdk on first use. */
    @Synchronized
    fun attach(activity: Activity) {
        runCatching {
            DiscordSocialSdkInit.setEngineActivity(activity)
            if (!loaded) {
                System.loadLibrary("orchard_discord_jni")
                loaded = true
            }
        }.onFailure { Log.e(TAG, "Discord SDK unavailable", it) }
    }

    val isLoaded: Boolean get() = loaded

    fun report(status: DiscordPresenceStatus) {
        mutableStatus.value = status
    }

    @JvmStatic
    fun onStatus(message: String?, ok: Boolean) {
        mutableStatus.value = if (ok) DiscordPresenceStatus.Sharing else DiscordPresenceStatus.Error(message.orEmpty())
    }

    @JvmStatic external fun start(applicationId: Long)
    @JvmStatic external fun stop()
    @JvmStatic external fun clearPresence()
    @JvmStatic external fun setPresence(
        type: Int,
        name: String,
        details: String,
        state: String,
        startMs: Long,
        endMs: Long,
        largeImage: String,
        largeText: String,
        smallImage: String,
        smallText: String,
        buttonLabels: Array<String>,
        buttonUrls: Array<String>,
    )
}
