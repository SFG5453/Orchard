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

package dev.sfg.orchard.mobile.songlinks

import dev.sfg.orchard.mobile.model.BrowseDetail
import dev.sfg.orchard.mobile.model.CatalogKind
import dev.sfg.orchard.mobile.model.Track
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Builds the same public links as desktop and recognizes links pasted into search. */
class SongLinksRepository {
    fun trackUrl(track: Track): String? = track.id.trim()
        .takeIf { track.playbackSource.equals("youtube", ignoreCase = true) && !track.isUpload }
        ?.takeIf { YOUTUBE_ID_REGEX.matches(it) }
        ?.let { "https://song.link/y/$it" }

    fun collectionUrl(detail: BrowseDetail): String? {
        if (detail.kind == CatalogKind.ARTIST) {
            val id = detail.id.trim()
            return id.takeIf { it.startsWith("UC") && PUBLIC_ID_REGEX.matches(it) }
                ?.let { "https://music.youtube.com/channel/$it" }
        }
        if (detail.kind != CatalogKind.ALBUM && detail.kind != CatalogKind.PLAYLIST) return null

        val rawId = detail.audioPlaylistId.ifBlank { detail.id }.trim().substringBefore(':')
        // VL is a browse wrapper, not part of the public playlist ID. MPREb is
        // also browse-only; album.link would send it straight to the 400 club.
        val id = if (rawId.startsWith("VL")) rawId.removePrefix("VL") else rawId
        return id.takeIf { PUBLIC_ID_REGEX.matches(it) &&
            (it.startsWith("OLAK") || it.startsWith("PL") || it.startsWith("RD")) }
            ?.let { "https://album.link/y/$it" }
    }

    fun parseLink(rawInput: String): SongLinkTarget? {
        // Android shares often include a title before the URL; search past it.
        val input = SHARED_URL_REGEX.find(rawInput)?.value?.trimEnd('.', ',', ')') ?: rawInput.trim()
        if (input.isBlank()) return null

        if (input.startsWith("orchard:", ignoreCase = true)) {
            val parts = input.substringAfter(':').removePrefix("//")
                .substringBefore('?').substringBefore('#').split('/').filter(String::isNotBlank)
            val kind = parts.firstOrNull()?.lowercase() ?: return null
            val id = parts.getOrNull(1) ?: return null
            return when (kind) {
                "video", "watch", "track" ->
                    id.takeIf { YOUTUBE_ID_REGEX.matches(it) }?.let(SongLinkTarget::Video)
                "album", "artist", "playlist" ->
                    id.takeIf { PUBLIC_ID_REGEX.matches(it) }?.let { SongLinkTarget.Browse(kind, it) }
                else -> null
            }
        }

        val candidate = if (input.startsWith("https://", true) || input.startsWith("http://", true)) {
            input
        } else if (KNOWN_HOSTS.any { input.startsWith(it, true) }) {
            "https://$input"
        } else {
            return null
        }
        val url = candidate.toHttpUrlOrNull() ?: return null
        val host = url.host.lowercase().removePrefix("www.")

        if (host == "song.link" || host == "album.link") {
            if (url.pathSegments.size != 2 || url.pathSegments[0] != "y") return null
            val id = url.pathSegments[1]
            return if (host == "song.link") {
                id.takeIf { YOUTUBE_ID_REGEX.matches(it) }?.let(SongLinkTarget::Video)
            } else {
                id.takeIf { PUBLIC_ID_REGEX.matches(it) }?.let {
                    SongLinkTarget.Browse(if (it.startsWith("OLAK")) "album" else "playlist", it)
                }
            }
        }

        if (host !in YOUTUBE_HOSTS) return null
        if (host == "youtu.be") {
            val id = url.pathSegments.firstOrNull().orEmpty()
            if (YOUTUBE_ID_REGEX.matches(id)) return SongLinkTarget.Video(id)
        }
        val videoId = url.queryParameter("v").orEmpty()
        if (YOUTUBE_ID_REGEX.matches(videoId)) return SongLinkTarget.Video(videoId)
        if (url.pathSegments.firstOrNull() in setOf("shorts", "embed", "live")) {
            val id = url.pathSegments.getOrNull(1).orEmpty()
            if (YOUTUBE_ID_REGEX.matches(id)) return SongLinkTarget.Video(id)
        }
        val playlistId = url.queryParameter("list").orEmpty().removePrefix("VL")
        if (playlistId.isNotBlank() && PUBLIC_ID_REGEX.matches(playlistId)) {
            return SongLinkTarget.Browse(if (playlistId.startsWith("OLAK")) "album" else "playlist", playlistId)
        }
        if (url.pathSegments.firstOrNull() in setOf("browse", "channel")) {
            val id = url.pathSegments.getOrNull(1).orEmpty()
            if (PUBLIC_ID_REGEX.matches(id)) {
                val kind = when {
                    id.startsWith("MPRE") -> "album"
                    id.startsWith("VL") || id.startsWith("PL") || id.startsWith("RD") -> "playlist"
                    else -> "artist"
                }
                return SongLinkTarget.Browse(kind, id)
            }
        }
        return null
    }

    private companion object {
        val YOUTUBE_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")
        val SHARED_URL_REGEX = Regex("https?://\\S+", RegexOption.IGNORE_CASE)
        val PUBLIC_ID_REGEX = Regex("^[a-zA-Z0-9_-]+$")
        val KNOWN_HOSTS = setOf("song.link/", "album.link/", "youtu.be/", "youtube.com/",
            "www.youtube.com/", "m.youtube.com/", "music.youtube.com/", "youtube-nocookie.com/")
        val YOUTUBE_HOSTS = setOf("youtu.be", "youtube.com", "m.youtube.com", "music.youtube.com",
            "youtube-nocookie.com")
    }
}
