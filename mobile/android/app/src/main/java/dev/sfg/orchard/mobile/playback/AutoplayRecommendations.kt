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

package dev.sfg.orchard.mobile.playback

import dev.sfg.orchard.mobile.model.Track
import java.text.Normalizer
import kotlin.math.abs

/** Selects distinct songs from YouTube Music's radio response for the Autoplay queue. */
object AutoplayRecommendations {
    /**
     * Finds duplicate rows that Autoplay itself added. User-authored duplicate playlist rows are
     * intentionally retained, but an Autoplay row may not repeat either a chosen row or an earlier
     * Autoplay recommendation. Returning indices lets the live player remove only bad upcoming
     * rows without rebuilding the queue or interrupting playback.
     */
    fun duplicateGeneratedIndices(queue: List<Track>): List<Int> {
        val kept = mutableListOf<Track>()
        return buildList {
            queue.forEachIndexed { index, track ->
                if (track.autoplayGenerated && kept.any { it.isSameSongAs(track) }) {
                    add(index)
                } else {
                    kept += track
                }
            }
        }
    }

    /**
     * YouTube can return the album audio, music video and radio edit as separate video ids. Queue
     * identity still uses those ids, but Autoplay should not put each representation of the same
     * recording next to the others. User-authored playlists remain untouched by keeping this rule
     * local to the Autoplay ingress path.
     */
    fun select(existing: List<Track>, candidates: List<Track>, limit: Int): List<Track> {
        if (limit <= 0) return emptyList()
        val selected = mutableListOf<Track>()

        candidates.forEach { candidate ->
            if (candidate.id.isBlank() || existing.any { it.isSameSongAs(candidate) }) return@forEach

            val duplicateIndex = selected.indexOfFirst { it.isSameSongAs(candidate) }
            if (duplicateIndex >= 0) {
                if (candidate.versionPreference > selected[duplicateIndex].versionPreference) {
                    // Keep YouTube's recommendation position while swapping in the album cut.
                    selected[duplicateIndex] = candidate
                }
            } else if (selected.size < limit) {
                selected += candidate
            }
        }

        return selected
    }

    private fun Track.isSameSongAs(other: Track): Boolean {
        if (id == other.id) return true
        if (title.songTitleKey() != other.title.songTitleKey()) return false
        if (!hasSamePrimaryArtist(other)) return false

        // Different ids for audio and video normally differ by only an intro or outro. A much
        // larger difference is more likely an extended, acoustic or live performance whose title
        // omitted the qualifier, so retain it.
        return durationMs <= 0L || other.durationMs <= 0L ||
            abs(durationMs - other.durationMs) <= MAX_VARIANT_DURATION_DRIFT_MS
    }

    private fun Track.hasSamePrimaryArtist(other: Track): Boolean {
        if (artistId.isNotBlank() && other.artistId.isNotBlank() && artistId == other.artistId) return true
        val artistKey = artist.textKey()
        val otherArtistKey = other.artist.textKey()
        return artistKey == otherArtistKey || artist.primaryArtistKey() == other.artist.primaryArtistKey()
    }

    private val Track.versionPreference: Int
        get() = when {
            isAudioOnly -> 3
            musicVideoType.isBlank() -> 2
            isVideoUpload -> 1
            else -> 0
        }

    private fun String.songTitleKey(): String =
        normalizeUnicode()
            .replace(BRACKETED_COLLABORATOR, " ")
            .replace(BRACKETED_VERSION, " ")
            .replace(TRAILING_VERSION, " ")
            .textKey()

    private fun String.primaryArtistKey(): String =
        normalizeUnicode().split(COLLABORATOR, limit = 2).first().textKey()

    private fun String.textKey(): String = normalizeUnicode()
        .lowercase()
        .replace(NON_ALPHANUMERIC, " ")
        .trim()
        .replace(MULTIPLE_SPACES, " ")

    private fun String.normalizeUnicode(): String = Normalizer.normalize(this, Normalizer.Form.NFKC)

    private const val MAX_VARIANT_DURATION_DRIFT_MS = 45_000L

    private val BRACKETED_VERSION = Regex(
        """\s*[\[(]\s*(?:official\s+)?(?:music\s+)?(?:video|audio|lyrics?(?:\s+video)?|visuali[sz]er|radio\s+edit|single\s+version|album\s+version|remaster(?:ed)?(?:\s+\d{4})?)\s*[\])]\s*""",
        RegexOption.IGNORE_CASE,
    )
    private val BRACKETED_COLLABORATOR = Regex(
        """\s*[\[(]\s*(?:feat(?:uring)?\.?|ft\.?|with)\s+[^\])]+[\])]\s*""",
        RegexOption.IGNORE_CASE,
    )
    private val TRAILING_VERSION = Regex(
        """\s*[-\u2013\u2014]\s*(?:official\s+)?(?:music\s+)?(?:video|audio|lyrics?(?:\s+video)?|visuali[sz]er|radio\s+edit|single\s+version|album\s+version|remaster(?:ed)?(?:\s+\d{4})?)\s*$""",
        RegexOption.IGNORE_CASE,
    )
    private val COLLABORATOR = Regex(
        """\s+(?:feat(?:uring)?\.?|ft\.?|with|x)\s+|\s+&\s+""",
        RegexOption.IGNORE_CASE,
    )
    private val NON_ALPHANUMERIC = Regex("""[^\p{L}\p{N}]+""")
    private val MULTIPLE_SPACES = Regex("""\s+""")
}
