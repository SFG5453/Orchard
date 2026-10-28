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

package dev.sfg.orchard.mobile.applemusic

import android.content.Context
import android.util.Base64
import dev.sfg.orchard.mobile.artwork.TrackArtwork
import dev.sfg.orchard.mobile.auth.OrchardAccountService
import dev.sfg.orchard.mobile.model.BrowseDetail
import dev.sfg.orchard.mobile.model.CatalogItem
import dev.sfg.orchard.mobile.model.CatalogKind
import dev.sfg.orchard.mobile.model.HomeFeed
import dev.sfg.orchard.mobile.model.LibrarySnapshot
import dev.sfg.orchard.mobile.model.Playlist
import dev.sfg.orchard.mobile.model.SearchResults
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.provider.ProviderBundle
import dev.sfg.orchard.mobile.provider.ProviderHost
import dev.sfg.orchard.mobile.security.AndroidKeystoreCipher
import dev.sfg.orchard.mobile.youtube.mapObjects
import dev.sfg.orchard.mobile.youtube.providerDetail
import dev.sfg.orchard.mobile.youtube.providerItem
import dev.sfg.orchard.mobile.youtube.providerSections
import dev.sfg.orchard.mobile.youtube.providerSearch
import dev.sfg.orchard.mobile.youtube.providerTrack
import dev.sfg.orchard.mobile.youtube.text
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal class AppleMusicException(val code: String, message: String) : Exception(message)

/** The shared Apple Music provider, configured with the cached developer token and the listener's sign-in. */
internal class AppleMusicCatalog(context: Context, http: OkHttpClient, bundle: (String) -> ByteArray?) {
    private val provider = ProviderHost(http, ProviderBundle.AppleMusic, bundle)
    private val auth = AppleMusicAuth.get(context)
    private val account = OrchardAccountService.get(context)
    private val prefs = context.applicationContext.getSharedPreferences("orchard_apple_music", Context.MODE_PRIVATE)
    private val cipher = AndroidKeystoreCipher("orchard_apple_music_developer_token_v1")
    private val tokens = AppleMusicDeveloperTokenClient(account.serviceUrl)
    // ShazamKit sends no Origin header, so it needs a token without an origin claim.
    private val shazamTokens = AppleMusicDeveloperTokenClient(account.serviceUrl, "/apple/shazam-token")
    private val mutex = Mutex()
    private var configuredFor = ""

    fun signedIn(): Boolean = auth.userToken().isNotBlank()
    val connection = auth.state

    suspend fun search(query: String): SearchResults =
        call("catalog.search", JSONObject().put("query", query).put("filter", "all")).providerSearch()

    suspend fun home(): HomeFeed =
        HomeFeed(sections = call("catalog.home", JSONObject()).optJSONArray("sections").providerSections("apple-home"))

    /** The listener's saved songs, albums, artists and playlists. */
    suspend fun library(): LibrarySnapshot {
        val sections = call("library", JSONObject()).optJSONArray("sections")
            .mapObjects { it.text("key") to it.optJSONArray("items").mapObjects { item -> item.providerItem() } }
            .toMap()
        fun items(key: String) = sections[key].orEmpty()
        return LibrarySnapshot(
            likedTracks = items("songs").filterIsInstance<CatalogItem.Song>().map { it.track },
            savedAlbums = items("albums").filterIsInstance<CatalogItem.Record>().map { it.album },
            savedArtists = items("artists").filterIsInstance<CatalogItem.Performer>().map { it.artist },
            savedPlaylists = items("playlists").filterIsInstance<CatalogItem.Collection>().map { it.playlist },
        )
    }

    /** Songs played recently anywhere on Apple Music, newest first. */
    suspend fun recentTracks(): List<Track> =
        call("library.recent", JSONObject()).optJSONArray("items").mapObjects { it.providerItem() }
            .filterIsInstance<CatalogItem.Song>().map { it.track }

    /** Loving a song also saves it to the library. */
    suspend fun setLiked(track: Track, liked: Boolean) {
        call("library.like.set", JSONObject().put("track", track.payload()).put("liked", liked))
    }

    /** [playlistId] is the browse id, `am.playlists.p.…`. */
    suspend fun addToPlaylist(playlistId: String, track: Track) {
        call("library.playlist.add", JSONObject()
            .put("track", track.payload())
            .put("playlistId", playlistId.removePrefix(PLAYLIST_PREFIX)))
    }

