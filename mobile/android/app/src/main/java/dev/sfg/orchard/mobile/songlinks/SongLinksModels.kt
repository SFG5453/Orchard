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

package dev.sfg.orchard.mobile.songlinks

/** Share URLs are ready locally; no remote resolver is needed to fill the sheet. */
sealed interface SongShareState {
    val title: String
    val subtitle: String
    val artworkUrl: String
    val explicit: Boolean

    data class Ready(
        override val title: String,
        override val subtitle: String = "",
        override val artworkUrl: String = "",
        override val explicit: Boolean = false,
        val shareUrl: String,
        val isCollection: Boolean = false,
    ) : SongShareState

    data class Error(
        override val title: String,
        override val subtitle: String = "",
        override val artworkUrl: String = "",
        override val explicit: Boolean = false,
        val message: String,
    ) : SongShareState
}

/** Targets from public song.link, album.link, YouTube or Orchard links. */
sealed interface SongLinkTarget {
    data class Browse(val kind: String, val browseId: String) : SongLinkTarget
    data class Video(val videoId: String) : SongLinkTarget
}
