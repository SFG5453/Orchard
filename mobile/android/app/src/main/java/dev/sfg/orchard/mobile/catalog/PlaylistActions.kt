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

import org.json.JSONArray
import org.json.JSONObject

/** Authenticated mutations for playlists.  The client supplies the session and request headers. */
class PlaylistActions(private val client: InnerTubeClient) {
    /**
     * YouTube's Liked Music collection is a rating feed, not an editable playlist. Match the
     * desktop WEB_REMIX request: its raw Actions call wraps the video id in LikeEndpoint.target;
     * browse/edit_playlist rejects this special collection with INVALID_ARGUMENT.
     */
    fun setLiked(videoId: String, liked: Boolean) {
        val id = videoId.trim()
        require(id.isNotEmpty()) { "This track cannot be liked." }
        client.post(
            if (liked) "like/like" else "like/removelike",
            JSONObject().put("target", JSONObject().put("videoId", id)),
        )
    }

    fun create(title: String, videoId: String): String {
        val cleanTitle = title.trim()
        require(cleanTitle.isNotEmpty()) { "Enter a playlist name." }
        require(videoId.isNotBlank()) { "This track cannot be added to a playlist." }
        val result = client.post("playlist/create", JSONObject()
            .put("title", cleanTitle)
            .put("videoIds", JSONArray().put(videoId)))
        return result.optString("playlistId").ifBlank { result.optString("playlist_id") }
            .also { check(it.isNotBlank()) { "YouTube did not create the playlist." } }
    }

    fun add(playlistId: String, videoId: String) {
        if (isLikedMusicPlaylist(playlistId)) {
            setLiked(videoId, true)
            return
        }
        val id = playlistId.removePrefix("VL").trim()
        require(id.isNotEmpty() && videoId.isNotBlank()) { "Playlist or track information is missing." }
        val page = client.post("browse", JSONObject().put("browseId", "VL$id"))
        check(!containsVideoId(page, videoId)) { "This track is already in the playlist." }
        edit(id, videoId, "ACTION_ADD_VIDEO")
    }

    fun remove(playlistId: String, videoId: String) {
        if (isLikedMusicPlaylist(playlistId)) {
            setLiked(videoId, false)
            return
        }
        val id = playlistId.removePrefix("VL").trim()
        require(id.isNotEmpty() && videoId.isNotBlank()) { "Playlist or track information is missing." }
        var page = client.post("browse", JSONObject().put("browseId", "VL$id"))
        var setVideoId = findSetVideoId(page, videoId)
        var continuation = CatalogParser.continuationToken(page)
        var pages = 0
        while (setVideoId.isBlank() && continuation.isNotBlank() && pages < MAX_PLAYLIST_PAGES) {
            page = client.browseContinuation(continuation)
            setVideoId = findSetVideoId(page, videoId)
            val next = CatalogParser.continuationToken(page)
            if (next == continuation) break
            continuation = next
            pages++
        }
        check(setVideoId.isNotBlank()) { "This track is not in the playlist." }
        client.post("browse/edit_playlist", JSONObject()
            .put("playlistId", id)
            .put("actions", JSONArray().put(JSONObject()
                .put("action", "ACTION_REMOVE_VIDEO")
                .put("setVideoId", setVideoId))))
    }

    /** Permanently moves one playlist occurrence to [toIndex], preserving duplicate songs. */
    fun move(playlistId: String, fromIndex: Int, toIndex: Int) {
        require(!isLikedMusicPlaylist(playlistId)) { "Liked Music cannot be reordered." }
        val id = playlistId.removePrefix("VL").trim()
        require(id.isNotEmpty()) { "Playlist information is missing." }
        require(fromIndex >= 0 && toIndex >= 0) { "Playlist position is invalid." }
        if (fromIndex == toIndex) return

        val entries = playlistEntries(id)
        require(fromIndex in entries.indices && toIndex in entries.indices) {
            "The playlist changed. Refresh it and try again."
        }
        val movedSetVideoId = entries[fromIndex]
        val remaining = entries.toMutableList().apply { removeAt(fromIndex) }
        val successor = remaining.getOrNull(toIndex)
        val action = JSONObject()
            .put("action", "ACTION_MOVE_VIDEO_BEFORE")
            .put("setVideoId", movedSetVideoId)
        if (successor != null) action.put("movedSetVideoIdSuccessor", successor)

        client.post(
            "browse/edit_playlist",
            JSONObject()
                .put("playlistId", id)
                .put("actions", JSONArray().put(action)),
        )
    }

