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

package dev.sfg.orchard.mobile.connect

import dev.sfg.orchard.mobile.model.Track
import org.json.JSONObject

/** Desktop's answer to ResolveArtwork: a larger cover and, when they exist, square and tall loops. */
data class ConnectArtwork(val staticUrl: String, val animatedUrl: String, val animatedVerticalUrl: String = "")

/**
 * Artwork requests and unsupported provider responses across Connect.
 */
internal class ConnectProviders(
    private val connect: ConnectRepository,
) {
    fun serve(event: JSONObject) {
        connect.respond(event.optString("id"), false, error = "unsupported")
    }

    // ── Artwork from the desktop ──────────────────────────────────────────────────────────────

    /** The Artwork Host's cover for [track], or null when this phone is its own artwork host. */
    suspend fun artwork(track: Track): ConnectArtwork? {
        if (connect.roleHost("artwork") == null) return null
        val params = JSONObject()
            .put("kind", "track")
            .put("title", track.title)
            .put("artist", track.artist)
            .put("album", track.album)
            .put("artwork", track.artworkUrl)
        val result = connect.request("artwork", "ResolveArtwork", params, timeoutMs = 15_000)?.optJSONObject("result")
            ?: return null
        return ConnectArtwork(
            result.optString("static_url"),
            result.optString("animated_url"),
            result.optString("animated_vertical_url"),
        )
    }

}
