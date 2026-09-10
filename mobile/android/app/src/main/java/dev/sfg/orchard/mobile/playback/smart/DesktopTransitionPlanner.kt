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
import org.json.JSONArray
import org.json.JSONObject
import org.mozilla.javascript.Context
import org.mozilla.javascript.Function
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/** Runs the bundled desktop sources in an isolated, interpreter-only JS context (also on ART).
 * No Android, Java, network, or file objects are exposed to the planner. Calls are serialized;
 * its bounded pair cache is shared by playback and queue previews.
 */
internal object DesktopTransitionPlanner {
    private var scope: Scriptable? = null
    private var entry: Function? = null

    @Suppress("DEPRECATION") // Interpreter mode is required on ART; JVM bytecode generation cannot run there.
    @Synchronized
    fun invoke(method: String, input: JSONObject): JSONObject = Context.enter().use { context ->
        context.languageVersion = Context.VERSION_ECMASCRIPT
        context.optimizationLevel = -1
        context.setClassShutter { false }
        if (scope == null) {
            val initialized = context.initSafeStandardObjects()
            val source = checkNotNull(javaClass.getResourceAsStream("/transition-planner.js")) {
                "Desktop transition planner resource is missing"
            }.bufferedReader().use { it.readText() }
            context.evaluateString(initialized, source, "transition-planner.js", 1, null)
            val api = ScriptableObject.getProperty(initialized, "OrchardTransitionPlanner") as Scriptable
            entry = ScriptableObject.getProperty(api, "invoke") as Function
            scope = initialized
        }
        JSONObject(Context.toString(entry!!.call(context, scope, scope, arrayOf(method, input.toString()))))
    }
}

internal fun plannerInput(
    analysis: TrackAnalysis, nextAnalysis: TrackAnalysis, duration: Double, nextDuration: Double,
): JSONObject = JSONObject()
    .put("analysis", analysis.plannerJson()).put("nextAnalysis", nextAnalysis.plannerJson())
    .put("duration", duration.orZero()).put("nextDuration", nextDuration.orZero())

internal fun Track.plannerJson(): JSONObject = JSONObject()
    .put("id", id).put("title", title).put("subtitle", artist)
    .put("artist", artist).put("album", album).put("albumId", albumId)
    .put("durationSeconds", durationMs / 1000.0)

internal fun TrackAnalysis.plannerJson(): JSONObject = JSONObject(plannerFeaturesJson).apply {
    // Re-normalize against mobile's final refined grid, rather than retaining a pre-refinement grid.
    listOf("timing", "audibleRange", "harmonic", "frames", "boundaries").forEach { remove(it) }

    put("status", status); put("trackId", trackId)
    val numbers = mapOf(
        "duration" to duration, "bpm" to bpm, "beatInterval" to beatInterval,
        "beatConfidence" to beatConfidence, "firstBeat" to firstBeat,
        "keyConfidence" to keyConfidence, "audibleStartTime" to audibleStartTime,
        "pickupTime" to pickupTime, "introEndTime" to introEndTime,
        "contentEndTime" to contentEndTime, "outroStartTime" to outroStartTime,
        "mixInTime" to mixInTime, "mixOutTime" to mixOutTime,
        "vocalProbability" to vocalProbability,
    )
    numbers.forEach { (name, value) -> put(name, value?.takeIf { it.isFinite() } ?: JSONObject.NULL) }
    // Kotlin uses zero for an unmeasured endpoint; desktop represents it as an absent field.
    if (contentEndTime <= 0.0 || !contentEndTime.isFinite()) remove("contentEndTime")
    put("key", key)
    fun numbers(values: List<Double>) = JSONArray(values.map { it.takeIf(Double::isFinite) ?: JSONObject.NULL })
    put("downbeats", numbers(downbeats)); put("phraseBoundaries", numbers(phraseBoundaries))
    put("vocalActivityMask", numbers(vocalActivityMask))
    fun energy(values: List<EnergySample>) = JSONArray(values.map {
        JSONObject().put("time", it.time.takeIf(Double::isFinite) ?: JSONObject.NULL)
            .put("energy", it.energy.takeIf(Double::isFinite) ?: JSONObject.NULL)
    })
    fun candidates(values: List<MixCandidate>) = JSONArray(values.map {
        JSONObject().put("time", it.time.orZero()).put("score", it.score.orZero()).put("type", it.type)
    })
    put("energyCurve", energy(energyCurve)); put("lowEnergyCurve", energy(lowEnergyCurve))
    put("mixInCandidates", candidates(mixInCandidates)); put("mixOutCandidates", candidates(mixOutCandidates))
}

internal fun JSONObject.number(name: String, fallback: Double = 0.0) = optDouble(name, fallback)
internal fun JSONObject.strings(name: String): List<String> = optJSONArray(name)?.let { values ->
    List(values.length()) { values.getString(it) }
} ?: emptyList()

internal fun JSONObject.choreography(): TransitionChoreography {
    val out = getJSONObject("outgoing")
    val into = getJSONObject("incoming")
    val curves = getJSONObject("curves")
    fun curve(name: String): List<AutomationPoint> = curves.optJSONArray(name)?.let { values ->
        List(values.length()) { index ->
            val point = values.getJSONObject(index)
            AutomationPoint(point.getDouble("position"), point.getDouble("value"),
                requireNotNull(CurveInterpolation.fromId(point.getString("interpolation"))))
        }
    } ?: emptyList()
    return TransitionChoreography(
        schemaVersion = getInt("schemaVersion"),
        strategy = requireNotNull(ChoreographyStrategy.fromId(getString("strategy"))),
        outgoing = OutgoingChoreography(out.getDouble("start"), out.getDouble("end"), out.getDouble("tempoRatio")),
        incoming = IncomingChoreography(into.getDouble("cue"), into.getDouble("arrival"),
            into.getDouble("resume"), into.getDouble("tempoRatio")),
        duration = getDouble("duration"), dominancePoint = number("dominancePoint").takeUnless { isNull("dominancePoint") },
        curves = AutomationCurves(curve("outgoingGain"), curve("incomingGain"), curve("outgoingLowPass"),
            curve("outgoingBass"), curve("incomingBass")),
        bassSwapPoint = number("bassSwapPoint").takeUnless { isNull("bassSwapPoint") },
        confidence = getDouble("confidence"),
        fallback = optJSONObject("fallback")?.choreography(),
    )
}
