/* Copyright (C) 2026 SFG545. Licensed under the GNU AGPL v3 or later. */

package dev.sfg.orchard.mobile.ui.screens

import dev.sfg.orchard.mobile.model.Artist
import dev.sfg.orchard.mobile.model.BrowseDetail
import dev.sfg.orchard.mobile.model.CatalogKind

internal fun BrowseDetail.isVariousArtists(): Boolean = artist.trim().equals("Various Artists", ignoreCase = true)

internal fun BrowseDetail.albumArtists(): List<Artist> {
    if (kind != CatalogKind.ALBUM) return emptyList()
    val candidates = if (isVariousArtists()) {
        tracks.flatMap { track ->
            track.artists.ifEmpty { listOf(Artist(track.artistId, track.artist)) }
        }
    } else {
        artists.ifEmpty { tracks.firstOrNull { it.artistId.isNotBlank() }?.let { listOf(Artist(it.artistId, it.artist)) }.orEmpty() }
    }
    return candidates.filter { it.id.isNotBlank() && it.name.isNotBlank() && !it.name.trim().equals("Various Artists", true) }
        .distinctBy(Artist::id)
}
