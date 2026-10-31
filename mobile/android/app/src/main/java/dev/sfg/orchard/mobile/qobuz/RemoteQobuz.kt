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

import org.json.JSONObject

/**
 * A Qobuz session on another Orchard Connect device (its Provider Host). The token never leaves
 * that device; this phone sees match results, opaque playback ids and decrypted FLAC bytes.
 */
interface RemoteQobuz {
    fun available(): Boolean
    /** The provider's `playback.resolve` result ({source, match} or {miss}), or null on failure. */
    suspend fun resolve(track: JSONObject): JSONObject?
    /** Inclusive byte range; empty when the host is gone. */
    suspend fun read(playbackId: String, start: Long, end: Long): ByteArray
    fun report(playbackId: String, started: Boolean, position: Double)
}
