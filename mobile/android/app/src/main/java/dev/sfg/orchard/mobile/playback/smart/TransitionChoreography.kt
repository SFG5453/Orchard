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

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.math.sin

const val CHOREOGRAPHY_SCHEMA_VERSION = 1

enum class ChoreographyStrategy(val id: String) {
    STAGED_BLEND("staged_blend"),
    FILTERED_HANDOFF("filtered_handoff"),
    CLEAN_CUT("clean_cut"),
    SILENCE_TRIM("silence_trim");

    companion object {
        fun fromId(id: String): ChoreographyStrategy? =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

enum class CurveInterpolation(val id: String) {
    LINEAR("linear"),
    SMOOTH_STEP("smooth_step"),
    EQUAL_POWER_IN("equal_power_in"),
    EQUAL_POWER_OUT("equal_power_out"),
    LOGARITHMIC("logarithmic");

    companion object {
        fun fromId(id: String): CurveInterpolation? =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
    }
}

data class AutomationPoint(
    val position: Double,
    val value: Double,
    val interpolation: CurveInterpolation = CurveInterpolation.LINEAR,
)

data class AutomationCurves(
    val outgoingGain: List<AutomationPoint> = emptyList(),
    val incomingGain: List<AutomationPoint> = emptyList(),
    val outgoingLowPass: List<AutomationPoint> = emptyList(),
    val outgoingBass: List<AutomationPoint> = emptyList(),
    val incomingBass: List<AutomationPoint> = emptyList(),
)

data class OutgoingChoreography(
    val start: Double,
    val end: Double,
    val tempoRatio: Double = 1.0,
)

data class IncomingChoreography(
    val cue: Double,
    val arrival: Double,
    val resume: Double,
    val tempoRatio: Double = 1.0,
)

data class ChoreographyValidationResult(
    val isValid: Boolean,
    val errors: List<String>,
)

data class TransitionChoreography(
    val schemaVersion: Int = CHOREOGRAPHY_SCHEMA_VERSION,
    val strategy: ChoreographyStrategy = ChoreographyStrategy.STAGED_BLEND,
    val outgoing: OutgoingChoreography = OutgoingChoreography(0.0, 0.0, 1.0),
    val incoming: IncomingChoreography = IncomingChoreography(0.0, 0.0, 0.0, 1.0),
    val duration: Double = 0.0,
    val dominancePoint: Double? = 0.5,
    val curves: AutomationCurves = AutomationCurves(),
    val bassSwapPoint: Double? = null,
    val confidence: Double = 1.0,
    val diagnostics: Map<String, Any?>? = null,
    val fallback: TransitionChoreography? = null,
) {
    fun validate(isFallback: Boolean = false): ChoreographyValidationResult {
        val errors = mutableListOf<String>()

        if (schemaVersion != CHOREOGRAPHY_SCHEMA_VERSION) {
            errors.add("Unsupported schemaVersion '$schemaVersion', expected $CHOREOGRAPHY_SCHEMA_VERSION.")
        }

        if (!outgoing.start.isFinite() || outgoing.start < 0) {
            errors.add("Invalid outgoing start: ${outgoing.start}")
        }
        if (!outgoing.end.isFinite() || outgoing.end < 0) {
            errors.add("Invalid outgoing end: ${outgoing.end}")
        }
        if (!outgoing.tempoRatio.isFinite() || outgoing.tempoRatio <= 0) {
            errors.add("Invalid outgoing tempoRatio: ${outgoing.tempoRatio}")
        }
        if (outgoing.start.isFinite() && outgoing.end.isFinite() && outgoing.end < outgoing.start - 1e-6) {
            errors.add("Outgoing end (${outgoing.end}) is before start (${outgoing.start}).")
        }

        if (!incoming.cue.isFinite() || incoming.cue < 0) {
            errors.add("Invalid incoming cue: ${incoming.cue}")
        }
        if (!incoming.arrival.isFinite() || incoming.arrival < 0) {
            errors.add("Invalid incoming arrival: ${incoming.arrival}")
        }
        if (!incoming.resume.isFinite() || incoming.resume < 0) {
            errors.add("Invalid incoming resume: ${incoming.resume}")
        }
        if (!incoming.tempoRatio.isFinite() || incoming.tempoRatio <= 0) {
            errors.add("Invalid incoming tempoRatio: ${incoming.tempoRatio}")
        }
        if (incoming.cue.isFinite() && incoming.arrival.isFinite() && incoming.arrival < incoming.cue - 1e-6) {
            errors.add("Incoming arrival (${incoming.arrival}) is before cue (${incoming.cue}).")
        }
        if (incoming.cue.isFinite() && incoming.resume.isFinite() && incoming.resume < incoming.cue - 1e-6) {
            errors.add("Incoming resume (${incoming.resume}) is before cue (${incoming.cue}).")
        }

        if (!duration.isFinite() || duration < 0) {
            errors.add("Invalid duration: $duration")
        }

        if (duration.isFinite() && outgoing.start.isFinite() && outgoing.end.isFinite()) {
            val outgoingConsumed = outgoing.end - outgoing.start
            val expectedOutDuration = duration * outgoing.tempoRatio
            if (abs(outgoingConsumed - expectedOutDuration) > 1e-3) {
                errors.add("Duration $duration with tempoRatio ${outgoing.tempoRatio} inconsistent with outgoing consumption ($outgoingConsumed vs $expectedOutDuration).")
            }
            val incomingConsumed = incoming.resume - incoming.cue
            val expectedInDuration = duration * incoming.tempoRatio
            if (abs(incomingConsumed - expectedInDuration) > 1e-3) {
                errors.add("Duration $duration with tempoRatio ${incoming.tempoRatio} inconsistent with incoming consumption ($incomingConsumed vs $expectedInDuration).")
            }
        }

        dominancePoint?.let {
            if (!it.isFinite() || it < 0 || it > 1) {
                errors.add("Invalid dominancePoint: $it, must be in [0, 1].")
            }
        }

        bassSwapPoint?.let {
            if (!it.isFinite() || it < 0 || it > 1) {
                errors.add("Invalid bassSwapPoint: $it, must be in [0, 1].")
            }
        }

        validateCurveList(curves.outgoingGain, "outgoingGain", errors)
        validateCurveList(curves.incomingGain, "incomingGain", errors)
        validateCurveList(curves.outgoingLowPass, "outgoingLowPass", errors)
        validateCurveList(curves.outgoingBass, "outgoingBass", errors)
        validateCurveList(curves.incomingBass, "incomingBass", errors)

        if (duration > 0 && curves.outgoingBass.isNotEmpty() && curves.incomingBass.isNotEmpty()) {
            var dualActiveCount = 0
            val sampleSteps = 20
            for (i in 0..sampleSteps) {
                val t = i.toDouble() / sampleSteps
                val outBass = evaluateAutomationCurve(curves.outgoingBass, t)
                val inBass = evaluateAutomationCurve(curves.incomingBass, t)
                if (outBass > 0.8 && inBass > 0.8) {
                    dualActiveCount++
                }
            }
            val dualActiveFraction = dualActiveCount.toDouble() / (sampleSteps + 1)
            if (dualActiveFraction > 0.25) {
                errors.add("Bass ownership leaves both decks active simultaneously for ${(dualActiveFraction * 100)}% of the transition.")
            }
        }

        if (!isFallback && fallback != null) {
            val fallbackRes = fallback.validate(isFallback = true)
            if (!fallbackRes.isValid) {
                errors.addAll(fallbackRes.errors.map { "Fallback: $it" })
            }
        }

        return ChoreographyValidationResult(errors.isEmpty(), errors)
    }

    private fun validateCurveList(curve: List<AutomationPoint>, name: String, errors: MutableList<String>) {
        var prevPos = Double.NEGATIVE_INFINITY
        for ((index, pt) in curve.withIndex()) {
            if (!pt.position.isFinite() || pt.position < 0 || pt.position > 1) {
                errors.add("Point $index in curve '$name' position ${pt.position} is out of range [0, 1].")
            }
            if (pt.position < prevPos - 1e-9) {
                errors.add("Point $index in curve '$name' position ${pt.position} is not sorted (previous $prevPos).")
            }
            prevPos = pt.position
            if (!pt.value.isFinite()) {
                errors.add("Point $index in curve '$name' value is non-finite.")
            }
        }
    }
}

fun evaluateAutomationCurve(points: List<AutomationPoint>, position: Double): Double {
    if (points.isEmpty()) return 1.0
    val t = position.coerceIn(0.0, 1.0)
    if (points.size == 1) return points[0].value

    if (t <= points.first().position) return points.first().value
    if (t >= points.last().position) return points.last().value

    for (i in 0 until points.size - 1) {
        val p0 = points[i]
        val p1 = points[i + 1]
        if (t >= p0.position && t <= p1.position) {
            val span = p1.position - p0.position
            if (span <= 1e-9) return p1.value
            val progress = (t - p0.position) / span
            val factor = when (p0.interpolation) {
                CurveInterpolation.LINEAR -> progress
                CurveInterpolation.SMOOTH_STEP -> progress * progress * (3 - 2 * progress)
                CurveInterpolation.EQUAL_POWER_IN -> sin(progress * PI * 0.5)
                CurveInterpolation.EQUAL_POWER_OUT -> 1.0 - cos(progress * PI * 0.5)
                CurveInterpolation.LOGARITHMIC -> {
                    val v0 = max(1e-4, p0.value)
                    val v1 = max(1e-4, p1.value)
                    return exp(ln(v0) + (ln(v1) - ln(v0)) * progress)
                }
            }
            return p0.value + (p1.value - p0.value) * factor
        }
    }
    return points.last().value
}
