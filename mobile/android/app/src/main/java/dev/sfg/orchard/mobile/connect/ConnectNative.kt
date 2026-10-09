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

/**
 * The shared Orchard Connect core (`core/native/connect`), the same C++ desktop runs.
 *
 * Listener callbacks arrive on the Connect thread, never the main thread. Every handle must be
 * passed to [destroy] exactly once; that joins the Connect thread.
 */
internal object ConnectNative {
    interface Listener {
        fun onHubSend(text: String)
        fun onEvent(json: String)
        fun onData(sessionId: String, headerJson: String, payload: ByteArray)
    }

    init {
        System.loadLibrary("orchard_connect_jni")
    }

    @JvmStatic external fun create(listener: Listener): Long
    @JvmStatic external fun destroy(handle: Long)
    @JvmStatic external fun hubOpened(handle: Long)
    @JvmStatic external fun hubMessage(handle: Long, text: String)
    @JvmStatic external fun hubClosed(handle: Long)
    @JvmStatic external fun setDevice(handle: Long, json: String)
    @JvmStatic external fun setInterfaces(handle: Long, json: String)
    @JvmStatic external fun publishPlayback(handle: Long, json: String)
    @JvmStatic external fun connectTo(handle: Long, deviceId: String)
    @JvmStatic external fun disconnect(handle: Long, sessionId: String)
    @JvmStatic external fun command(handle: Long, json: String): String
    @JvmStatic external fun request(handle: Long, json: String): String
    @JvmStatic external fun respond(handle: Long, json: String)
    @JvmStatic external fun sendData(handle: Long, sessionId: String, headerJson: String, payload: ByteArray): Boolean
    /** An AudioChunk stream the core chunks and paces; returns its id, or "" when refused. */
    @JvmStatic external fun sendStream(handle: Long, sessionId: String, metaJson: String, payload: ByteArray): String
}
