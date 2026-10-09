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

package dev.sfg.orchard.mobile.lyrics.translation

import android.util.Log

/** JNI over core/native/translation, the same code desktop runs. Text crosses as UTF-8 bytes. */
internal object TranslationNative {
    /** False when either library is missing, which hides translation instead of crashing lyrics. */
    val available: Boolean = runCatching {
        // Loaded first so the core's dlopen("libLiteRt.so") finds it already mapped.
        System.loadLibrary("LiteRt")
        System.loadLibrary("orchard_translation")
    }.onFailure { Log.w("LyricTranslation", "Translation runtime unavailable", it) }.isSuccess

    /** JSON array of the shared pack table: id, source, name, quality, baseUrl, revision, credit, files. */
    @JvmStatic external fun packTable(): ByteArray

    /** JSON {source, name, translate[]} for UTF-8 lines; source is empty for English songs. */
    @JvmStatic external fun plan(lines: Array<ByteArray>): ByteArray

    /** Loads a pack folder; throws IllegalStateException with LiteRT's reason on failure. */
    @JvmStatic external fun open(runtime: String, directory: ByteArray, threads: Int): Long

    /** Null when [generation] went stale mid-line or the model failed. One thread per handle. */
    @JvmStatic external fun translate(handle: Long, text: ByteArray, generation: Int): ByteArray?

    /** Any thread: running translate calls for other generations stop at their next token. */
    @JvmStatic external fun setGeneration(generation: Int)

    @JvmStatic external fun close(handle: Long)
}
