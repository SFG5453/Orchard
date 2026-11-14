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
import android.provider.DocumentsContract
import android.provider.OpenableColumns

/** What a song file says about itself. Every field may be blank: tags are a suggestion. */
data class LocalMetadata(
    val title: String,
    val artist: String = "",
    val album: String = "",
    val albumArtist: String = "",
    val trackNumber: Int = 0,
    val discNumber: Int = 0,
    val year: Int = 0,
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
                albumArtist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).clean(),
                trackNumber = leadingNumber(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)),
                discNumber = leadingNumber(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER)),
                year = leadingNumber(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR)?.take(4))
                    .takeIf { it > 0 } ?: leadingNumber(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)?.take(4)),
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

    /** The document's last-modified time, or 0 when the provider does not report one. */
    fun lastModified(uri: Uri): Long =
        runCatching {
            context.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else 0L
            }
        }.getOrNull() ?: 0L

    companion object {
        /** "3/12" and "2004" both start with the number that matters. */
        fun leadingNumber(text: String?): Int = text.orEmpty().trim().takeWhile(Char::isDigit).take(9).toIntOrNull() ?: 0

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
