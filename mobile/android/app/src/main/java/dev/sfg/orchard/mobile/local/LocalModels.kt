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

import dev.sfg.orchard.mobile.model.LOCAL_SOURCE
import dev.sfg.orchard.mobile.model.Playlist
import dev.sfg.orchard.mobile.model.Track
import java.security.MessageDigest

/** Id prefixes that keep local songs and playlists apart from anything YouTube hands out. */
const val LOCAL_TRACK_PREFIX = "local:"
const val LOCAL_PLAYLIST_PREFIX = "local-playlist:"

fun isLocalTrackId(id: String): Boolean = id.startsWith(LOCAL_TRACK_PREFIX)
fun isLocalPlaylistId(id: String): Boolean = id.startsWith(LOCAL_PLAYLIST_PREFIX)

/**
 * A song file on this phone. The audio is never copied: [uri] is a persisted document URI, and
 * Orchard keeps only the tags, covers and lyrics it found or was given.
 */
data class LocalSong(
    val id: String,
    val uri: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
    /** Bits per second divided by a thousand, as probed from the file. */
    val bitrateKbps: Int = 0,
    val mimeType: String = "",
    /** A still image: embedded art or the user's pick. */
    val coverPath: String = "",
    /** A looping video or GIF the user chose for this song. */
    val animatedPath: String = "",
    val customCover: Boolean = false,
    /** The user's lyrics file, copied into app storage. */
    val lyricsPath: String = "",
    /** Lyrics text found in the file's own tags. */
    val embeddedLyrics: String = "",
    val addedAt: Long = 0,
)

/** A playlist kept on this phone. A cover the user picked wins over the generated collage. */
data class LocalPlaylist(
    val id: String,
    val title: String,
    val description: String = "",
    val trackIds: List<String> = emptyList(),
    val coverPath: String = "",
    val animatedPath: String = "",
    val collagePath: String = "",
    val createdAt: Long = 0,
) {
    /** What the playlist shows: the user's picture, else the generated collage, else nothing. */
    val artworkPath: String get() = coverPath.ifBlank { collagePath }
}

data class LocalSnapshot(
    /** Newest first, like "recently added". */
    val songs: List<LocalSong> = emptyList(),
    val playlists: List<LocalPlaylist> = emptyList(),
) {
    val songsById: Map<String, LocalSong> by lazy { songs.associateBy { it.id } }
}

/** The list with the item at [from] moved so it ends up at [to]; null when either index is out of range. */
fun <T> List<T>.movedTo(from: Int, to: Int): List<T>? {
    if (from !in indices || to !in indices || from == to) return null
    return toMutableList().also { it.add(to, it.removeAt(from)) }
}

/** Stable across restarts: the same document always gets the same id. */
fun localTrackId(uri: String): String {
    val digest = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray())
    return LOCAL_TRACK_PREFIX + digest.joinToString("") { "%02x".format(it) }.take(16)
}

fun formatLocalFileUri(path: String): String = if (path.isBlank()) "" else "file://$path"

/** The provider-neutral track the rest of the app plays, queues and displays. */
fun LocalSong.toTrack(): Track = Track(
    id = id,
    title = title,
    artist = artist.ifBlank { "Unknown artist" },
    album = album,
    artworkUrl = formatLocalFileUri(coverPath),
    animatedArtworkUrl = formatLocalFileUri(animatedPath),
    durationMs = durationMs,
    playbackSource = LOCAL_SOURCE,
    localUri = uri,
    localBitrateKbps = bitrateKbps,
    codec = mimeType.substringAfter('/', "").removePrefix("x-"),
)

/**
 * A playlist kept on this phone, shaped like a saved one so shared cards and the playlist picker can
 * show it. Pass [snapshot] when the songs themselves are needed, e.g. to grey out duplicates.
 */
fun LocalPlaylist.toPlaylist(snapshot: LocalSnapshot? = null): Playlist {
    val count = trackIds.size
    return Playlist(
        id = id,
        title = title,
        author = if (count == 1) "Local · 1 song" else "Local · $count songs",
        artworkUrl = formatLocalFileUri(artworkPath),
        description = description,
        tracks = snapshot?.let { snap -> trackIds.mapNotNull { snap.songsById[it]?.toTrack() } }.orEmpty(),
    )
}
