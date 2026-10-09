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

package dev.sfg.orchard.mobile.model

import androidx.compose.runtime.compositionLocalOf

/** Tier and format of a Qobuz album, as the provider's catalog reports it. */
data class QobuzAlbumQuality(val hiRes: Boolean, val bitDepth: Int, val sampleRate: Int)

/** Format of the playing stream; zero and blank mean unknown. */
data class StreamDetail(
    val hiRes: Boolean = false,
    val bitDepth: Int = 0,
    val sampleRate: Int = 0,
    val codec: String = "",
)

/** What the player badge describes about the current stream. */
val LocalStreamDetail = compositionLocalOf { StreamDetail() }

/** True while a Qobuz subscription is linked; MAX can only be chosen then. */
val LocalQobuzLinked = compositionLocalOf { false }

/** True while MAX is the effective quality, which benches adaptive mix and the equalizer. */
val LocalMaxActive = compositionLocalOf { false }

/** Looks up an album's Qobuz tier; null while MAX is off. */
val LocalQobuzAlbumQuality = compositionLocalOf<(suspend (BrowseDetail) -> QobuzAlbumQuality?)?> { null }
