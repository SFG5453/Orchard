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

import dev.sfg.orchard.mobile.auth.YouTubeSessionProvider
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.youtube.YouTubeProvider
import dev.sfg.orchard.mobile.youtube.mapObjects
import dev.sfg.orchard.mobile.youtube.providerJson
import dev.sfg.orchard.mobile.youtube.text
import org.json.JSONObject

/** A playlist the listener can add a song to, as the provider's picker reports it. */
data class PlaylistTarget(val id: String, val title: String, val containsTrack: Boolean)

/** Authenticated library writes through the desktop provider's `library.*` methods. */
class PlaylistActions(
    private val provider: YouTubeProvider,
    private val sessions: YouTubeSessionProvider,
) {
    private fun payload() = JSONObject().put("session", sessions.session().providerJson())

    // The provider saves the audio version playback would open, so rows carry their metadata.
    private fun JSONObject.withTrack(track: Track) = put("track", JSONObject()
        .put("id", track.id)
        .put("type", if (track.isVideoUpload) "video" else "track")
        .put("title", track.title)
        .put("artist", track.artist)
        .put("album", track.album)
        .put("musicVideoType", track.musicVideoType)
        .put("durationSeconds", track.durationMs / 1000.0)
        .put("explicit", track.explicit)
        .put("isUpload", track.isUpload))

    suspend fun setLiked(track: Track, liked: Boolean) {
        provider.invoke("library.like.set", payload().withTrack(track).put("liked", liked))
    }

    suspend fun isLiked(track: Track): Boolean =
        provider.invoke("library.like.status", payload().withTrack(track)).optBoolean("liked")

    suspend fun targets(track: Track): List<PlaylistTarget> =
        provider.invoke("library.playlist.targets", payload().withTrack(track))
            .optJSONArray("playlists").mapObjects { item ->
                PlaylistTarget(item.text("id"), item.text("title"), item.optBoolean("containsTrack"))
                    .takeIf { it.id.isNotEmpty() }
            }

    /** Returns the new playlist's id. */
    suspend fun create(title: String, track: Track? = null): String =
        provider.invoke(
            "library.playlist.create",
            (if (track != null) payload().withTrack(track) else payload()).put("title", title.trim()),
        ).text("playlistId")

    suspend fun add(playlistId: String, track: Track) {
        if (isLikedMusicPlaylist(playlistId)) return setLiked(track, true)
        provider.invoke("library.playlist.add", payload().withTrack(track).put("playlistId", playlistId))
    }

    /** [setVideoId] picks one occurrence when a song repeats; blank looks it up. */
    suspend fun remove(playlistId: String, track: Track, setVideoId: String = "") {
        if (isLikedMusicPlaylist(playlistId)) return setLiked(track, false)
        val row = JSONObject().put("id", track.id)
        if (setVideoId.isNotBlank()) row.put("setVideoId", setVideoId)
        provider.invoke("library.playlist.remove", payload().put("playlistId", playlistId).put("track", row))
    }

    /** Permanently moves one playlist occurrence to [toIndex], preserving duplicate songs. */
    suspend fun move(playlistId: String, fromIndex: Int, toIndex: Int) {
        require(!isLikedMusicPlaylist(playlistId)) { "Liked Music cannot be reordered." }
        provider.invoke("library.playlist.move", payload()
            .put("playlistId", playlistId).put("fromIndex", fromIndex).put("toIndex", toIndex))
    }

    suspend fun delete(playlistId: String) {
        provider.invoke("library.playlist.delete", payload().put("playlistId", playlistId))
    }

    companion object {
        const val LIKED_MUSIC_BROWSE_ID = "FEmusic_liked_videos"

        /** Liked Music is a rating feed; YouTube rejects playlist edits against it. */
        fun isLikedMusicPlaylist(playlistId: String): Boolean =
            playlistId.removePrefix("VL").trim() in setOf("LM", LIKED_MUSIC_BROWSE_ID)
    }
}
