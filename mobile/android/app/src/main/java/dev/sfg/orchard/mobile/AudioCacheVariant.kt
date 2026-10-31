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
import dev.sfg.orchard.mobile.model.youtubeAudioQuality

/** Separates playback cache bytes by selected provider and encoding tier. */
/** Apple Music serves one 256 kbps AAC encode whatever the quality setting says. */
internal const val APPLE_MUSIC_CACHE_VARIANT = "v1|apple|aac256"

/** The variant for one song: Apple Music songs ignore the YouTube and Qobuz settings. */
internal fun sourceCacheVariant(sourceId: String, providerVariant: () -> String): String =
    if (sourceId.startsWith("am.")) APPLE_MUSIC_CACHE_VARIANT else providerVariant()

internal fun audioCacheVariant(quality: AudioQuality, qobuzEnabled: Boolean, qobuzTier: String): String =
    if (qobuzEnabled && quality == AudioQuality.MAX) "v2|qobuz|$qobuzTier"
    else "v2|youtube|${youtubeAudioQuality(quality).name}"
