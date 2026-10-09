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

package dev.sfg.orchard.mobile.youtube

import dev.sfg.orchard.mobile.auth.YouTubeSession
import dev.sfg.orchard.mobile.model.Album
import dev.sfg.orchard.mobile.model.Artist
import dev.sfg.orchard.mobile.model.BrowseDetail
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.CatalogKind
import dev.sfg.orchard.mobile.model.CatalogSection
import dev.sfg.orchard.mobile.model.Playlist
import dev.sfg.orchard.mobile.model.SearchResults
import dev.sfg.orchard.mobile.model.Track
import org.json.JSONArray
import org.json.JSONObject

// Maps the provider's normalized catalog JSON (the shapes desktop QML reads) onto app models.
// Parsing InnerTube itself happens once, in providers/youtube.

/** The session fields the provider signs requests with. */
fun YouTubeSession?.providerJson(): JSONObject = JSONObject().apply {
    if (this@providerJson == null) return@apply
    put("cookie", cookie)
    if (visitorData.isNotBlank()) put("visitorData", visitorData)
    if (dataSyncId.isNotBlank()) put("dataSyncId", dataSyncId)
    if (accountIndex != 0) put("accountIndex", accountIndex)
}

/** org.json turns JSON null into the string "null"; the provider sends plenty of nulls. */
internal fun JSONObject.text(name: String): String =
    if (isNull(name)) "" else optString(name).trim()

internal fun JSONObject.strings(name: String): List<String> {
    val values = optJSONArray(name) ?: return emptyList()
    return (0 until values.length()).mapNotNull { index ->
        values.opt(index).takeUnless { it == null || it == JSONObject.NULL }?.toString()?.trim()?.takeIf(String::isNotEmpty)
    }
}

internal inline fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T?): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index -> optJSONObject(index)?.let(transform) }
}

private val yearPattern = Regex("""\b(19|20)\d{2}\b""")

/** A subtitle such as "Album • SZA • 2022" split into its parts. */
private fun subtitleParts(subtitle: String): List<String> =
    subtitle.split(" • ", "·", "•").map(String::trim).filter(String::isNotEmpty)

private fun creditFrom(subtitle: String): String = subtitleParts(subtitle).firstOrNull { part ->
    !part.matches(yearPattern) && part.lowercase() !in typeLabels && !part.first().isDigit()
}.orEmpty()

private val typeLabels = setOf("album", "single", "ep", "playlist", "song", "video", "artist", "profile")

private fun JSONObject.browseTarget(): Pair<String, String> {
    val payload = optJSONObject("browsePayload")
    val browseId = text("browseId").ifEmpty { payload?.text("browseId").orEmpty() }
    return browseId to payload?.text("params").orEmpty()
}

/** A playable row, or null for albums, artists and playlists. */
fun JSONObject.providerTrack(): Track? {
    val id = text("id")
    if (id.isEmpty() || optBoolean("unplayable")) return null
    val names = strings("artists").ifEmpty { listOfNotNull(text("artist").takeIf(String::isNotEmpty)) }
    val ids = strings("artistBrowseIds")
    val artists = names.mapIndexed { index, name -> Artist(ids.getOrElse(index) { "" }, name) }
    return Track(
        id = id,
        title = text("title").ifEmpty { "Untitled" },
        artist = names.joinToString(", "),
        album = text("album"),
        albumId = text("albumId"),
        artistId = ids.firstOrNull().orEmpty(),
        artworkUrl = text("thumbnail"),
        durationMs = (optDouble("durationSeconds", 0.0).takeIf(Double::isFinite) ?: 0.0).times(1000).toLong(),
        explicit = optBoolean("explicit"),
        musicVideoType = text("musicVideoType"),
        isUpload = optBoolean("isUpload"),
        artists = artists,
    )
}