    /** Browse id of the new playlist, seeded with [track] when set. */
    suspend fun createPlaylist(title: String, track: Track?): String {
        val payload = JSONObject().put("title", title.trim())
        if (track != null) payload.put("track", track.payload())
        return call("library.playlist.create", payload).text("playlistId").let { if (it.isEmpty()) it else PLAYLIST_PREFIX + it }
    }

    // Non-Apple tracks are matched to a catalog song by the provider.
    private fun Track.payload() = JSONObject()
        .put("id", id)
        .put("title", title)
        .put("artist", artist)
        .put("album", album)

    suspend fun homeSection(id: String): List<CatalogItem> =
        call("catalog.home.section", JSONObject().put("browseId", id))
            .optJSONArray("items").mapObjects { it.providerItem() }

    suspend fun detail(id: String): BrowseDetail {
        val kind = when (id.split('.').getOrNull(1)) {
            "artists" -> CatalogKind.ARTIST
            "albums" -> CatalogKind.ALBUM
            "playlists", "stations" -> CatalogKind.PLAYLIST
            else -> throw AppleMusicException("bad_id", "Apple Music cannot open this.")
        }
        val method = "catalog." + kind.name.lowercase()
        return call(method, JSONObject().put("browseId", id)).providerDetail(id, kind)
    }

    /** The next songs from a song's station (am.songs id) or from a station (am.stations id). */
    suspend fun upNext(seed: String): List<Track> =
        call("catalog.upNext", JSONObject().put("seed", seed)).optJSONArray("items").mapObjects { it.providerTrack() }

    /** Browse id of the Apple Music artist named exactly [name], or null. */
    suspend fun matchArtist(name: String): String? {
        if (name.isBlank()) return null
        return call("catalog.artist.match", JSONObject().put("name", name)).text("browseId").ifEmpty { null }
    }

    suspend fun artistView(id: String, params: String): List<CatalogItem> =
        call("catalog.artist.view", JSONObject().put("browseId", id).put("params", params))
            .optJSONArray("items").mapObjects { it.providerItem() }

    /**
     * Apple's own motion artwork for a song, or for an album when [album] is set. Apple ids on the
     * track skip the catalog search.
     */
    suspend fun artwork(track: Track, album: Boolean): TrackArtwork? {
        val payload = JSONObject()
            .put("title", track.title)
            .put("artist", track.artist)
            .put("album", track.album)
        if (album) payload.put("kind", "album")
        val albumId = if (album && track.id.startsWith("am.albums.")) track.id else track.albumId
        if (albumId.startsWith("am.albums.")) payload.put("albumId", albumId)
        if (track.id.startsWith("am.songs.")) payload.put("appleId", track.id.removePrefix("am.songs."))
        val result = call("catalog.artwork", payload)
        val artwork = TrackArtwork(
            trackId = track.id,
            staticUrl = result.text("static"),
            videoUrl = result.text("animated"),
            videoUrlVertical = result.text("animatedVertical"),
            heroTallUrl = result.text("heroTall"),
        )
        return artwork.takeIf { it.staticUrl.isNotBlank() || it.videoUrl.isNotBlank() || it.videoUrlVertical.isNotBlank() }
    }

    /**
     * Apple's audio analysis (beat and bar grid, vocal activity) keyed by catalog song id, for
     * `am.songs.` ids or bare catalog ids. Songs Apple has not analysed are missing.
     */
    suspend fun audioAnalysis(ids: List<String>): JSONObject =
        call("catalog.audioAnalysis", JSONObject().put("ids", JSONArray(ids))).optJSONObject("items") ?: JSONObject()

    suspend fun lyrics(track: Track): String {
        val payload = JSONObject().put("title", track.title).put("artist", track.artist).put("album", track.album)
        if (track.id.startsWith("am.songs.")) payload.put("appleId", track.id.removePrefix("am.songs."))
        return call("lyrics", payload).text("ttml")
    }

    /** Playlist layout and license details of a catalog song's Widevine stream. */
    suspend fun stream(id: String): JSONObject =
        call("playback.stream", JSONObject().put("track", JSONObject().put("id", id)))

    /** A music video's master playlist and license endpoint, for an am.music-videos id. */
    suspend fun video(id: String): JSONObject = call("playback.video", JSONObject().put("id", id))

