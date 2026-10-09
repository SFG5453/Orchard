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

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/** Opens the system file picker for songs. Several files can be chosen at once. */
@Composable
internal fun rememberSongPicker(onPicked: (List<Uri>) -> Unit): () -> Unit {
    val current by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { current(it) }
    return remember(launcher) { { launcher.launch(arrayOf("audio/*", "application/ogg")) } }
}

/** Opens the system folder picker; every song under the folder is imported. */
@Composable
internal fun rememberFolderPicker(onPicked: (Uri) -> Unit): () -> Unit {
    val current by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { it?.let(current) }
    return remember(launcher) { { launcher.launch(null) } }
}

/** A picture, GIF or short video for a cover. */
@Composable
internal fun rememberCoverPicker(onPicked: (Uri) -> Unit): () -> Unit {
    val current by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(current) }
    return remember(launcher) { { launcher.launch(arrayOf("image/*", "video/mp4", "video/webm", "video/*")) } }
}

/** An .lrc, .srt or .txt file. Providers disagree about their MIME types, so any file may be picked. */
@Composable
internal fun rememberLyricsPicker(onPicked: (Uri) -> Unit): () -> Unit {
    val current by rememberUpdatedState(onPicked)
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(current) }
    return remember(launcher) { { launcher.launch(arrayOf("*/*")) } }
}
