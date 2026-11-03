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

package dev.sfg.orchard.mobile.artwork

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import dev.sfg.orchard.mobile.model.PlaybackSnapshot
import dev.sfg.orchard.mobile.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Tall motion loops the listener rejected, keyed by URL so every track of an album follows.
 * Some of Apple's tall loops are the square cover zoomed over a baked-in blur, and no image
 * check separates them from real tall art reliably.
 */
class RejectedTallArtwork(context: Context) {
    private val prefs = context.getSharedPreferences("rejected_tall_artwork", Context.MODE_PRIVATE)
    private val mutableUrls = MutableStateFlow(prefs.getStringSet(KEY, null).orEmpty().toSet())
    val urls: StateFlow<Set<String>> = mutableUrls

    fun toggle(url: String) {
        val key = key(url)
        if (key.isBlank()) return
        val next = mutableUrls.value.let { if (key in it) it - key else it + key }
        mutableUrls.value = next
        prefs.edit().putStringSet(KEY, next).apply()
    }

    private companion object {
        const val KEY = "urls"
    }
}

/** Signed CDN query strings change between lookups; the path names the loop. */
private fun key(url: String) = url.substringBefore('?').trim()

fun Track.tallArtRejected(rejected: Set<String>) =
    animatedArtworkVerticalUrl.isNotBlank() && key(animatedArtworkVerticalUrl) in rejected

/** Drops a rejected tall loop, so the square loop (or the still cover) takes its place. */
fun Track.withoutRejectedTallArt(rejected: Set<String>): Track =
    if (tallArtRejected(rejected)) copy(animatedArtworkVerticalUrl = "") else this

fun PlaybackSnapshot.withoutRejectedTallArt(rejected: Set<String>): PlaybackSnapshot =
    if (rejected.isEmpty()) this else copy(
        currentTrack = currentTrack?.withoutRejectedTallArt(rejected),
        queue = queue.map { it.withoutRejectedTallArt(rejected) },
    )

/** The player menu's switch for the current track's tall loop. */
class TallArtToggle(val rejected: Boolean, val onToggle: () -> Unit)

val LocalTallArtToggle = compositionLocalOf<TallArtToggle?> { null }
