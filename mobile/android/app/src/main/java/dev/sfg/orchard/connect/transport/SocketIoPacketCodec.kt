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

import org.json.JSONArray
import org.json.JSONObject

/** Minimal Engine.IO 4 / Socket.IO packet codec for Orchard's JSON-only events. */
object SocketIoPacketCodec {
    sealed interface Incoming {
        data object EngineOpened : Incoming
        data object Ping : Incoming
        data object NamespaceOpened : Incoming
        data class Event(val name: String, val payload: Any?) : Incoming
        data class Ack(val id: Int, val payload: JSONObject) : Incoming
        data class Closed(val reason: String = "") : Incoming
        data class Ignored(val raw: String) : Incoming
    }

    fun decode(text: String): Incoming = when {
        text == "2" -> Incoming.Ping
        text.startsWith("0") -> Incoming.EngineOpened
        text.startsWith("40") -> Incoming.NamespaceOpened
        text.startsWith("42") -> decodeEvent(text.drop(2))
        text.startsWith("43") -> decodeAck(text.drop(2))
        text.startsWith("41") -> Incoming.Closed(text.drop(2))
        else -> Incoming.Ignored(text)
    }

    fun namespaceOpen(): String = "40"
    fun pong(): String = "3"

    fun event(name: String, payload: Any?, ackId: Int?): String {
        val id = ackId?.toString().orEmpty()
        val array = JSONArray().put(name).put(payload ?: JSONObject.NULL)
        return "42$id$array"
    }

    private fun decodeEvent(raw: String): Incoming {
        val array = JSONArray(raw.dropWhile(Char::isDigit))
        val name = array.optString(0)
        return if (name.isEmpty()) Incoming.Ignored(raw) else Incoming.Event(name, array.opt(1))
    }

    private fun decodeAck(raw: String): Incoming {
        val digits = raw.takeWhile(Char::isDigit)
        val id = digits.toIntOrNull() ?: return Incoming.Ignored(raw)
        val array = JSONArray(raw.drop(digits.length))
        return Incoming.Ack(id, array.optJSONObject(0) ?: JSONObject())
    }
}
