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

package dev.sfg.orchard.mobile.qobuz

import org.json.JSONObject
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

val VERSION_MARKERS = listOf(
    "acoustic", "clean", "edit", "explicit", "instrumental", "karaoke", "live",
    "mono", "remaster", "remastered", "remix", "slowed", "sped up", "stereo"
)

fun normalizedQobuzText(value: String?): String {
    if (value.isNullOrBlank()) return ""
    val normalized = Normalizer.normalize(value, Normalizer.Form.NFKD)
        .replace(Regex("""\p{M}"""), "")
        .lowercase()
        .replace("&", " and ")
        .replace(Regex("""\b(feat|featuring|ft)\.?\s+.+$""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""[^a-z0-9]+"""), " ")
        .trim()
        .replace(Regex("""\s+"""), " ")
    return normalized
}

fun versionMarkers(value: String): List<String> {
    val normalized = normalizedQobuzText(value)
    return VERSION_MARKERS.filter { marker ->
        Regex("""\b${Regex.escape(marker).replace(" ", """\s+""")}\b""").containsMatchIn(normalized)
    }
}

fun sameVersion(targetTitle: String, targetAlbum: String, targetExplicit: Boolean, candidate: JSONObject): Boolean {
    val candidateTitle = candidate.optString("title")
    val candidateAlbum = candidate.optJSONObject("album")?.optString("title").orEmpty()
    val candidateExplicit = candidate.optBoolean("parental_warning", false)

    val wanted = versionMarkers("$targetTitle $targetAlbum")
    val offered = versionMarkers("$candidateTitle $candidateAlbum")
    if (wanted.joinToString("|") != offered.joinToString("|")) return false
    return if (candidateExplicit) targetExplicit else !targetExplicit
}

fun candidateArtists(candidate: JSONObject): List<String> {
    val list = mutableListOf<String>()
    candidate.optJSONObject("performer")?.optString("name")?.takeIf { it.isNotBlank() }?.let { list.add(it) }
    candidate.optJSONObject("artist")?.optString("name")?.takeIf { it.isNotBlank() }?.let { list.add(it) }
    candidate.optJSONObject("album")?.optJSONObject("artist")?.optString("name")?.takeIf { it.isNotBlank() }?.let { list.add(it) }
    val performers = candidate.optJSONArray("performers")
    if (performers != null) {
        for (i in 0 until performers.length()) {
            val item = performers.opt(i)
            when (item) {
                is JSONObject -> item.optString("name").takeIf { it.isNotBlank() }?.let { list.add(it) }
                is String -> if (item.isNotBlank()) list.add(item)
            }
        }
    }
    return list.map(::normalizedQobuzText).filter(String::isNotBlank).distinct()
}

fun candidateQualityScore(candidate: JSONObject): Long {
    val bitDepth = candidate.optInt("maximum_bit_depth", candidate.optInt("bit_depth", if (candidate.optBoolean("hires", false)) 24 else 16))
    val samplingRate = candidate.optDouble("maximum_sampling_rate", candidate.optDouble("sampling_rate", 0.0))
    return (bitDepth.toLong() * 1_000_000L) + (samplingRate * 1000.0).toLong()
}

fun toQobuzMatch(candidate: JSONObject, method: String, confidence: Double): QobuzMatch {
    val bitDepth = candidate.optInt("maximum_bit_depth", 0).takeIf { it > 0 }
        ?: candidate.optInt("bit_depth", 0).takeIf { it > 0 }
    val sampleRate = (candidate.optDouble("maximum_sampling_rate", 0.0).takeIf { it > 0.0 }
        ?: candidate.optDouble("sampling_rate", 0.0).takeIf { it > 0.0 })?.let { (it * 1000.0).toInt() }
    val hires = candidate.optBoolean("hires", false) || (bitDepth != null && bitDepth > 16)
    return QobuzMatch(
        qobuzTrackId = candidate.optLong("id"),
        title = candidate.optString("title"),
        artist = candidate.optJSONObject("performer")?.optString("name")
            ?: candidate.optJSONObject("artist")?.optString("name")
            ?: candidate.optJSONObject("album")?.optJSONObject("artist")?.optString("name")
            ?: "",
        album = candidate.optJSONObject("album")?.optString("title").orEmpty(),
        durationSeconds = candidate.optInt("duration", 0),
        isrc = candidate.optString("isrc").uppercase(),
        explicit = candidate.optBoolean("parental_warning", false),
        hires = hires,
        bitDepth = bitDepth,
        sampleRate = sampleRate,
        method = method,
        confidence = confidence,
    )
}

