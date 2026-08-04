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

package dev.sfg.orchard.mobile.artwork

private val widthHeight = Regex("=w\\d+-h\\d+([^?]*)$")
private val squareSize = Regex("=s\\d+([^?]*)$")
private val templateWidthHeight = Regex("\\{w\\}x\\{h\\}")
private val dimensionSuffix = Regex("\\d+x\\d+([a-z]{2}\\.)")

/** Requests a display-sized source instead of the tiny thumbnail embedded in list renderers. */
internal fun highResolutionArtworkUrl(url: String, pixels: Int = 540): String = when {
    url.isBlank() -> ""
    templateWidthHeight.containsMatchIn(url) -> url.replace(templateWidthHeight, "${pixels}x${pixels}")
    dimensionSuffix.containsMatchIn(url) -> url.replace(dimensionSuffix, "${pixels}x${pixels}\$1")
    widthHeight.containsMatchIn(url) -> url.replace(widthHeight, "=w$pixels-h$pixels\$1")
    squareSize.containsMatchIn(url) -> url.replace(squareSize, "=s$pixels\$1")
    else -> url
}