    /** The am.music-videos id of a catalog song's music video, or blank when Apple has none. */
    suspend fun musicVideo(songId: String): String =
        call("catalog.musicVideo", JSONObject().put("id", songId)).optString("videoId")
            .takeIf(String::isNotBlank)?.let { "am.music-videos.$it" }.orEmpty()

    /** Apple's license for a Widevine [challenge] against a stream from [stream]. */
    suspend fun license(stream: JSONObject, challenge: ByteArray): ByteArray {
        val payload = JSONObject()
            .put("licenseUrl", stream.text("licenseUrl"))
            .put("keyUri", stream.text("keyUri"))
            .put("appleId", stream.text("appleId"))
            .put("challenge", Base64.encodeToString(challenge, Base64.NO_WRAP))
        return Base64.decode(call("playback.license", payload).text("license"), Base64.DEFAULT)
    }

    private suspend fun call(method: String, payload: JSONObject, retry: Boolean = true): JSONObject {
        check(signedIn()) { "Connect Apple Music in Settings to load this." }
        configure()
        val result = provider.invoke(method, payload)
        val error = result.optJSONObject("error") ?: return result
        val code = error.text("code")
        if (code == "developer_token" && retry) {
            // A revoked or rotated key; fetch a fresh token once.
            forgetDeveloperToken()
            return call(method, payload, retry = false)
        }
        throw AppleMusicException(code, error.text("message").ifEmpty { "Apple Music could not load this." })
    }

    private suspend fun configure() = mutex.withLock {
        val developer = developerToken()
        val user = auth.userToken()
        val key = "${developer.hashCode()}:${user.hashCode()}"
        if (key == configuredFor) return@withLock
        val region = Locale.getDefault().country.lowercase().ifEmpty { "us" }
        setSession(developer, user, region)
        val storefront = provider.invoke("storefront.resolve", JSONObject().put("fallback", region))
            .text("storefront").ifEmpty { region }
        if (storefront != region) setSession(developer, user, storefront)
        configuredFor = key
    }

    private suspend fun setSession(developer: String, user: String, storefront: String) {
        provider.invoke("session.set", JSONObject()
            .put("developerToken", developer)
            .put("userToken", user)
            .put("storefront", storefront)
            .put("origin", AppleMusicWebSession.PAGE_ORIGIN))
    }

    private suspend fun developerToken(): String = cachedToken(tokens, KEY_DEVELOPER_TOKEN, KEY_DEVELOPER_EXPIRY)

    internal suspend fun shazamToken(): String = cachedToken(shazamTokens, KEY_SHAZAM_TOKEN, KEY_SHAZAM_EXPIRY)

    internal fun forgetShazamToken() {
        prefs.edit().remove(KEY_SHAZAM_TOKEN).remove(KEY_SHAZAM_EXPIRY).apply()
    }

    /** Reuses the stored token until a day before it expires. */
    private suspend fun cachedToken(client: AppleMusicDeveloperTokenClient, tokenKey: String, expiryKey: String): String {
        val now = System.currentTimeMillis() / 1000
        val stored = runCatching { prefs.getString(tokenKey, null)?.let(cipher::decrypt) }.getOrNull()
        if (!stored.isNullOrEmpty() && prefs.getLong(expiryKey, 0) > now + REFRESH_MARGIN_SECONDS) return stored
        val accessToken = account.accessToken()
            ?: throw AppleMusicException("orchard_account", "Sign in to your Orchard account to use Apple Music.")
        val token = client.fetch(accessToken)
        runCatching {
            prefs.edit()
                .putString(tokenKey, cipher.encrypt(token.value))
                .putLong(expiryKey, token.expiresAt)
                .apply()
        }
        return token.value
    }

    private suspend fun forgetDeveloperToken() = mutex.withLock {
        prefs.edit().remove(KEY_DEVELOPER_TOKEN).remove(KEY_DEVELOPER_EXPIRY).apply()
        configuredFor = ""
    }

    private companion object {
        const val KEY_DEVELOPER_TOKEN = "developer_token"
        const val KEY_DEVELOPER_EXPIRY = "developer_token_expires_at"
        const val KEY_SHAZAM_TOKEN = "shazam_token"
        const val KEY_SHAZAM_EXPIRY = "shazam_token_expires_at"
        const val REFRESH_MARGIN_SECONDS = 24 * 60 * 60L
        const val PLAYLIST_PREFIX = "am.playlists."
    }
}

