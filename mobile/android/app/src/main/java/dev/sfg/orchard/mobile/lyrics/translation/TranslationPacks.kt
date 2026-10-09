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

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.io.File
import java.io.IOException
import java.security.MessageDigest

data class TranslationPackFile(val name: String, val size: Long, val sha256: String)

/** One downloadable source->English model from the shared table. */
data class TranslationPack(
    val id: String,
    val source: String,
    val name: String,
    val quality: String,
    val baseUrl: String,
    val revision: String,
    val credit: String,
    val files: List<TranslationPackFile>,
) {
    val bytes: Long get() = files.sumOf { it.size }
}

/**
 * Models on disk, one folder per pack id, in the same layout desktop uses: the three files plus a
 * pack-revision marker written last. Kept out of backups; they are 20 to 80 MB and re-downloadable.
 */
class TranslationPacks(context: Context, private val http: OkHttpClient) {
    private val root = File(context.noBackupFilesDir, "translation")

    val table: List<TranslationPack> by lazy {
        if (!TranslationNative.available) return@lazy emptyList()
        val array = JSONArray(TranslationNative.packTable().decodeToString())
        List(array.length()) { index ->
            val pack = array.getJSONObject(index)
            val files = pack.getJSONArray("files")
            TranslationPack(
                id = pack.getString("id"),
                source = pack.getString("source"),
                name = pack.getString("name"),
                quality = pack.getString("quality"),
                baseUrl = pack.getString("baseUrl"),
                revision = pack.getString("revision"),
                credit = pack.getString("credit"),
                files = List(files.length()) {
                    val file = files.getJSONObject(it)
                    TranslationPackFile(file.getString("name"), file.getLong("size"), file.getString("sha256"))
                },
            )
        }
    }

    fun find(id: String): TranslationPack? = table.firstOrNull { it.id == id }

    /** The pack for [quality], falling back to standard where no high pack exists. */
    fun resolve(source: String, quality: String): TranslationPack? =
        table.firstOrNull { it.source == source && it.quality == quality }
            ?: table.firstOrNull { it.source == source && it.quality == "standard" }

    fun directory(id: String): File = File(root, id)

    fun installed(pack: TranslationPack): Boolean {
        val dir = directory(pack.id)
        val marker = File(dir, MARKER)
        return marker.isFile && marker.readText() == pack.revision &&
            pack.files.all { File(dir, it.name).length() == it.size }
    }

    fun installedPacks(): List<TranslationPack> = table.filter(::installed)

    fun installedBytes(): Long = installedPacks().sumOf { it.bytes }

    fun remove(id: String) {
        directory(id).deleteRecursively()
        File(root, "$id$PARTIAL").deleteRecursively()
    }

    /**
     * Downloads and verifies every file, then swaps the folder into place. Cancelling the caller
     * stops between chunks; a half-fetched pack never looks installed because the marker comes last.
     */
    suspend fun download(pack: TranslationPack, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        require(pack.baseUrl.isNotEmpty()) { "${pack.name} has no download" }
        val staging = File(root, "${pack.id}$PARTIAL")
        staging.deleteRecursively()
        if (!staging.mkdirs()) throw IOException("Cannot create $staging")
        val total = pack.bytes.toFloat()
        var done = 0L
        try {
            for (file in pack.files) {
                fetch(pack.baseUrl + file.name, File(staging, file.name), file) { written ->
                    onProgress((done + written) / total)
                }
                done += file.size
            }
            File(staging, MARKER).writeText(pack.revision)
            val target = directory(pack.id)
            target.deleteRecursively()
            if (!staging.renameTo(target)) throw IOException("Cannot install ${pack.id}")
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }
    }

    private suspend fun fetch(url: String, out: File, expected: TranslationPackFile, onWritten: (Long) -> Unit) {
        val digest = MessageDigest.getInstance("SHA-256")
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Download failed (HTTP ${response.code})")
            val body = response.body.byteStream()
            out.outputStream().buffered().use { sink ->
                val buffer = ByteArray(64 * 1024)
                var written = 0L
                var reported = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = body.read(buffer)
                    if (read < 0) break
                    written += read
                    // A server that sends more than the table promises is not sending our model.
                    if (written > expected.size) throw IOException("${expected.name} is larger than expected")
                    digest.update(buffer, 0, read)
                    sink.write(buffer, 0, read)
                    if (written - reported >= 256 * 1024) {
                        reported = written
                        onWritten(written)
                    }
                }
                if (written != expected.size) throw IOException("${expected.name} ended early")
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (hash != expected.sha256) throw IOException("${expected.name} failed verification")
    }

    private companion object {
        const val MARKER = "pack-revision"
        const val PARTIAL = ".partial"
    }
}
