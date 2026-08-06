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

package dev.sfg.orchard.mobile.ui.navigation

object Routes {
    const val WELCOME = "welcome"
    const val HOME = "home"
    const val SEARCH = "search"
    const val LIBRARY = "library"
    const val SETTINGS = "settings"
    const val NOW_PLAYING = "now-playing"
    const val DEVICES = "devices"
    const val LOGIN = "login"
    const val SPOTIFY_LOGIN = "spotify-login"
    const val DOWNLOADS = "downloads"
    const val DETAIL = "detail/{id}"

    fun detail(id: String) = "detail/${android.net.Uri.encode(id)}"
}
