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

package dev.sfg.orchard.mobile.youtube

import dev.sfg.orchard.mobile.provider.ProviderBundle
import dev.sfg.orchard.mobile.provider.ProviderHost
import java.io.File
import okhttp3.OkHttpClient

/** The desktop YouTube provider (`providers/youtube`) in the shared QuickJS host. */
class YouTubeProvider(
    http: OkHttpClient,
    private val cacheDir: File,
    bundle: (String) -> ByteArray?,
) : ProviderHost(http, ProviderBundle.YouTube, bundle) {
    private val playerCache get() = File(cacheDir, "youtube-player-v1.json")

    override fun readPlayerCache(): ByteArray? = runCatching { playerCache.readBytes() }.getOrNull()

    override fun writePlayerCache(data: ByteArray): Boolean = runCatching {
        val staging = File(cacheDir, "youtube-player-v1.json.tmp")
        staging.writeBytes(data)
        staging.renameTo(playerCache)
    }.getOrDefault(false)
}
