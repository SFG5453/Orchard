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

import android.content.Context
import android.os.Build
import dev.sfg.orchard.mobile.OrchardGraph
import dev.sfg.orchard.mobile.audio.isTabletForm
import dev.sfg.orchard.mobile.auth.AuthState
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

/** This phone's Connect capabilities and LAN addresses, in the core's DeviceInfo shape. */
internal object ConnectDeviceInfo {
    fun build(context: Context, graph: OrchardGraph, deviceId: String): JSONObject = JSONObject()
        .put("id", deviceId)
        .put("name", graph.settings.settings.value.customDeviceName.ifBlank { Build.MODEL.ifBlank { "Android" } })
        .put("platform", "android")
        .put("kind", if (context.isTabletForm()) "tablet" else "mobile")
        .put("can_render_audio", true)
        // The phone can mix and fetch artwork for itself; a desktop still wins both roles.
        .put("can_mix_audio", true)
        .put("can_fetch_artwork", true)
        .put("providers", JSONArray(listOf("youtube", "qobuz")))
        .put(
            "provider_sessions",
            JSONObject()
                .put("youtube", graph.auth.state.value is AuthState.SignedIn)
                .put("qobuz", graph.qobuzResolver.hasSession()),
        )
        .put("audio_transports", JSONArray(listOf("pcm_s16", "pcm_f32", "source")))

    /** Up, non-loopback IPv4 addresses; the core ranks them and drops virtual adapters. */
    fun interfaces(): JSONArray {
        val list = JSONArray()
        val all = runCatching { NetworkInterface.getNetworkInterfaces()?.toList() }.getOrNull().orEmpty()
        for (item in all) {
            if (!runCatching { item.isUp && !item.isLoopback }.getOrDefault(false)) continue
            for (address in item.inetAddresses.toList()) {
                if (address is Inet4Address) {
                    list.put(JSONObject().put("name", item.name).put("address", address.hostAddress))
                }
            }
        }
        return list
    }
}
