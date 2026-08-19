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

package dev.sfg.orchard.mobile.model

import org.json.JSONArray
import org.json.JSONObject

/** Stable JSON boundary used by playback restoration and offline library caches. */
object CatalogJson {
    fun track(value: Track): JSONObject = JSONObject()
        .put("id", value.id)
        .put("title", value.title)
        .put("artist", value.artist)
        .put("album", value.album)
        .put("albumId", value.albumId)
        .put("artistId", value.artistId)
        .put("artworkUrl", value.artworkUrl)
        .put("animatedArtworkUrl", value.animatedArtworkUrl)
        .put("animatedArtworkVerticalUrl", value.animatedArtworkVerticalUrl)
        .put("durationMs", value.durationMs)
        .put("explicit", value.explicit)
        .put("autoplayGenerated", value.autoplayGenerated)
        .put("isUpload", value.isUpload)

    fun track(value: JSONObject): Track = Track(
        id = value.cleanString("id"),
        title = value.cleanString("title", "Unknown track"),
        artist = value.cleanString("artist", "Unknown artist"),
        album = value.cleanString("album"),
        albumId = value.cleanString("albumId"),
        artistId = value.cleanString("artistId"),
        artworkUrl = value.cleanString("artworkUrl"),
        animatedArtworkUrl = value.cleanString("animatedArtworkUrl"),
        animatedArtworkVerticalUrl = value.cleanString("animatedArtworkVerticalUrl"),
        durationMs = value.optLong("durationMs"),
        explicit = value.optBoolean("explicit"),
        autoplayGenerated = value.optBoolean("autoplayGenerated"),
        isUpload = value.optBoolean("isUpload"),
    )

    fun tracks(values: List<Track>): JSONArray = JSONArray().also { output ->
        values.forEach { output.put(track(it)) }
    }

    fun tracks(values: JSONArray?): List<Track> = buildList {
        if (values == null) return@buildList
        for (index in 0 until values.length()) {
            val value = values.optJSONObject(index) ?: continue
            val decoded = track(value)
            if (decoded.id.isNotBlank()) add(decoded)
        }
    }

    fun album(value: Album): JSONObject = JSONObject()
        .put("id", value.id)
        .put("title", value.title)
        .put("artist", value.artist)
        .put("artworkUrl", value.artworkUrl)
        .put("year", value.year)
        .put("tracks", tracks(value.tracks))
        .put("explicit", value.explicit)

    fun album(value: JSONObject): Album = Album(
        id = value.cleanString("id"),
        title = value.cleanString("title"),
        artist = value.cleanString("artist"),
        artworkUrl = value.cleanString("artworkUrl"),
        year = value.cleanString("year"),
        tracks = tracks(value.optJSONArray("tracks")),
        explicit = value.optBoolean("explicit"),
    )

    fun artist(value: Artist): JSONObject = JSONObject()
        .put("id", value.id)
        .put("name", value.name)
        .put("artworkUrl", value.artworkUrl)
        .put("subtitle", value.subtitle)

    fun artist(value: JSONObject): Artist = Artist(
        id = value.cleanString("id"),
        name = value.cleanString("name"),
        artworkUrl = value.cleanString("artworkUrl"),
        subtitle = value.cleanString("subtitle"),
    )

    fun playlist(value: Playlist): JSONObject = JSONObject()
        .put("id", value.id)
        .put("title", value.title)
        .put("author", value.author)
        .put("artworkUrl", value.artworkUrl)
        .put("description", value.description)
        .put("tracks", tracks(value.tracks))
        .put("explicit", value.explicit)

    fun playlist(value: JSONObject): Playlist = Playlist(
        id = value.cleanString("id"),
        title = value.cleanString("title"),
        author = value.cleanString("author", "Orchard"),
        artworkUrl = value.cleanString("artworkUrl"),
        description = value.cleanString("description"),
        tracks = tracks(value.optJSONArray("tracks")),
        explicit = value.optBoolean("explicit"),
    )

    private fun JSONObject.cleanString(key: String, default: String = ""): String {
        if (isNull(key)) return default
        val v = optString(key, default).trim()
        return if (v.equals("null", ignoreCase = true)) default else v
    }
}

