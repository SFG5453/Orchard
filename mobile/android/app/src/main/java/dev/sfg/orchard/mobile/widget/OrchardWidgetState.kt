/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard.
 *
 * Orchard is free software: you can redistribute it and/or modify it under the
 * terms of the GNU Affero General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option) any
 * later version.
 */

package dev.sfg.orchard.mobile.widget

import android.content.Context
import dev.sfg.orchard.mobile.library.LibraryCache
import dev.sfg.orchard.mobile.model.CatalogJson
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.playback.PlaybackStateStore
import org.json.JSONObject

internal data class OrchardWidgetState(
    val currentTrack: Track? = null,
    val recentlyPlayed: List<Track> = emptyList(),
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

/** Small durable projection so widgets remain useful when Android has stopped the player process. */
internal object OrchardWidgetStateCodec {
    fun encode(state: OrchardWidgetState): JSONObject = JSONObject()
        .put("version", 1)
        .put("currentTrack", state.currentTrack?.let(CatalogJson::track))
        .put("recentlyPlayed", CatalogJson.tracks(state.recentlyPlayed.take(4)))
        .put("isPlaying", state.isPlaying)
        .put("positionMs", state.positionMs.coerceAtLeast(0))
        .put("durationMs", state.durationMs.coerceAtLeast(0))

    fun decode(root: JSONObject): OrchardWidgetState = OrchardWidgetState(
        currentTrack = root.optJSONObject("currentTrack")?.let(CatalogJson::track),
        recentlyPlayed = CatalogJson.tracks(root.optJSONArray("recentlyPlayed")).take(4),
        isPlaying = root.optBoolean("isPlaying"),
        positionMs = root.optLong("positionMs").coerceAtLeast(0),
        durationMs = root.optLong("durationMs").coerceAtLeast(0),
    )
}

internal class OrchardWidgetStateStore(private val context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun load(): OrchardWidgetState {
        val stored = preferences.getString(SNAPSHOT, null)
        if (!stored.isNullOrBlank()) {
            runCatching { return OrchardWidgetStateCodec.decode(JSONObject(stored)) }
        }
        return bootstrap()
    }

    fun save(state: OrchardWidgetState) {
        preferences.edit().putString(SNAPSHOT, OrchardWidgetStateCodec.encode(state).toString()).apply()
    }

    /** Uses the existing durable playback and library stores the first time a widget is added. */
    private fun bootstrap(): OrchardWidgetState {
        val playback = PlaybackStateStore(context).load()
        val library = LibraryCache(context).load()
        val track = playback.queue.getOrNull(playback.currentIndex)
        return OrchardWidgetState(
            currentTrack = track,
            recentlyPlayed = library.recentlyPlayed.take(4),
            durationMs = track?.durationMs ?: 0,
            positionMs = playback.positionMs,
        )
    }

    private companion object {
        const val PREFERENCES = "orchard-widget-state"
        const val SNAPSHOT = "snapshot"
    }
}