fun JSONObject.providerItem(): CatalogItem? {
    providerTrack()?.let { return CatalogItem.Song(it) }
    val (browseId, params) = browseTarget()
    if (browseId.isEmpty()) return null
    val title = text("title")
    val subtitle = text("subtitle")
    val art = text("thumbnail")
    val credit = text("artist").ifEmpty { creditFrom(subtitle) }
    val type = text("type")
    return when {
        type == "artist" || type == "library_artist" || browseId.startsWith("UC") ->
            CatalogItem.Performer(Artist(browseId, title, art, subtitle))
        type == "album" || browseId.startsWith("MPRE") ->
            CatalogItem.Record(Album(browseId, title, credit, art,
                yearPattern.find(text("year").ifEmpty { subtitle })?.value.orEmpty(), explicit = optBoolean("explicit")))
        type == "playlist" || browseId.startsWith("VL") || browseId.startsWith("RD") ->
            CatalogItem.Collection(Playlist(browseId, title, credit.ifEmpty { "YouTube Music" }, art,
                explicit = optBoolean("explicit")))
        else -> CatalogItem.Category(browseId, title, params = params)
    }
}

fun JSONObject.providerSection(fallbackId: String): CatalogSection {
    val payload = optJSONObject("browsePayload")
    return CatalogSection(
        id = text("key").ifEmpty { fallbackId },
        title = text("title"),
        items = optJSONArray("items").mapObjects { it.providerItem() },
        browseId = payload?.text("browseId").orEmpty(),
        params = payload?.text("params").orEmpty(),
    )
}

fun JSONArray?.providerSections(prefix: String): List<CatalogSection> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { index ->
        optJSONObject(index)?.providerSection("$prefix-$index")?.takeIf { it.items.isNotEmpty() }
    }
}

fun JSONObject.providerSearch(): SearchResults {
    val sections = optJSONArray("sections").mapObjects { it }
    fun items(key: String) = sections.filter { it.text("key") == key }
        .flatMap { section -> section.optJSONArray("items").mapObjects { it.providerItem() } }
    return SearchResults(
        tracks = items("songs").filterIsInstance<CatalogItem.Song>().map { it.track },
        videos = items("videos").filterIsInstance<CatalogItem.Song>().map { it.track },
        albums = items("albums").filterIsInstance<CatalogItem.Record>().map { it.album },
        artists = items("artists").filterIsInstance<CatalogItem.Performer>().map { it.artist },
        playlists = items("playlists").filterIsInstance<CatalogItem.Collection>().map { it.playlist },
    )
}

/** Album, playlist and artist pages, plus generic browse pages as sections only. */
fun JSONObject.providerDetail(id: String, kind: CatalogKind): BrowseDetail {
    val title = text("title")
    val artwork = text("thumbnail")
    val artist = text("artist").ifEmpty { text("author") }
    val tracks = optJSONArray("tracks").mapObjects { it.providerTrack() }.map { track ->
        if (kind == CatalogKind.ALBUM) track.inAlbum(id, title, artist, artwork)
        else if (track.artworkUrl.isEmpty()) track.copy(artworkUrl = artwork) else track
    }
    return BrowseDetail(
        id = id,
        kind = kind,
        title = title,
        audioPlaylistId = text("audioPlaylistId").ifEmpty { text("playlistId") },
        // The provider fills an album's subtitle with its description; the header wants the release type.
        subtitle = if (kind == CatalogKind.ALBUM) text("releaseType") else text("subtitle"),
        description = text("description"),
        artworkUrl = artwork,
        tracks = tracks,
        sections = optJSONArray("sections").providerSections(id),
        artist = artist,
        year = text("year"),
        explicit = optBoolean("explicit"),
        editable = optBoolean("editable"),
    )
}

/** Album rows inherit the album's identity; its cover beats a per-song video still. */
private fun Track.inAlbum(id: String, title: String, artist: String, cover: String): Track = copy(
    album = album.ifEmpty { title },
    albumId = albumId.ifEmpty { id },
    artist = this.artist.ifEmpty { artist },
    artworkUrl = if (cover.isNotEmpty() && (artworkUrl.isEmpty() || "i.ytimg.com/vi/" in artworkUrl)) cover else artworkUrl,
)
