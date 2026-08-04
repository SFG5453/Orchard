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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Client repository for https://songlinks.sfg545.dev.
 * Resolves universal song/album/playlist/artist links and handles deep link parsing.
 */
class SongLinksRepository(
    private val http: OkHttpClient,
    private val origin: String = DEFAULT_ORIGIN,
) {
    suspend fun resolveTrack(
        track: Track,
        albumName: String? = null,
        artistName: String? = null,
    ): ResolvedSong = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("title", track.title.trim())
            put("artist", (artistName?.takeIf(String::isNotBlank) ?: track.artist).trim())
            val album = (albumName?.takeIf(String::isNotBlank) ?: track.album).trim()
            if (album.isNotBlank()) put("album", album)
            if (track.id.isNotBlank()) put("youtubeVideoId", track.id.trim())
            if (track.artworkUrl.isNotBlank()) put("thumbnailUrl", track.artworkUrl.trim())
        }

        val request = Request.Builder()
            .url("$origin/resolve")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("SongLinks resolve failed: HTTP ${response.code}")
            }
            val body = response.body.string()
            parseResolvedSong(JSONObject(body), fallbackTrack = track)
        }
    }

    suspend fun resolveCollection(detail: BrowseDetail): ResolvedCollection = withContext(Dispatchers.IO) {
        val kind = when (detail.kind) {
            CatalogKind.ALBUM -> "album"
            CatalogKind.PLAYLIST -> "playlist"
            CatalogKind.ARTIST -> "artist"
            CatalogKind.TRACK -> "album"
        }

        val tracksArray = JSONArray()
        detail.tracks.take(300).forEach { t ->
            val obj = JSONObject().apply {
                put("title", t.title.trim())
                put("artist", t.artist.ifBlank { detail.title }.trim())
                if (t.album.isNotBlank()) put("album", t.album.trim())
                if (t.id.isNotBlank()) put("youtubeVideoId", t.id.trim())
                if (t.artworkUrl.isNotBlank()) put("thumbnailUrl", t.artworkUrl.trim())
            }
            tracksArray.put(obj)
        }

        val payload = JSONObject().apply {
            put("kind", kind)
            put("title", detail.title.trim())
            if (detail.subtitle.isNotBlank()) put("subtitle", detail.subtitle.trim())
            if (detail.id.isNotBlank()) put("browseId", detail.id.trim())
            if (detail.artworkUrl.isNotBlank()) put("thumbnailUrl", detail.artworkUrl.trim())
            if (detail.year.isNotBlank()) put("itemCount", detail.year.trim())
            put("orchardOnly", false)
            put("tracks", tracksArray)
        }

        val request = Request.Builder()
            .url("$origin/collections/resolve")
            .header("Accept", "application/json")
            .header("Content-Type", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("SongLinks collection resolve failed: HTTP ${response.code}")
            }
            val body = response.body.string()
            parseResolvedCollection(JSONObject(body), fallbackDetail = detail)
        }
    }

    suspend fun loadSong(id: String, baseOrigin: String = origin): ResolvedSong? = withContext(Dispatchers.IO) {
        val encodedId = URLEncoder.encode(id, "UTF-8")
        val request = Request.Builder()
            .url("$baseOrigin/api/songs/$encodedId")
            .header("Accept", "application/json")
            .get()
            .build()

        runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val body = response.body.string()
                parseResolvedSong(JSONObject(body), null)
            }
        }.getOrNull()
    }

    suspend fun loadCollection(id: String, baseOrigin: String = origin): ResolvedCollection? = withContext(Dispatchers.IO) {
        val encodedId = URLEncoder.encode(id, "UTF-8")
        val request = Request.Builder()
            .url("$baseOrigin/api/collections/$encodedId")
            .header("Accept", "application/json")
            .get()
            .build()

        runCatching {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                val body = response.body.string()
                parseResolvedCollection(JSONObject(body), null)
            }
        }.getOrNull()
    }

    fun parseLink(rawInput: String): SongLinkTarget? {
        val input = rawInput.trim()
        if (input.isBlank()) return null

        val candidate = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(input)) {
            input
        } else if (Regex("^(?:www\\.youtube\\.com|youtube\\.com|m\\.youtube\\.com|music\\.youtube\\.com|youtu\\.be|songlinks\\.sfg545\\.dev)\\b", RegexOption.IGNORE_CASE).containsMatchIn(input)) {
            "https://$input"
        } else {
            input
        }

        // Handle custom orchard: scheme
        if (candidate.startsWith("orchard:", ignoreCase = true)) {
            val stripped = candidate.substring(8).removePrefix("//")
            val parts = stripped.split("?", "#").first().split("/").filter(String::isNotBlank)
            if (parts.isEmpty()) return null
            val kind = parts[0].lowercase()
            val id = if (parts.size > 1) parts[1] else parts[0]

            return when (kind) {
                "s", "song", "songlink" -> SongLinkTarget.Song(id)
                "c", "collection", "share" -> SongLinkTarget.Collection(id)
                "album", "artist", "playlist", "podcast" -> SongLinkTarget.Browse(kind, id)
                "video", "watch", "track" -> if (YOUTUBE_ID_REGEX.matches(id)) SongLinkTarget.Video(id) else null
                else -> null
            }
        }

        val httpUrl = candidate.toHttpUrlOrNull() ?: return null
        val host = httpUrl.host.lowercase().removePrefix("www.")

        if (host == "songlinks.sfg545.dev" || host == "localhost" || host == "127.0.0.1") {
            val path = httpUrl.encodedPath
            val songMatch = Regex("^/(?:s|api/songs)/([^/?#]+)").find(path)
            if (songMatch != null) {
                return SongLinkTarget.Song(songMatch.groupValues[1], httpUrl.scheme + "://" + httpUrl.host)
            }
            val collectionMatch = Regex("^/(?:c|api/collections)/([^/?#]+)").find(path)
            if (collectionMatch != null) {
                return SongLinkTarget.Collection(collectionMatch.groupValues[1], httpUrl.scheme + "://" + httpUrl.host)
            }
        }

        if (YOUTUBE_HOSTS.contains(host)) {
            if (host == "youtu.be") {
                val id = httpUrl.pathSegments.firstOrNull().orEmpty()
                if (YOUTUBE_ID_REGEX.matches(id)) return SongLinkTarget.Video(id)
            }

            val videoParam = httpUrl.queryParameter("v").orEmpty()
            if (YOUTUBE_ID_REGEX.matches(videoParam)) return SongLinkTarget.Video(videoParam)

            val path = httpUrl.encodedPath
            val embedMatch = Regex("^/(?:shorts|embed|live)/([^/?#]+)").find(path)
            if (embedMatch != null && YOUTUBE_ID_REGEX.matches(embedMatch.groupValues[1])) {
                return SongLinkTarget.Video(embedMatch.groupValues[1])
            }

            val listParam = httpUrl.queryParameter("list").orEmpty()
            if (listParam.isNotBlank()) {
                val kind = if (listParam.startsWith("OLAK")) "album" else "playlist"
                return SongLinkTarget.Browse(kind, listParam)
            }

            val channelMatch = Regex("^/(?:browse|channel)/([^/?#]+)").find(path)
            if (channelMatch != null) {
                val browseId = channelMatch.groupValues[1]
                val kind = when {
                    browseId.startsWith("MPRE") || browseId.startsWith("MPR") -> "album"
                    browseId.startsWith("VL") || browseId.startsWith("PL") || browseId.startsWith("RD") -> "playlist"
                    else -> "artist"
                }
                return SongLinkTarget.Browse(kind, browseId)
            }
        }

        return null
    }

    private fun parseResolvedSong(root: JSONObject, fallbackTrack: Track?): ResolvedSong {
        val id = root.optString("id").ifBlank { fallbackTrack?.id.orEmpty() }
        val rawShareUrl = root.optString("shareUrl")
        val shareUrl = formatShareUrl(rawShareUrl, "/s/$id")

        val songObj = root.optJSONObject("song") ?: JSONObject()
        val title = songObj.optString("title").ifBlank { fallbackTrack?.title.orEmpty() }
        val artist = songObj.optString("artist").ifBlank { fallbackTrack?.artist.orEmpty() }
        val album = songObj.optString("album").ifBlank { fallbackTrack?.album.orEmpty() }
        val isrc = songObj.optString("isrc")
        val youtubeVideoId = songObj.optString("youtubeVideoId").ifBlank { fallbackTrack?.id.orEmpty() }
        val durationSeconds = songObj.optInt("durationSeconds", 0)
        val thumbnailUrl = songObj.optString("thumbnailUrl").ifBlank { fallbackTrack?.artworkUrl.orEmpty() }

        val linksArray = root.optJSONArray("links") ?: JSONArray()
        val links = parsePlatformLinks(linksArray)

        return ResolvedSong(
            id = id,
            shareUrl = shareUrl,
            title = title,
            artist = artist,
            album = album,
            isrc = isrc,
            youtubeVideoId = youtubeVideoId,
            durationSeconds = durationSeconds,
            thumbnailUrl = thumbnailUrl,
            links = links,
        )
    }

    private fun parseResolvedCollection(root: JSONObject, fallbackDetail: BrowseDetail?): ResolvedCollection {
        val id = root.optString("id").ifBlank { fallbackDetail?.id.orEmpty() }
        val rawShareUrl = root.optString("shareUrl")
        val shareUrl = formatShareUrl(rawShareUrl, "/c/$id")

        val colObj = root.optJSONObject("collection") ?: JSONObject()
        val kind = colObj.optString("kind").ifBlank { fallbackDetail?.kind?.name?.lowercase().orEmpty() }
        val title = colObj.optString("title").ifBlank { fallbackDetail?.title.orEmpty() }
        val subtitle = colObj.optString("subtitle").ifBlank { fallbackDetail?.subtitle.orEmpty() }
        val browseId = colObj.optString("browseId").ifBlank { fallbackDetail?.id.orEmpty() }
        val thumbnailUrl = colObj.optString("thumbnailUrl").ifBlank { fallbackDetail?.artworkUrl.orEmpty() }
        val itemCount = colObj.optString("itemCount").ifBlank { fallbackDetail?.year.orEmpty() }
        val orchardOnly = colObj.optBoolean("orchardOnly", false)

        val rawTracks = root.optJSONArray("tracks") ?: JSONArray()
        val tracks = mutableListOf<Track>()
        for (i in 0 until rawTracks.length()) {
            val tObj = rawTracks.optJSONObject(i) ?: continue
            val trackId = tObj.optString("youtubeVideoId")
            val trackTitle = tObj.optString("title")
            val trackArtist = tObj.optString("artist")
            val trackAlbum = tObj.optString("album")
            val trackThumb = tObj.optString("thumbnailUrl")
            if (trackTitle.isNotBlank()) {
                tracks.add(
                    Track(
                        id = trackId,
                        title = trackTitle,
                        artist = trackArtist,
                        album = trackAlbum,
                        artworkUrl = trackThumb,
                    ),
                )
            }
        }

        val linksArray = root.optJSONArray("links") ?: JSONArray()
        val links = parsePlatformLinks(linksArray)

        return ResolvedCollection(
            id = id,
            shareUrl = shareUrl,
            kind = kind,
            title = title,
            subtitle = subtitle,
            browseId = browseId,
            thumbnailUrl = thumbnailUrl,
            itemCount = itemCount,
            orchardOnly = orchardOnly,
            tracks = if (tracks.isNotEmpty()) tracks else fallbackDetail?.tracks.orEmpty(),
            links = links,
        )
    }

    private fun parsePlatformLinks(array: JSONArray): List<PlatformLink> {
        val list = mutableListOf<PlatformLink>()
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            val platform = obj.optString("platform")
            val label = obj.optString("label")
            val url = obj.optString("url")
            val confidence = obj.optDouble("confidence", 1.0)
            val matchType = obj.optString("match_type", "direct")
            val source = obj.optString("source")

            if (url.isNotBlank()) {
                list.add(
                    PlatformLink(
                        platform = platform,
                        label = label,
                        url = url,
                        confidence = confidence,
                        matchType = matchType,
                        source = source,
                    ),
                )
            }
        }
        return list
    }

    private fun formatShareUrl(rawUrl: String, fallbackPath: String): String {
        val url = rawUrl.ifBlank { fallbackPath }
        return if (url.startsWith("/")) "$origin$url" else url
    }

    companion object {
        const val DEFAULT_ORIGIN = "https://songlinks.sfg545.dev"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val YOUTUBE_ID_REGEX = Regex("^[a-zA-Z0-9_-]{11}$")
        private val YOUTUBE_HOSTS = setOf(
            "youtu.be",
            "youtube.com",
            "m.youtube.com",
            "music.youtube.com",
            "youtube-nocookie.com",
        )
    }
}
