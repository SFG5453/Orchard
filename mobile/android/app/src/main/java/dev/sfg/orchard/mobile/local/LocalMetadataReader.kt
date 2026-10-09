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

package dev.sfg.orchard.mobile.local

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns

/** What a song file says about itself. Every field may be blank: tags are a suggestion. */
data class LocalMetadata(
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    val bitrateKbps: Int = 0,
    val mimeType: String = "",
    val cover: ByteArray? = null,
)

/** Reads tags, duration, bitrate and embedded art with the platform's own retriever. */
class LocalMetadataReader(private val context: Context) {
    /** The file name Android shows for a document, which stands in for missing tags. */
    fun displayName(uri: Uri): String =
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    fun read(uri: Uri): LocalMetadata {
        val fallback = metadataFromFileName(displayName(uri))
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).clean()
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).clean()
                .ifBlank { retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).clean() }
            LocalMetadata(
                // A blank tag must not beat a perfectly good file name.
                title = title.ifBlank { fallback.title },
                artist = artist.ifBlank { fallback.artist },
                album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).clean(),
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L,
                bitrateKbps = ((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L) / 1000).toInt(),
                mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE).clean(),
                cover = retriever.embeddedPicture,
            )
        } catch (_: Exception) {
            // Unreadable tags still leave a playable file with a sensible name: the last honest metadata.
            fallback
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun String?.clean(): String = this?.trim().orEmpty()

    companion object {
        /** "01 - Artist - Title.mp3" becomes artist and title; anything else is just a title. */
        fun metadataFromFileName(name: String): LocalMetadata {
            var base = name.substringBeforeLast('.', name).replace('_', ' ')
            base = base.replace(Regex("^\\s*\\d{1,3}\\s*[-.)]\\s+"), "")
            val dash = base.indexOf(" - ")
            return if (dash > 0) {
                LocalMetadata(title = base.substring(dash + 3).trim(), artist = base.substring(0, dash).trim())
            } else {
                LocalMetadata(title = base.trim().ifBlank { name })
            }
        }
    }
}
