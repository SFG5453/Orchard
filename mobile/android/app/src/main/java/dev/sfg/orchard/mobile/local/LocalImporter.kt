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
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.security.MessageDigest

/** A user's picture copied into app storage: a still image plus a loop when it moves. */
data class LocalPicture(val still: String, val animated: String = "")

/**
 * The blocking half of the library: reading song files, walking folders and copying the user's
 * pictures and lyrics. Call from a background dispatcher; it never touches library state.
 */
class LocalImporter(private val context: Context, private val store: LocalStore) {
    private val reader = LocalMetadataReader(context)

    /** Songs probed from [uris]; [LocalSong]s already known are not read again. */
    class Result(val songs: List<LocalSong>, val ids: List<String>, val rejected: Int)

    fun importUris(uris: List<Uri>, known: Set<String>): Result {
        val songs = mutableListOf<LocalSong>()
        val ids = mutableListOf<String>()
        var rejected = 0
        for (uri in uris) {
            val id = localTrackId(uri.toString())
            if (id in ids) continue
            val name = reader.displayName(uri)
            if (id in known) {
                ids += id
                continue
            }
            persist(uri)
            val metadata = reader.read(uri)
            if (!isAudio(metadata.mimeType, name)) {
                rejected++
                continue
            }
            ids += id
            songs += LocalSong(
                id = id,
                uri = uri.toString(),
                title = metadata.title,
                artist = metadata.artist,
                album = metadata.album,
                durationMs = metadata.durationMs,
                bitrateKbps = metadata.bitrateKbps.takeIf { it > 0 } ?: estimateBitrate(uri, metadata.durationMs),
                mimeType = metadata.mimeType,
                coverPath = metadata.cover?.let(::saveEmbeddedCover).orEmpty(),
                addedAt = System.currentTimeMillis(),
            )
        }
        return Result(songs, ids, rejected)
    }

    /** When the tags carry no bitrate, file size over duration is the honest average. */
    private fun estimateBitrate(uri: Uri, durationMs: Long): Int {
        if (durationMs <= 0) return 0
        val bytes = runCatching { context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } }.getOrNull() ?: return 0
        return if (bytes > 0) (bytes * 8 / durationMs).toInt() else 0
    }

    /** Every audio document under a picked folder, sorted so an album imports in track order. */
    fun listTree(tree: Uri): List<Uri> {
        persist(tree)
        val found = mutableListOf<Uri>()
        fun walk(documentId: String) {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, documentId)
            val rows = mutableListOf<Triple<String, String, String>>()
            context.contentResolver.query(
                children,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                ),
                null, null, null,
            )?.use { cursor ->
                while (cursor.moveToNext()) rows += Triple(cursor.getString(0), cursor.getString(1).orEmpty(), cursor.getString(2).orEmpty())
            }
            rows.sortedBy { it.third.lowercase() }.forEach { (id, mime, name) ->
                when {
                    mime == DocumentsContract.Document.MIME_TYPE_DIR -> walk(id)
                    isAudio(mime, name) -> found += DocumentsContract.buildDocumentUriUsingTree(tree, id)
                }
            }
        }
        walk(DocumentsContract.getTreeDocumentId(tree))
        return found
    }

    /** Copies a picture, GIF or video into the store. [base] names the files; a timestamp keeps caches honest. */
    fun ingestPicture(uri: Uri, base: String): LocalPicture? {
        val mime = context.contentResolver.getType(uri).orEmpty()
        val extension = reader.displayName(uri).substringAfterLast('.', "").lowercase()
        val stamp = System.currentTimeMillis()
        store.coversDir.mkdirs()
        return when {
            mime == "image/gif" || extension == "gif" -> {
                val animated = copy(uri, File(store.animatedDir, "$base-$stamp.gif")) ?: return null
                val frame = context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream)
                val still = frame?.let { saveJpeg(it, File(store.coversDir, "$base-$stamp.jpg")) } ?: return LocalPicture(animated, animated)
                LocalPicture(still, animated)
            }
            mime.startsWith("video/") || extension in VIDEO_EXTENSIONS -> {
                val animated = copy(uri, File(store.animatedDir, "$base-$stamp.${extension.ifBlank { "mp4" }}")) ?: return null
                val still = videoFrame(Uri.fromFile(File(animated)))
                    ?.let { saveJpeg(it, File(store.coversDir, "$base-$stamp.jpg")) } ?: return null
                LocalPicture(still, animated)
            }
            mime.startsWith("image/") || extension in IMAGE_EXTENSIONS -> {
                val still = copy(uri, File(store.coversDir, "$base-$stamp.${extension.ifBlank { "jpg" }}")) ?: return null
                LocalPicture(still)
            }
            else -> null
        }
    }

    /** Copies a lyrics file into the store after checking that it really parses as lyrics. */
    fun ingestLyrics(uri: Uri, base: String): String? {
        val bytes = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().take(MAX_LYRICS_BYTES).toByteArray() }
        }.getOrNull() ?: return null
        if (LocalLyricsParser.parse(decode(bytes)).isEmpty()) return null
        val extension = reader.displayName(uri).substringAfterLast('.', "txt").lowercase()
        val target = File(store.lyricsDir, "$base.$extension")
        store.lyricsDir.mkdirs()
        target.writeBytes(bytes)
        return target.absolutePath
    }

    fun readLyrics(path: String): String? = runCatching { decode(File(path).readBytes()) }.getOrNull()

    private fun decode(bytes: ByteArray): String {
        val utf8 = Charsets.UTF_8.newDecoder()
        return runCatching { utf8.decode(java.nio.ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { String(bytes, Charsets.ISO_8859_1) }
    }

    /** Embedded art is named after its pixels, so an album's worth of identical covers share one file. */
    private fun saveEmbeddedCover(bytes: ByteArray): String? {
        val digest = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
        val target = File(store.coversDir, "$digest.jpg")
        if (!target.exists()) {
            store.coversDir.mkdirs()
            runCatching { target.writeBytes(bytes) }.onFailure { return null }
        }
        return target.absolutePath
    }

    private fun videoFrame(uri: Uri): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(0)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun saveJpeg(bitmap: Bitmap, target: File): String? {
        target.parentFile?.mkdirs()
        val saved = runCatching { target.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) } }.getOrDefault(false)
        return if (saved) target.absolutePath else null
    }

    private fun copy(uri: Uri, target: File): String? = runCatching {
        target.parentFile?.mkdirs()
        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: return null
        target.absolutePath
    }.getOrNull()

    /** Keeps read access across restarts. Not every provider offers it; those still work this session. */
    private fun persist(uri: Uri) {
        runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    private companion object {
        const val MAX_LYRICS_BYTES = 2 * 1024 * 1024
        val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "oga", "opus", "wav", "wma", "aif", "aiff", "mka", "alac")
        val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "webm", "mkv")
        val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "bmp")

        fun isAudio(mime: String, name: String): Boolean =
            mime.startsWith("audio/") || mime == "application/ogg" ||
                name.substringAfterLast('.', "").lowercase() in AUDIO_EXTENSIONS
    }
}
