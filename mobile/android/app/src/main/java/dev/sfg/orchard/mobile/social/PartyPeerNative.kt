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

package dev.sfg.orchard.mobile.social

/**
 * One WebRTC data channel to a Listening Party peer, backed by the libdatachannel stack in
 * `liborchard_connect_jni` (`cpp/connect/party_jni.cpp`).
 *
 * Listener callbacks arrive on the peer's own native thread. [destroy] joins that thread, so it
 * must not be called from a callback, and every handle needs exactly one call.
 */
internal object PartyPeerNative {
    interface Listener {
        /** A local description or candidate: `{kind: description|candidate, ...}`. */
        fun onSignal(json: String)
        fun onOpen()
        fun onText(text: String)
        fun onClosed(reason: String)
    }

    init {
        System.loadLibrary("orchard_connect_jni")
    }

    /** `iceServers` is the browser-shaped JSON array; the offerer opens the channel. */
    @JvmStatic external fun create(listener: Listener, iceServers: String, offerer: Boolean): Long
    @JvmStatic external fun destroy(handle: Long)
    @JvmStatic external fun start(handle: Long)
    @JvmStatic external fun signal(handle: Long, json: String)
    @JvmStatic external fun sendText(handle: Long, text: String): Boolean
}
