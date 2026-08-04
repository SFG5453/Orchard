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

package dev.sfg.orchard.mobile.library

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import dev.sfg.orchard.mobile.model.Album
import dev.sfg.orchard.mobile.model.Artist
import dev.sfg.orchard.mobile.model.CatalogJson
import dev.sfg.orchard.mobile.model.LibrarySnapshot
import dev.sfg.orchard.mobile.model.Playlist
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/** Atomic offline cache for user-owned library metadata and listening history. */
class LibraryCache(context: Context) {
    private val file = AtomicFile(File(context.filesDir, "library-cache.json"))

    fun load(): LibrarySnapshot {
        if (!file.baseFile.exists()) return LibrarySnapshot()
        return try {
            val root = JSONObject(file.openRead().bufferedReader().use { it.readText() })
            val countsObj = root.optJSONObject("playCounts") ?: JSONObject()
            val countsMap = buildMap {
                countsObj.keys().forEach { key -> put(key, countsObj.optInt(key, 0)) }
            }
            LibrarySnapshot(
                likedTracks = CatalogJson.tracks(root.optJSONArray("likedTracks")),
                savedAlbums = objects(root.optJSONArray("savedAlbums"), CatalogJson::album),
                savedArtists = objects(root.optJSONArray("savedArtists"), CatalogJson::artist),
                savedPlaylists = objects(root.optJSONArray("savedPlaylists"), CatalogJson::playlist),
                recentlyPlayed = CatalogJson.tracks(root.optJSONArray("recentlyPlayed")),
                playCounts = countsMap,
            )
        } catch (error: Exception) {
            Log.w(TAG, "Ignoring unreadable library cache", error)
            LibrarySnapshot()
        }
    }

    @Synchronized
    fun save(snapshot: LibrarySnapshot) {
        val countsObj = JSONObject()
        snapshot.playCounts.forEach { (id, count) -> countsObj.put(id, count) }
        val root = JSONObject()
            .put("version", 1)
            .put("likedTracks", CatalogJson.tracks(snapshot.likedTracks))
            .put("savedAlbums", array(snapshot.savedAlbums, CatalogJson::album))
            .put("savedArtists", array(snapshot.savedArtists, CatalogJson::artist))
            .put("savedPlaylists", array(snapshot.savedPlaylists, CatalogJson::playlist))
            .put("recentlyPlayed", CatalogJson.tracks(snapshot.recentlyPlayed))
            .put("playCounts", countsObj)
        var output: FileOutputStream? = file.startWrite()
        try {
            output?.write(root.toString().toByteArray(Charsets.UTF_8))
            file.finishWrite(output)
            output = null
        } catch (error: Exception) {
            output?.let(file::failWrite)
            Log.e(TAG, "Could not save library cache", error)
        }
    }

    private fun <T> array(values: List<T>, encode: (T) -> JSONObject): JSONArray =
        JSONArray().also { output -> values.forEach { output.put(encode(it)) } }

    private fun <T> objects(values: JSONArray?, decode: (JSONObject) -> T): List<T> = buildList {
        if (values == null) return@buildList
        for (index in 0 until values.length()) values.optJSONObject(index)?.let { add(decode(it)) }
    }

    private companion object {
        const val TAG = "LibraryCache"
    }
}
