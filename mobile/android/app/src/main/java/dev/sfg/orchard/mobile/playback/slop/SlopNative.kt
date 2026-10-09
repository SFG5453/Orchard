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

package dev.sfg.orchard.mobile.playback.slop

internal object SlopNative {
    val available = runCatching { System.loadLibrary("orchard_slop") }.isSuccess
    external fun create(rate: Int, channels: Int, model: ByteArray): Long
    external fun push(handle: Long, samples: FloatArray, count: Int)
    external fun full(handle: Long): Boolean
    external fun verdict(handle: Long): FloatArray?
    external fun free(handle: Long)
}
