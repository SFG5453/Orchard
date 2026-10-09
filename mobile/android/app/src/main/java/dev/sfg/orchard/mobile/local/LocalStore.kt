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

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Songs and playlists as one JSON file, plus the folders holding their pictures and lyrics.
 * Plain data and `org.json` only, so unit tests can run it without Android.
 */
class LocalStore(val root: File) {
    val coversDir: File get() = File(root, "covers")
    val collagesDir: File get() = File(root, "collages")
    val animatedDir: File get() = File(root, "animated")
    val lyricsDir: File get() = File(root, "lyrics")
    private val file: File get() = File(root, "library.json")

    fun load(): LocalSnapshot {
        val text = runCatching { file.readText() }.getOrNull() ?: return LocalSnapshot()
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return LocalSnapshot()
        val songs = mutableListOf<LocalSong>()
        json.optJSONArray("songs")?.let { array ->
            for (index in 0 until array.length()) array.optJSONObject(index)?.let(::song)?.let(songs::add)
        }
        val known = songs.mapTo(HashSet()) { it.id }
        val playlists = mutableListOf<LocalPlaylist>()
        json.optJSONArray("playlists")?.let { array ->
            for (index in 0 until array.length()) {
                // A song removed from the library must not linger as a ghost row.
                array.optJSONObject(index)?.let(::playlist)
                    ?.let { it.copy(trackIds = it.trackIds.filter(known::contains)) }
                    ?.let(playlists::add)
            }
        }
        return LocalSnapshot(songs, playlists)
    }

    /** Writes to a temp file and renames, so a crash mid-save cannot eat the library. Musicians have suffered enough. */
    fun save(snapshot: LocalSnapshot) {
        root.mkdirs()
        val json = JSONObject()
            .put("version", VERSION)
            .put("songs", JSONArray().also { out -> snapshot.songs.forEach { out.put(toJson(it)) } })
            .put("playlists", JSONArray().also { out -> snapshot.playlists.forEach { out.put(toJson(it)) } })
        val temp = File(root, "library.json.tmp")
        temp.writeText(json.toString())
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    private fun toJson(song: LocalSong): JSONObject = JSONObject()
        .put("id", song.id)
        .put("uri", song.uri)
        .put("title", song.title)
        .put("artist", song.artist)
        .put("album", song.album)
        .put("durationMs", song.durationMs)
        .put("bitrateKbps", song.bitrateKbps)
        .put("mimeType", song.mimeType)
        .put("coverPath", song.coverPath)
        .put("animatedPath", song.animatedPath)
        .put("customCover", song.customCover)
        .put("lyricsPath", song.lyricsPath)
        .put("embeddedLyrics", song.embeddedLyrics)
        .put("addedAt", song.addedAt)

    private fun song(json: JSONObject): LocalSong? {
        val id = json.optString("id")
        val uri = json.optString("uri")
        if (id.isBlank() || uri.isBlank()) return null
        return LocalSong(
            id = id,
            uri = uri,
            title = json.optString("title"),
            artist = json.optString("artist"),
            album = json.optString("album"),
            durationMs = json.optLong("durationMs"),
            bitrateKbps = json.optInt("bitrateKbps"),
            mimeType = json.optString("mimeType"),
            coverPath = json.optString("coverPath"),
            animatedPath = json.optString("animatedPath"),
            customCover = json.optBoolean("customCover"),
            lyricsPath = json.optString("lyricsPath"),
            embeddedLyrics = json.optString("embeddedLyrics"),
            addedAt = json.optLong("addedAt"),
        )
    }

    private fun toJson(playlist: LocalPlaylist): JSONObject = JSONObject()
        .put("id", playlist.id)
        .put("title", playlist.title)
        .put("description", playlist.description)
        .put("tracks", JSONArray(playlist.trackIds))
        .put("coverPath", playlist.coverPath)
        .put("animatedPath", playlist.animatedPath)
        .put("collagePath", playlist.collagePath)
        .put("createdAt", playlist.createdAt)

    private fun playlist(json: JSONObject): LocalPlaylist? {
        val id = json.optString("id")
        if (id.isBlank()) return null
        val ids = json.optJSONArray("tracks")?.let { array -> List(array.length()) { array.optString(it) } }.orEmpty()
        return LocalPlaylist(
            id = id,
            title = json.optString("title"),
            description = json.optString("description"),
            trackIds = ids,
            coverPath = json.optString("coverPath"),
            animatedPath = json.optString("animatedPath"),
            collagePath = json.optString("collagePath"),
            createdAt = json.optLong("createdAt"),
        )
    }

    private companion object {
        const val VERSION = 1
    }
}
