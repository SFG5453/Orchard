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

package dev.sfg.orchard.mobile.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.sfg.orchard.mobile.local.LocalLibraryRepository

/**
 * What song menus need to edit a file on this phone. The pickers live at the root of the app, so a
 * chosen cover or lyrics file still arrives after the bottom sheet that asked for it has closed.
 */
internal class LocalLibraryUi(
    val repository: LocalLibraryRepository,
    val pickCover: (songId: String) -> Unit,
    val pickLyrics: (songId: String) -> Unit,
)

/** Null outside the app shell (previews, tests), which simply hides the local entries. */
internal val LocalLibraryUiLocal = staticCompositionLocalOf<LocalLibraryUi?> { null }

@Composable
internal fun rememberLocalLibraryUi(repository: LocalLibraryRepository): LocalLibraryUi {
    var pendingSong by remember { mutableStateOf("") }
    val cover = rememberCoverPicker { uri -> repository.setSongCover(pendingSong, uri) }
    val lyrics = rememberLyricsPicker { uri -> repository.setSongLyrics(pendingSong, uri) }
    return remember(repository, cover, lyrics) {
        LocalLibraryUi(
            repository,
            pickCover = { id -> pendingSong = id; cover() },
            pickLyrics = { id -> pendingSong = id; lyrics() },
        )
    }
}
