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

import org.json.JSONObject

/** Another Orchard install online on the same account, as the hub reports it. */
data class ConnectDevice(
    val id: String,
    val name: String,
    val platform: String = "",
    val kind: String = "",
    /** False for a device on another Connect protocol major; it cannot be selected. */
    val compatible: Boolean = true,
    val nowPlayingTitle: String = "",
    val nowPlayingArtist: String = "",
    val playing: Boolean = false,
) {
    val isDesktop: Boolean get() = kind == "desktop"

    companion object {
        fun from(json: JSONObject): ConnectDevice {
            val playingNow = json.optJSONObject("currently_playing")
            return ConnectDevice(
                id = json.optString("id"),
                name = json.optString("name").ifBlank { "Orchard device" },
                platform = json.optString("platform"),
                kind = json.optString("kind"),
                compatible = json.optBoolean("compatible", json.optInt("connect_protocol_major") == 2),
                nowPlayingTitle = playingNow?.optString("title").orEmpty(),
                nowPlayingArtist = playingNow?.optString("artist").orEmpty(),
                playing = playingNow?.optBoolean("playing") ?: false,
            )
        }
    }
}

/** One Connect relationship. `role` is this phone's: controller or target. */
data class ConnectSession(
    val id: String,
    val role: String,
    val state: String,
    val peer: ConnectDevice,
    val transport: String = "",
) {
    val isController: Boolean get() = role == "controller"
    val connected: Boolean get() = state == "CONNECTED"
    /** Still between the hub's grant and a live, authenticated link. */
    val establishing: Boolean get() = state !in setOf("CONNECTED", "RECONNECTING", "DISCONNECTED")
}

/** Who does what, as the target decided. Ids are account device ids. */
data class ConnectRoles(
    val target: String = "",
    val mixHost: String = "",
    val artworkHost: String = "",
    val providerHosts: Map<String, String> = emptyMap(),
) {
    companion object {
        fun from(json: JSONObject): ConnectRoles {
            val hosts = json.optJSONObject("provider_hosts")
            return ConnectRoles(
                target = json.optString("target"),
                mixHost = json.optString("mix_host"),
                artworkHost = json.optString("artwork_host"),
                providerHosts = hosts?.keys()?.asSequence()?.associateWith(hosts::optString).orEmpty(),
            )
        }
    }
}

data class ConnectState(
    /** Signed in to an Orchard account; Connect needs it for discovery and keys. */
    val available: Boolean = false,
    val online: Boolean = false,
    val devices: List<ConnectDevice> = emptyList(),
    val sessions: List<ConnectSession> = emptyList(),
    /** Asked the hub for a session with this device; no grant yet. */
    val pending: ConnectDevice? = null,
    val message: String = "",
) {
    val controllerSession: ConnectSession? get() = sessions.firstOrNull { it.isController }
    val controllerNames: List<String>
        get() = sessions.filter { !it.isController && it.connected }.map { it.peer.name }
}
