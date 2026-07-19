package dev.sfg.orchard.connect

import org.json.JSONArray
import java.util.Locale
import kotlin.math.floor
import kotlin.math.max

fun secondsValue(value: Any?): Double {
    if (value is Number) return max(0.0, value.toDouble())
    val text = value?.toString()?.trim().orEmpty()
    if (text.isEmpty()) return 0.0
    if (":" in text) {
        val parts = text.split(":").mapNotNull { it.toDoubleOrNull() }
        if (parts.isNotEmpty()) return parts.fold(0.0) { total, part -> total * 60 + max(0.0, part) }
    }
    return max(0.0, text.toDoubleOrNull() ?: 0.0)
}

fun formatTime(value: Double): String {
    val whole = max(0, floor(value).toInt())
    return String.format(Locale.US, "%d:%02d", whole / 60, whole % 60)
}

fun activeLyric(lines: JSONArray, currentTime: Double): Int {
    var active = -1
    for (index in 0 until lines.length()) {
        val line = lines.optJSONObject(index) ?: continue
        if (line.has("startTime") && line.optDouble("startTime") <= currentTime + 0.12) active = index
    }
    return active
}
