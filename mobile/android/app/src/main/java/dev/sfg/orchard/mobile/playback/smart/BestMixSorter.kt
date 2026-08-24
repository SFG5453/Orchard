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

package dev.sfg.orchard.mobile.playback.smart

import dev.sfg.orchard.mobile.model.Track
import kotlin.math.abs
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

object BestMixSorter {

    private val KEY_INDEX = mapOf(
        "C" to 0, "C♯" to 1, "C#" to 1, "D♭" to 1, "Db" to 1, "D" to 2,
        "D♯" to 3, "D#" to 3, "E♭" to 3, "Eb" to 3, "E" to 4, "F" to 5,
        "F♯" to 6, "F#" to 6, "G♭" to 6, "Gb" to 6, "G" to 7,
        "G♯" to 8, "G#" to 8, "A♭" to 8, "Ab" to 8, "A" to 9,
        "A♯" to 10, "A#" to 10, "B♭" to 10, "Bb" to 10, "B" to 11
    )

    private fun normalizedTempoRatio(leftBpm: Double, rightBpm: Double): Double {
        if (leftBpm <= 0 || rightBpm <= 0) return 0.0
        var ratio = rightBpm / leftBpm
        while (ratio > 1.5) ratio /= 2.0
        while (ratio < 0.67) ratio *= 2.0
        return ratio
    }

    private fun harmonicCost(left: String, right: String): Double? {
        val leftParts = left.trim().split(" ")
        val rightParts = right.trim().split(" ")
        if (leftParts.isEmpty() || rightParts.isEmpty()) return null

        val leftRoot = leftParts[0]
        val leftMode = if (leftParts.size > 1) leftParts[1].lowercase() else "major"
        val rightRoot = rightParts[0]
        val rightMode = if (rightParts.size > 1) rightParts[1].lowercase() else "major"

        val leftIndex = KEY_INDEX[leftRoot] ?: return null
        val rightIndex = KEY_INDEX[rightRoot] ?: return null

        if (leftMode != rightMode) {
            val relative = (leftMode == "major" && rightIndex == (leftIndex + 9) % 12) ||
                    (rightMode == "major" && leftIndex == (rightIndex + 9) % 12)
            if (relative) return 0.05
            if (leftIndex == rightIndex) return 0.22
            val pitchDistance = min(
                (leftIndex - rightIndex + 12) % 12,
                (rightIndex - leftIndex + 12) % 12
            )
            return min(1.0, 0.35 + pitchDistance / 10.0)
        }

        val leftCircle = (leftIndex * 7) % 12
        val rightCircle = (rightIndex * 7) % 12
        val circleDistance = min(
            (leftCircle - rightCircle + 12) % 12,
            (rightCircle - leftCircle + 12) % 12
        )

        return when (circleDistance) {
            0 -> 0.0
            1 -> 0.12
            2 -> 0.38
            else -> min(1.0, 0.55 + circleDistance * 0.09)
        }
    }

    private fun confidence(value: Double, fallback: Double): Double =
        if (value.isFinite() && value > 0) max(0.15, min(1.0, value)) else fallback

    fun transitionCost(left: TrackFeatures.Features, right: TrackFeatures.Features): Double? {
        var weightedCost = 0.0
        var totalWeight = 0.0

        val tempoRatio = normalizedTempoRatio(left.bpm, right.bpm)
        if (tempoRatio > 0.0) {
            val weight = 4.0 * sqrt(
                confidence(left.beatConfidence, 0.35) *
                        confidence(right.beatConfidence, 0.35)
            )
            val cost = min(1.5, abs(log2(tempoRatio)) / log2(1.2))
            weightedCost += cost * weight
            totalWeight += weight
        }

        val keyCost = harmonicCost(left.key, right.key)
        if (keyCost != null) {
            val weight = 2.4 * sqrt(
                confidence(left.keyConfidence, 0.35) * confidence(right.keyConfidence, 0.35)
            )
            weightedCost += keyCost * weight
            totalWeight += weight
        }

        if (left.vocalProbability >= 0 && right.vocalProbability >= 0) {
            val weight = 0.35
            val leftVocal = max(0.0, min(1.0, (left.vocalProbability - 0.5) * 2))
            val rightVocal = max(0.0, min(1.0, (right.vocalProbability - 0.5) * 2))
            val conflict = leftVocal * rightVocal
            weightedCost += conflict * weight
            totalWeight += weight
        }

        return if (totalWeight > 0) weightedCost / totalWeight else null
    }

