/* Copyright (C) 2026 SFG545. Licensed under the PolyForm Shield License 1.0.0, see LICENSE-POLYFORM-SHIELD. */

package dev.sfg.orchard.mobile.playback.apple

import android.util.Log
import dev.sfg.orchard.mobile.OrchardGraph
import dev.sfg.orchard.mobile.model.APPLE_MUSIC_SOURCE
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

/**
 * Apple Music's audio analysis for adaptive mix and Best Mix, as desktop's
 * `PlaybackController::appleMixAnalyses`: only while Apple Music is the music source and signed in.
 */
internal object AppleMixAnalysis {
    private const val TAG = "OrchardAppleMix"
    private const val SONG_PREFIX = "am.songs."

    fun eligible(graph: OrchardGraph, trackId: String): Boolean =
        trackId.startsWith(SONG_PREFIX) &&
            graph.settings.settings.value.homeSource == APPLE_MUSIC_SOURCE &&
            graph.appleMusic.signedIn()

    /** Analysis by Orchard track id for the eligible ids; a failed lookup returns what it has. */
    suspend fun fetch(graph: OrchardGraph, trackIds: List<String>): Map<String, JSONObject> {
        val wanted = trackIds.filter { eligible(graph, it) }.distinct()
        if (wanted.isEmpty()) return emptyMap()
        val items = try {
            graph.appleMusic.audioAnalysis(wanted)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // The mix falls back to the models; Best Mix leaves these songs in place.
            Log.w(TAG, "Apple Music analysis unavailable: ${error.message}")
            return emptyMap()
        }
        return wanted.mapNotNull { id -> items.optJSONObject(id.removePrefix(SONG_PREFIX))?.let { id to it } }.toMap()
    }
}
