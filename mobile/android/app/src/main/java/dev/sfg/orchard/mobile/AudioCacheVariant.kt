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

package dev.sfg.orchard.mobile

import dev.sfg.orchard.mobile.model.AudioQuality

/** Separates playback cache bytes by selected provider and encoding tier. */
internal fun audioCacheVariant(quality: AudioQuality, qobuzEnabled: Boolean, qobuzTier: String): String =
    if (qobuzEnabled && quality == AudioQuality.MAX) "v2|qobuz|$qobuzTier"
    else "v2|youtube|${if (quality == AudioQuality.MAX) AudioQuality.HIGH.name else quality.name}"
