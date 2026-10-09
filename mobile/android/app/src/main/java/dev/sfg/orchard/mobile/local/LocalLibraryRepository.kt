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
import android.net.Uri
import dev.sfg.orchard.mobile.model.BrowseDetail
import dev.sfg.orchard.mobile.model.CatalogKind
import dev.sfg.orchard.mobile.model.LyricLine
import dev.sfg.orchard.mobile.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Songs and playlists that live on this phone instead of YouTube Music. The files stay where the
 * user keeps them; Orchard remembers tags, covers, lyrics and the order of each playlist.
 */
class LocalLibraryRepository(
    context: Context,
    private val scope: CoroutineScope,
    /** Shows a short message; used for import results and problems. */
    private val notify: (String) -> Unit,
    root: File = File(context.filesDir, "local"),
) {
    private val store = LocalStore(root)
    private val importer = LocalImporter(context, store)
    private val mutableLibrary = MutableStateFlow(store.load())
    val library: StateFlow<LocalSnapshot> = mutableLibrary.asStateFlow()
    private val jobs = AtomicInteger()
    private val mutableBusy = MutableStateFlow(false)
    /** True while files are being read, so the UI can show progress instead of looking frozen. */
    val busy: StateFlow<Boolean> = mutableBusy.asStateFlow()
    private val saves = Channel<LocalSnapshot>(Channel.CONFLATED)
    private val editLock = Any()

    init {
        // One writer, so rapid edits cannot finish out of order and leave an older file on disk.
        scope.launch(Dispatchers.IO) { for (snapshot in saves) runCatching { store.save(snapshot) } }
    }

    private inline fun background(crossinline work: suspend () -> Unit) {
        scope.launch(Dispatchers.IO) {
            if (jobs.getAndIncrement() == 0) mutableBusy.value = true
            try {
                work()
            } finally {
                if (jobs.decrementAndGet() == 0) mutableBusy.value = false
            }
        }
    }

    /** Applies an edit, rebuilds the collages it affects and queues a save. One at a time, so no edit is lost. */
    private fun edit(transform: (LocalSnapshot) -> LocalSnapshot) {
        val next = synchronized(editLock) {
            val edited = transform(mutableLibrary.value)
            val withCollages = edited.copy(playlists = edited.playlists.map { refreshCollage(it, edited) })
            mutableLibrary.value = withCollages
            withCollages
        }
        saves.trySend(next)
    }

    // ---- Songs ---------------------------------------------------------------------------

    /** Adds files, and to [playlistId] as well when given. Folders go through [importTree]. */
    fun importUris(uris: List<Uri>, playlistId: String? = null) {
        if (uris.isEmpty()) return
        background {
            val result = importer.importUris(uris, mutableLibrary.value.songsById.keys)
            commitImport(result, playlistId)
        }
    }

    fun importTree(tree: Uri, playlistId: String? = null) {
        background {
            val uris = importer.listTree(tree)
            if (uris.isEmpty()) {
                notify("No audio files found in that folder.")
                return@background
            }
            commitImport(importer.importUris(uris, mutableLibrary.value.songsById.keys), playlistId)
        }
    }

    private fun commitImport(result: LocalImporter.Result, playlistId: String?) {
        edit { snapshot ->
            // Newest first, but a folder keeps its own order within the batch.
            val songs = result.songs.filter { it.id !in snapshot.songsById } + snapshot.songs
            val playlists = snapshot.playlists.map { playlist ->
                if (playlist.id != playlistId) playlist
                else playlist.copy(trackIds = playlist.trackIds + result.ids.filter { it !in playlist.trackIds })
            }
            snapshot.copy(songs = songs, playlists = playlists)
        }
        notify(
            when {
                result.ids.isEmpty() -> "No audio files found."
                playlistId != null -> "Added ${result.ids.size} song(s) to the playlist."
                else -> "Added ${result.songs.size} song(s) to your library."
            },
        )
    }

    /** Takes a song out of the library and every playlist. The file on the phone is untouched. */
    fun removeSong(songId: String) {
        val song = mutableLibrary.value.songsById[songId] ?: return
        if (song.customCover) deleteFiles(song.coverPath, song.animatedPath)
        deleteFiles(song.lyricsPath)
        edit { snapshot ->
            snapshot.copy(
                songs = snapshot.songs.filterNot { it.id == songId },
                playlists = snapshot.playlists.map { it.copy(trackIds = it.trackIds - songId) },
            )
        }
    }

    // ---- Playlists -----------------------------------------------------------------------

    /** Creates a playlist and returns its id, so callers can open it. */
    fun createPlaylist(title: String, songIds: List<String> = emptyList()): String {
        val id = LOCAL_PLAYLIST_PREFIX + UUID.randomUUID()
        val playlist = LocalPlaylist(
            id = id,
            title = title.trim().ifBlank { "New playlist" },
            trackIds = songIds.filter { it in mutableLibrary.value.songsById },
            createdAt = System.currentTimeMillis(),
        )
        edit { it.copy(playlists = it.playlists + playlist) }
        return id
    }

    fun renamePlaylist(id: String, title: String) {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) return
        updatePlaylist(id) { it.copy(title = trimmed) }
    }

    fun deletePlaylist(id: String) {
        val playlist = mutableLibrary.value.playlists.firstOrNull { it.id == id } ?: return
        deleteFiles(playlist.coverPath, playlist.animatedPath, playlist.collagePath)
        edit { it.copy(playlists = it.playlists.filterNot { candidate -> candidate.id == id }) }
    }

    fun addToPlaylist(playlistId: String, songId: String) {
        if (songId !in mutableLibrary.value.songsById) return
        updatePlaylist(playlistId) { if (songId in it.trackIds) it else it.copy(trackIds = it.trackIds + songId) }
    }

    fun removeFromPlaylist(playlistId: String, songId: String) =
        updatePlaylist(playlistId) { it.copy(trackIds = it.trackIds - songId) }

    /** Moves a song so it ends up at [to], which is what a finished reorder knows. */
    fun moveInPlaylist(playlistId: String, from: Int, to: Int) = updatePlaylist(playlistId) { playlist ->
        playlist.trackIds.movedTo(from, to)?.let { playlist.copy(trackIds = it) } ?: playlist
    }

    private fun updatePlaylist(id: String, transform: (LocalPlaylist) -> LocalPlaylist) =
        edit { snapshot -> snapshot.copy(playlists = snapshot.playlists.map { if (it.id == id) transform(it) else it }) }

    // ---- Covers and lyrics ---------------------------------------------------------------

    fun setPlaylistCover(id: String, uri: Uri) = background {
        val picture = importer.ingestPicture(uri, "pl-${id.removePrefix(LOCAL_PLAYLIST_PREFIX)}")
        if (picture == null) {
            notify("Pick a PNG, JPEG, WebP, GIF or MP4 file.")
            return@background
        }
        mutableLibrary.value.playlists.firstOrNull { it.id == id }?.let { deleteFiles(it.coverPath, it.animatedPath) }
        updatePlaylist(id) { it.copy(coverPath = picture.still, animatedPath = picture.animated) }
    }

    fun clearPlaylistCover(id: String) {
        mutableLibrary.value.playlists.firstOrNull { it.id == id }?.let { deleteFiles(it.coverPath, it.animatedPath) }
        updatePlaylist(id) { it.copy(coverPath = "", animatedPath = "") }
    }

    fun setSongCover(songId: String, uri: Uri) = background {
        val picture = importer.ingestPicture(uri, "tr-${songId.removePrefix(LOCAL_TRACK_PREFIX)}")
        if (picture == null) {
            notify("Pick a PNG, JPEG, WebP, GIF or MP4 file.")
            return@background
        }
        mutableLibrary.value.songsById[songId]?.takeIf { it.customCover }?.let { deleteFiles(it.coverPath, it.animatedPath) }
        updateSong(songId) { it.copy(coverPath = picture.still, animatedPath = picture.animated, customCover = true) }
    }

    /** Back to whatever the file's own tags carried, read again from the file. */
    fun clearSongCover(songId: String) = background {
        val song = mutableLibrary.value.songsById[songId] ?: return@background
        if (!song.customCover) return@background
        deleteFiles(song.coverPath, song.animatedPath)
        val embedded = importer.importUris(listOf(Uri.parse(song.uri)), emptySet()).songs.firstOrNull()?.coverPath.orEmpty()
        updateSong(songId) { it.copy(coverPath = embedded, animatedPath = "", customCover = false) }
    }

    fun setSongLyrics(songId: String, uri: Uri) = background {
        val path = importer.ingestLyrics(uri, songId.removePrefix(LOCAL_TRACK_PREFIX))
        if (path == null) {
            notify("That file does not look like lyrics. Try an .lrc, .srt or .txt file.")
            return@background
        }
        updateSong(songId) { it.copy(lyricsPath = path) }
        notify("Lyrics saved.")
    }

    fun clearSongLyrics(songId: String) {
        mutableLibrary.value.songsById[songId]?.let { deleteFiles(it.lyricsPath) }
        updateSong(songId) { it.copy(lyricsPath = "") }
    }

    private fun updateSong(id: String, transform: (LocalSong) -> LocalSong) =
        edit { snapshot -> snapshot.copy(songs = snapshot.songs.map { if (it.id == id) transform(it) else it }) }

    /** The user's lyrics for a playing local song; empty means "none", never "go and look online". */
    fun lyricsFor(track: Track): List<LyricLine> {
        val song = mutableLibrary.value.songsById[track.id] ?: return emptyList()
        val text = song.lyricsPath.takeIf(String::isNotBlank)?.let(importer::readLyrics)
            ?: song.embeddedLyrics.takeIf(String::isNotBlank)
            ?: return emptyList()
        return LocalLyricsParser.parse(text)
    }

    // ---- Presentation --------------------------------------------------------------------

    /** The playlist as the detail screen shows it, kept in step with every edit. */
    fun detailFlow(id: String): Flow<BrowseDetail?> =
        library.map { snapshot -> snapshot.playlists.firstOrNull { it.id == id }?.let { detail(it, snapshot) } }
            .distinctUntilChanged()

    fun animatedCoverOf(id: String): String =
        formatLocalFileUri(library.value.playlists.firstOrNull { it.id == id }?.animatedPath.orEmpty())

    fun hasCustomCover(id: String): Boolean =
        library.value.playlists.firstOrNull { it.id == id }?.coverPath?.isNotBlank() == true

    private fun detail(playlist: LocalPlaylist, snapshot: LocalSnapshot) = BrowseDetail(
        id = playlist.id,
        kind = CatalogKind.PLAYLIST,
        title = playlist.title,
        subtitle = "Local playlist",
        description = playlist.description,
        artworkUrl = formatLocalFileUri(playlist.artworkPath),
        tracks = playlist.trackIds.mapNotNull { snapshot.songsById[it]?.toTrack() },
        artist = "You",
        editable = true,
    )

    // ---- Collage and files ---------------------------------------------------------------

    /** The collage follows a playlist's first four distinct covers; a cover the user picked always wins. */
    private fun refreshCollage(playlist: LocalPlaylist, snapshot: LocalSnapshot): LocalPlaylist {
        if (playlist.coverPath.isNotBlank()) {
            if (playlist.collagePath.isBlank()) return playlist
            deleteFiles(playlist.collagePath)
            return playlist.copy(collagePath = "")
        }
        val sources = LocalCollage.sources(playlist.trackIds.mapNotNull { snapshot.songsById[it]?.coverPath })
        if (sources.isEmpty()) {
            deleteFiles(playlist.collagePath)
            return if (playlist.collagePath.isBlank()) playlist else playlist.copy(collagePath = "")
        }
        val target = File(store.collagesDir, "${playlist.id.removePrefix(LOCAL_PLAYLIST_PREFIX)}-${LocalCollage.key(sources)}.jpg")
        if (playlist.collagePath == target.absolutePath && target.isFile) return playlist
        if (!LocalCollage.write(sources, target)) return playlist
        if (playlist.collagePath.isNotBlank() && playlist.collagePath != target.absolutePath) deleteFiles(playlist.collagePath)
        return playlist.copy(collagePath = target.absolutePath)
    }

    /** Only files inside the store are ours to delete. Never the user's music folder; that is how friendships end. */
    private fun deleteFiles(vararg paths: String) {
        paths.filter { it.isNotBlank() && it.startsWith(store.root.absolutePath) }.forEach { runCatching { File(it).delete() } }
    }
}
