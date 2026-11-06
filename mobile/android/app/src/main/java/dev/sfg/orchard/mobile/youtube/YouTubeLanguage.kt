/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard, licensed under the GNU Affero General Public
 * License, version 3 or later. See <https://www.gnu.org/licenses/>.
 */
package dev.sfg.orchard.mobile.youtube

import java.util.Locale
import org.json.JSONObject

internal fun effectiveYouTubeLanguage(setting: String, system: Locale = Locale.getDefault()): String =
    (if (setting == "system") system.language else setting).ifBlank { "en" }

internal fun youTubeLanguages(): List<Pair<String, String>> = Locale.getAvailableLocales()
    .filter { it.language.isNotBlank() && it.language != "und" }
    .distinctBy { it.language }
    .map { it.language to it.getDisplayLanguage(it).replaceFirstChar { char -> char.titlecase(it) } }
    .sortedBy { it.second.lowercase(Locale.getDefault()) }

internal fun withYouTubeLanguage(payload: JSONObject, language: String): JSONObject {
    val session = payload.optJSONObject("session") ?: return payload
    return JSONObject(payload.toString()).put("session", JSONObject(session.toString()).put("language", language))
}
