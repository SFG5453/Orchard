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

enum class AnimatedArtworkQuality(val label: String, val description: String) {
    AUTOMATIC("Automatic", "Adjust quality to your connection speed"),
    DATA_SAVER("Data saver", "Smaller motion covers with less data use"),
    BALANCED("Balanced", "Sharper motion covers with moderate data use"),
    HIGHEST("Highest", "Best available quality; may buffer on slow connections");

    companion object {
        fun fromStoredValue(value: String?): AnimatedArtworkQuality =
            entries.firstOrNull { it.name == value } ?: AUTOMATIC
    }
}

internal fun OrchardSettings.artworkQuality(isMetered: Boolean): AnimatedArtworkQuality =
    if (isMetered) cellularAnimatedArtworkQuality else animatedArtworkQuality