    fun delete(playlistId: String) {
        val id = playlistId.removePrefix("VL").trim()
        require(id.isNotEmpty()) { "Playlist information is missing." }
        client.post("playlist/delete", JSONObject().put("playlistId", id))
    }

    private fun edit(playlistId: String, videoId: String, action: String) {
        val id = playlistId.removePrefix("VL").trim()
        require(id.isNotEmpty() && videoId.isNotBlank()) { "Playlist or track information is missing." }
        val actionObj = JSONObject().put("action", action)
        if (action == "ACTION_ADD_VIDEO") {
            actionObj.put("addedVideoId", videoId)
        } else {
            actionObj.put("videoId", videoId)
        }
        client.post("browse/edit_playlist", JSONObject()
            .put("playlistId", id)
            .put("actions", JSONArray().put(actionObj)))
    }

    private fun playlistEntries(playlistId: String): List<String> {
        var page = client.post("browse", JSONObject().put("browseId", "VL$playlistId"))
        val entries = playlistSetVideoIds(page).toMutableList()
        var continuation = CatalogParser.continuationToken(page)
        var pages = 0
        while (continuation.isNotBlank() && pages < MAX_PLAYLIST_PAGES) {
            page = client.browseContinuation(continuation)
            entries += playlistSetVideoIds(page)
            val next = CatalogParser.continuationToken(page)
            if (next == continuation) break
            continuation = next
            pages++
        }
        return entries.distinct()
    }

    private fun playlistSetVideoIds(page: JSONObject): List<String> =
        JsonTraversal.renderers(page, "playlistItemData")
            .mapNotNull { item -> item.optString("playlistSetVideoId").takeIf(String::isNotBlank) }

    private fun findSetVideoId(value: Any?, videoId: String, inheritedSetId: String = "", depth: Int = 0): String {
        if (depth > 20) return ""
        if (value is JSONArray) {
            for (index in 0 until value.length()) {
                val found = findSetVideoId(value.opt(index), videoId, inheritedSetId, depth + 1)
                if (found.isNotBlank()) return found
            }
            return ""
        }
        if (value !is JSONObject) return ""
        val localSetId = value.optString("playlistSetVideoId").ifBlank { inheritedSetId }
        val item = value.optJSONObject("playlistItemData")
        if (item?.optString("videoId") == videoId) {
            return item.optString("playlistSetVideoId")
                .ifBlank { localSetId }
        }
        // Some browse responses flatten playlistItemData and keep the set id on its renderer.
        if (value.optString("videoId") == videoId && localSetId.isNotBlank()) return localSetId
        val keys = value.keys()
        while (keys.hasNext()) {
            val found = findSetVideoId(value.opt(keys.next()), videoId, localSetId, depth + 1)
            if (found.isNotBlank()) return found
        }
        return ""
    }

    private fun containsVideoId(value: Any?, videoId: String, depth: Int = 0): Boolean = when {
        depth > 20 -> false
        value is JSONArray -> (0 until value.length()).any { containsVideoId(value.opt(it), videoId, depth + 1) }
        value is JSONObject -> {
            value.optString("videoId") == videoId || value.keys().asSequence()
                .any { key -> containsVideoId(value.opt(key), videoId, depth + 1) }
        }
        else -> false
    }

    companion object {
        /** YouTube Music returns roughly 100 playlist rows per browse page. */
        private const val MAX_PLAYLIST_PAGES = 50
        const val LIKED_MUSIC_BROWSE_ID = "FEmusic_liked_videos"

        fun isLikedMusicPlaylist(playlistId: String): Boolean =
            playlistId.removePrefix("VL").trim() in setOf("LM", LIKED_MUSIC_BROWSE_ID)
    }
}
