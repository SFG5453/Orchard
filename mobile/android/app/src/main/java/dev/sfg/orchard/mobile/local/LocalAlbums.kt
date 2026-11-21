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

import dev.sfg.orchard.mobile.i18n.tr

import dev.sfg.orchard.mobile.model.Album
import dev.sfg.orchard.mobile.model.BrowseDetail
import dev.sfg.orchard.mobile.model.CatalogKind
import dev.sfg.orchard.mobile.model.LOCAL_SOURCE
import java.net.URLDecoder
import java.security.MessageDigest

const val LOCAL_ALBUM_PREFIX = "local-album:"

fun isLocalAlbumId(id: String): Boolean = id.startsWith(LOCAL_ALBUM_PREFIX)

/** Playlists and albums kept on this phone: never looked up, saved or shared online. */
fun isLocalCollectionId(id: String): Boolean = isLocalPlaylistId(id) || isLocalAlbumId(id)

/** An album grouped from tags. Nothing is stored; it is rebuilt from the songs. */
data class LocalAlbum(val id: String, val title: String, val artist: String, val year: Int, val songs: List<LocalSong>) {
    val artworkPath: String get() = songs.firstOrNull { it.coverPath.isNotBlank() }?.coverPath.orEmpty()
}

private fun String.folded(): String = trim().replace(Regex("\\s+"), " ").lowercase()

private val DISC_FOLDER = Regex("^(cd|disc|disk)\\s*\\d+$", RegexOption.IGNORE_CASE)

/**
 * The folder a document lives in, from its decoded document id ("primary:Music/Album/01.flac").
 * Disc subfolders count as their parent. Null when the provider hides folders (media picker ids).
 */
internal fun localSongFolder(uri: String): String? {
    val decoded = runCatching { URLDecoder.decode(uri.replace("+", "%2B"), "UTF-8") }.getOrDefault(uri)
    val path = decoded.substringAfterLast("/document/", "")
    if (!path.contains('/')) return null
    var folder = path.substringBeforeLast('/')
    if (DISC_FOLDER.matches(folder.substringAfterLast('/').trim()) && folder.contains('/')) folder = folder.substringBeforeLast('/')
    return folder
}

/**
 * Album tag plus album artist, or plus the folder when there is no album artist so compilations
 * stay whole. Blank for songs without an album tag.
 */
fun localAlbumId(song: LocalSong): String {
    val album = song.album.folded()
    if (album.isEmpty()) return ""
    val owner = song.albumArtist.folded().ifEmpty { localSongFolder(song.uri) ?: "artist:" + song.artist.folded() }
    val digest = MessageDigest.getInstance("SHA-1").digest("$album\n$owner".toByteArray())
    return LOCAL_ALBUM_PREFIX + digest.joinToString("") { "%02x".format(it) }.take(16)
}

private val ALBUM_ORDER = compareBy<LocalSong>({ it.discNumber }, { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE })
    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.uri }

/** Albums sorted by artist then title, each in disc and track order. */
fun LocalSnapshot.albums(): List<LocalAlbum> =
    songs.groupBy(::localAlbumId).filterKeys { it.isNotEmpty() }.map { (id, members) ->
        val ordered = members.sortedWith(ALBUM_ORDER)
        LocalAlbum(id, ordered.first().album, albumArtist(ordered), ordered.maxOf { it.year }, ordered)
    }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, LocalAlbum::artist).thenBy(String.CASE_INSENSITIVE_ORDER, LocalAlbum::title))

private fun albumArtist(songs: List<LocalSong>): String {
    songs.firstOrNull { it.albumArtist.isNotBlank() }?.let { return it.albumArtist }
    val artists = songs.map { it.artist }.filter { it.isNotBlank() }.distinctBy { it.folded() }
    return if (artists.size > 1) "Various Artists" else artists.firstOrNull().orEmpty()
}

fun LocalAlbum.toAlbum(): Album = Album(
    id = id,
    title = title,
    artist = artist,
    artworkUrl = formatLocalFileUri(artworkPath),
    year = if (year > 0) year.toString() else "",
    tracks = songs.map { it.toTrack() },
)

fun LocalAlbum.toDetail(): BrowseDetail = BrowseDetail(
    id = id,
    kind = CatalogKind.ALBUM,
    title = title,
    subtitle = tr("Album on this phone"),
    artworkUrl = formatLocalFileUri(artworkPath),
    tracks = songs.map { it.toTrack() },
    artist = artist,
    year = if (year > 0) year.toString() else "",
    source = LOCAL_SOURCE,
)
