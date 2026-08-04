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

package dev.sfg.orchard.connect.transport

import org.json.JSONObject

/**
 * Platform-neutral transport seam for Orchard Connect.
 *
 * Implementations own network callbacks and may invoke [Listener] on arbitrary
 * threads. [dev.sfg.orchard.connect.client.OrchardConnectClient] serializes
 * those callbacks onto its configured executor before exposing them to UI.
 */
interface ConnectTransport {
    interface Listener {
        fun onOpened()
        fun onClosed(reason: String)
        fun onFailure(error: Throwable)
        fun onEvent(name: String, payload: Any?)
    }

    fun connect()
    fun emit(event: String, payload: Any? = JSONObject.NULL, ack: ((Result<JSONObject>) -> Unit)? = null)
    fun close()

    fun interface Factory {
        fun create(serverUrl: String, listener: Listener): ConnectTransport
    }
}