    private data class AnalyzedTrack(
        val track: Track,
        val features: TrackFeatures.Features,
        val originalIndex: Int
    )

    private fun orderSegment(
        segment: List<AnalyzedTrack>,
        initialFeatures: TrackFeatures.Features?
    ): List<Track> {
        val remaining = segment.toMutableList()
        val ordered = mutableListOf<Track>()
        var previous = initialFeatures

        if ((previous == null || previous.bpm <= 0) && remaining.isNotEmpty()) {
            val first = remaining.removeAt(0)
            ordered.add(first.track)
            previous = first.features
        }

        while (remaining.isNotEmpty()) {
            var bestIdx = 0
            var lowestCost = Double.MAX_VALUE

            for (i in remaining.indices) {
                val candidate = remaining[i]
                val cost = if (previous != null) transitionCost(previous, candidate.features) ?: 1.0 else 0.0
                if (cost < lowestCost) {
                    lowestCost = cost
                    bestIdx = i
                }
            }

            val selected = remaining.removeAt(bestIdx)
            ordered.add(selected.track)
            previous = selected.features
        }

        return ordered
    }

    /**
     * Sorts a list of tracks for Best Mix using available audio features.
     * Tracks without analysis are kept in place without halting the process.
     */
    fun sort(tracks: List<Track>, featuresMap: Map<String, TrackFeatures.Features>): List<Track> {
        if (tracks.size <= 1) return tracks

        val output = mutableListOf<Track>()
        val segment = mutableListOf<AnalyzedTrack>()
        var previousFeatures: TrackFeatures.Features? = null

        fun flush() {
            if (segment.isNotEmpty()) {
                val sorted = orderSegment(segment, previousFeatures)
                output.addAll(sorted)
                previousFeatures = featuresMap[sorted.lastOrNull()?.id] ?: previousFeatures
                segment.clear()
            }
        }

        tracks.forEachIndexed { index, track ->
            val features = featuresMap[track.id]
            if (features != null && (features.bpm > 0 || features.key.isNotBlank())) {
                segment.add(AnalyzedTrack(track, features, index))
            } else {
                flush()
                output.add(track)
                previousFeatures = null
            }
        }

        flush()
        return output
    }

    private val localFeaturesCache = java.util.concurrent.ConcurrentHashMap<String, TrackFeatures.Features>()

    fun getCachedFeatures(trackId: String): TrackFeatures.Features? = localFeaturesCache[trackId]

    fun cacheFeatures(trackId: String, features: TrackFeatures.Features) {
        localFeaturesCache[trackId] = features
    }

    /**
     * Analyzes an on-disk audio file (e.g. a downloaded Opus/WebM track) for Best Mix.
     * Decodes the track to mono PCM at the native feature analyzer sample rate and computes
     * tempo, harmonic key, energy curve, and downbeats.
     * Results are cached in-memory for instant subsequent sorts.
     */
    fun analyzeLocalTrack(track: Track, file: java.io.File): TrackFeatures.Features? {
        localFeaturesCache[track.id]?.let { return it }
        if (!file.exists() || file.length() == 0L) return null
        val durationSeconds = (track.durationMs / 1000.0).takeIf { it > 0 } ?: (file.length() / 20_000.0)
        val decodeDuration = minOf(durationSeconds, 180.0)
        val source = FileMediaDataSource(file)
        val targetRate = TrackFeatures.sampleRate.toInt()
        val decoded = source.use { AudioDecoder.decodeRegion(it, 0.0, decodeDuration, targetRate = targetRate) }
            ?: return null
        val (pcm, _) = decoded
        val samples = if (abs(pcm.sampleRate - TrackFeatures.sampleRate) > 1.0) {
            MelSpectrogram.resample(pcm.samples, pcm.sampleRate, TrackFeatures.sampleRate) ?: pcm.samples
        } else pcm.samples
        val features = TrackFeatures.analyze(samples, durationSeconds)
        if (features != null) {
            localFeaturesCache[track.id] = features
        }
        return features
    }
}
