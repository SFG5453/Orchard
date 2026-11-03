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

package dev.sfg.orchard.mobile.catalog

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import dev.sfg.orchard.mobile.model.BrowseDetail
import dev.sfg.orchard.mobile.model.CatalogJson
import dev.sfg.orchard.mobile.model.CatalogKind
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** The last complete copy of each opened playlist, shown while a fresh copy loads. */
class PlaylistPageCache(context: Context) {
    private val directory = File(context.cacheDir, "playlists")

    private fun file(key: String): AtomicFile {
        val name = MessageDigest.getInstance("SHA-1").digest(key.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return AtomicFile(File(directory, "$name.json"))
    }

    /** [key] names the account and the playlist, so private playlists stay with their account. */
    fun read(key: String): BrowseDetail? {
        val file = file(key)
        if (!file.baseFile.exists()) return null
        return try {
            val root = JSONObject(file.readFully().decodeToString())
            if (root.optInt("version") != VERSION || root.optString("key") != key) return null
            BrowseDetail(
                id = root.optString("id"),
                kind = CatalogKind.PLAYLIST,
                title = root.optString("title"),
                audioPlaylistId = root.optString("audioPlaylistId"),
                subtitle = root.optString("subtitle"),
                description = root.optString("description"),
                artworkUrl = root.optString("artworkUrl"),
                tracks = CatalogJson.tracks(root.optJSONArray("tracks")),
                artist = root.optString("artist"),
                year = root.optString("year"),
                explicit = root.optBoolean("explicit"),
                editable = root.optBoolean("editable"),
                source = root.optString("source"),
            ).takeIf { it.tracks.isNotEmpty() }
        } catch (error: Exception) {
            Log.w(TAG, "Ignoring unreadable playlist cache", error)
            null
        }
    }

    fun write(key: String, detail: BrowseDetail) {
        if (detail.tracks.isEmpty() || !(directory.isDirectory || directory.mkdirs())) return
        val root = JSONObject()
            .put("version", VERSION)
            .put("key", key)
            .put("id", detail.id)
            .put("title", detail.title)
            .put("audioPlaylistId", detail.audioPlaylistId)
            .put("subtitle", detail.subtitle)
            .put("description", detail.description)
            .put("artworkUrl", detail.artworkUrl)
            .put("tracks", CatalogJson.tracks(detail.tracks))
            .put("artist", detail.artist)
            .put("year", detail.year)
            .put("explicit", detail.explicit)
            .put("editable", detail.editable)
            .put("source", detail.source)
        val file = file(key)
        val stream = file.startWrite()
        try {
            stream.write(root.toString().toByteArray())
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            Log.w(TAG, "Could not cache playlist", error)
            return
        }
        directory.listFiles { candidate -> candidate.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?.drop(MAX_PLAYLISTS)
            ?.forEach { it.delete() }
    }

    private companion object {
        const val TAG = "PlaylistPageCache"
        // Bump when the stored shape changes.
        const val VERSION = 1
        const val MAX_PLAYLISTS = 40
    }
}