fun selectQobuzMatch(
    targetTitle: String,
    targetArtists: List<String>,
    targetAlbum: String,
    targetDurationMs: Long,
    targetIsrc: String,
    targetExplicit: Boolean,
    candidates: List<JSONObject>,
    method: String = "search",
): QobuzMatch? {
    val targetDurationSec = targetDurationMs / 1000.0

    // 1. If searching by ISRC
    if (method == "isrc" && targetIsrc.isNotBlank()) {
        val isrcMatches = candidates.filter {
            it.optString("isrc").equals(targetIsrc, ignoreCase = true)
        }
        if (isrcMatches.isNotEmpty()) {
            val best = isrcMatches.maxByOrNull { candidateQualityScore(it) } ?: isrcMatches.first()
            return toQobuzMatch(best, "isrc", 1.0)
        }
    }

    // 2. Exact Title + Artist metadata match
    val normTargetTitle = normalizedQobuzText(targetTitle)
    val normTargetArtists = targetArtists.map(::normalizedQobuzText).filter(String::isNotBlank)

    val validCandidates = candidates.filter { candidate ->
        if (candidate.optBoolean("streamable", true) == false) return@filter false
        if (!sameVersion(targetTitle, targetAlbum, targetExplicit, candidate)) return@filter false

        val candTitle = normalizedQobuzText(candidate.optString("title"))
        if (candTitle != normTargetTitle) return@filter false

        val candArtists = candidateArtists(candidate)
        val artistMatches = normTargetArtists.isEmpty() || candArtists.any { cand ->
            normTargetArtists.any { targ -> cand.contains(targ) || targ.contains(cand) }
        }
        if (!artistMatches) return@filter false

        val candDuration = candidate.optInt("duration", 0).toDouble()
        if (targetDurationSec > 0 && candDuration > 0 && abs(targetDurationSec - candDuration) > 4.0) {
            return@filter false
        }
        true
    }

    if (validCandidates.isNotEmpty()) {
        val best = validCandidates.maxByOrNull { candidateQualityScore(it) } ?: validCandidates.first()
        return toQobuzMatch(best, method, 0.95)
    }

    // 3. Album-title fallback if provided
    if (targetAlbum.isNotBlank()) {
        val normTargetAlbum = normalizedQobuzText(targetAlbum)
        val albumCandidates = candidates.filter { candidate ->
            if (candidate.optBoolean("streamable", true) == false) return@filter false
            if (!sameVersion(targetTitle, targetAlbum, targetExplicit, candidate)) return@filter false
            val candAlbum = normalizedQobuzText(candidate.optJSONObject("album")?.optString("title"))
            if (candAlbum != normTargetAlbum) return@filter false
            val candTitle = normalizedQobuzText(candidate.optString("title"))
            if (candTitle != normTargetTitle) return@filter false
            val candDuration = candidate.optInt("duration", 0).toDouble()
            if (targetDurationSec > 0 && candDuration > 0 && abs(targetDurationSec - candDuration) > 10.0) {
                return@filter false
            }
            true
        }
        if (albumCandidates.isNotEmpty()) {
            val best = albumCandidates.maxByOrNull { candidateQualityScore(it) } ?: albumCandidates.first()
            return toQobuzMatch(best, "album-title", 0.90)
        }
    }

    return null
}

class QobuzMatcher(
    private val client: QobuzClient,
    private val cacheTtlMs: Long = 60 * 60_000L,
) {
    private data class CachedMatch(val match: QobuzMatch?, val expiresAtMs: Long)
    private val cache = ConcurrentHashMap<String, CachedMatch>()

    suspend fun match(
        title: String,
        artists: List<String>,
        album: String = "",
        durationMs: Long = 0,
        isrc: String = "",
        explicit: Boolean = false,
    ): QobuzMatch? {
        val cacheKey = "$isrc:${title.lowercase()}:${artists.firstOrNull()?.lowercase()}"
        val cached = cache[cacheKey]
        if (cached != null && cached.expiresAtMs > System.currentTimeMillis()) {
            return cached.match
        }

        val primaryArtist = artists.firstOrNull().orEmpty()
        val query = listOf(primaryArtist, title).filter(String::isNotBlank).joinToString(" ")
        if (query.isBlank()) return null

        val result = runCatching { client.search(query) }.getOrNull()
        val itemsArray = result?.optJSONObject("tracks")?.optJSONArray("items")
        val candidates = mutableListOf<JSONObject>()
        if (itemsArray != null) {
            for (i in 0 until itemsArray.length()) {
                itemsArray.optJSONObject(i)?.let { candidates.add(it) }
            }
        }

        val match = selectQobuzMatch(
            targetTitle = title,
            targetArtists = artists,
            targetAlbum = album,
            targetDurationMs = durationMs,
            targetIsrc = isrc,
            targetExplicit = explicit,
            candidates = candidates,
            method = "search",
        )

        cache[cacheKey] = CachedMatch(match, System.currentTimeMillis() + cacheTtlMs)
        return match
    }

    fun clear() {
        cache.clear()
    }
}
