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

/** Connect error and end reasons in words, matching the desktop's wording. */
internal object ConnectMessages {
    fun describe(code: String, deviceName: String): String {
        val who = deviceName.ifBlank { "The other device" }
        return when (code) {
            "incompatible_protocol", "incompatible_client" ->
                "$who runs a different Orchard version. Update both devices to use Connect."
            "peer_offline" -> "$who is offline."
            "busy" -> "$who is already controlling another device."
            "hub_offline" -> "Orchard Connect is offline. Check your connection."
            "target_lost", "transport_failed", "timeout" -> "Lost the connection to $who."
            "controller_lost" -> "$who stopped controlling this device."
            "unauthorized" -> "$who could not be verified. Sign in to Orchard on both devices."
            "shutdown", "peer_left" -> "$who left Orchard Connect."
            "provider_unavailable" -> "No connected device is signed in to that service."
            "not_connected" -> "$who is not connected."
            "" -> ""
            else -> "Orchard Connect: $code"
        }
    }
}
