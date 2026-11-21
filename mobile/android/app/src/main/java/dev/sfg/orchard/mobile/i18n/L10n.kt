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

package dev.sfg.orchard.mobile.i18n

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.AnnotatedString
import org.json.JSONObject
import java.util.Locale

/**
 * Interface language. English text is the key; the Spanish catalog lives in assets/i18n/es.json and is
 * shared with desktop. Reading [spanish] inside tr() lets Compose recompose when the language changes.
 */
object L10n {
    private var catalog: Map<String, String> = emptyMap()
    private var spanish by mutableStateOf(false)

    /** "system", "en" or "es". */
    var language: String = "system"
        set(value) {
            field = value
            spanish = when (value) {
                "es" -> true
                "en" -> false
                else -> Locale.getDefault().language == "es"
            } && catalog.isNotEmpty()
        }

    fun init(context: Context) {
        catalog = runCatching {
            val json = JSONObject(context.assets.open("i18n/es.json").bufferedReader().use { it.readText() })
            buildMap { json.keys().forEach { key -> json.optString(key).takeIf { it.isNotEmpty() }?.let { put(key, it) } } }
        }.onFailure { Log.w("L10n", "Spanish catalog unavailable", it) }.getOrDefault(emptyMap())
        language = language
    }

    val isSpanish: Boolean get() = spanish

    fun translate(text: String): String = if (spanish) catalog[text] ?: text else text
}

fun tr(text: String): String = L10n.translate(text)

fun tr(text: AnnotatedString): AnnotatedString = text

/** tr() for text with Qt-style %1, %2 placeholders, matching the desktop catalog keys. */
fun trf(text: String, vararg args: Any?): String =
    args.foldIndexed(tr(text)) { index, out, arg -> out.replace("%${index + 1}", arg.toString()) }
