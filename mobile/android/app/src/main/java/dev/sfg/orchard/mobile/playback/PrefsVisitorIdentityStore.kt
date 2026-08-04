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

import android.content.Context

/**
 * Keeps the YouTube visitor identity across process restarts.
 *
 * Without it every cold start pays a full watch-page download before the first track can be
 * resolved. The identity is anonymous and not a credential, so plain preferences are enough.
 */
class PrefsVisitorIdentityStore(context: Context) : VisitorIdentityStore {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): Pair<String, String>? {
        val id = prefs.getString(KEY_ID, null)?.takeIf(String::isNotBlank) ?: return null
        return id to prefs.getString(KEY_COOKIE, "").orEmpty()
    }

    override fun save(id: String, cookie: String) {
        prefs.edit().putString(KEY_ID, id).putString(KEY_COOKIE, cookie).apply()
    }

    private companion object {
        const val FILE = "orchard.visitor"
        const val KEY_ID = "visitorData"
        const val KEY_COOKIE = "cookie"
    }
}
