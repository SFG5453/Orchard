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

package dev.sfg.orchard.mobile.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.common.Player
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.datasource.okhttp.OkHttpDataSource
import dev.sfg.orchard.connect.app.MainActivity
import okhttp3.OkHttpClient

internal fun mainActivityIntent(context: Context): PendingIntent = PendingIntent.getActivity(
    context,
    0,
    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
)

/**
 * The app's OkHttp stack: the default URLConnection loader fails on some provider artwork URLs,
 * and WearOS then falls back to a gray notification background.
 */
@UnstableApi
internal fun artworkBitmapLoader(context: Context, http: OkHttpClient): BitmapLoader =
    DataSourceBitmapLoader.Builder(context)
        .setDataSourceFactory(OkHttpDataSource.Factory(http))
        .setMaximumOutputDimension(1024)
        .setMakeShared(true)
        .build()

/** Resolves and caches the current and next items ahead of playback. */
@UnstableApi
internal fun prefetchAround(player: Player, streamResolver: YouTubeStreamResolver, streamCache: StreamCache,
                            skipAudio: (android.net.Uri) -> Boolean = { false },
                            warmSkipped: (android.net.Uri) -> Unit = {}) {
    val wanted = (player.currentMediaItemIndex..player.currentMediaItemIndex + 1)
        .filter { it in 0 until player.mediaItemCount }
        .map(player::getMediaItemAt)
    for (item in wanted) {
        val uri = item.localConfiguration?.uri
        // Authenticated progressive items are prefetched through StreamCache below, whose
        // DataSource invokes their signed-in resolver; a guest resolve beside it would compete.
        val authenticated = uri != null &&
            (MediaItemMapper.requiresAuthenticatedHls(uri) || MediaItemMapper.requiresAuthenticatedDirect(uri))
        val apple = dev.sfg.orchard.mobile.playback.apple.AppleMusicDataSource.isApple(uri)
        if (!authenticated && !apple && !MediaItemMapper.isVideoUri(uri)) streamResolver.prefetch(item.mediaId)
    }
    val audio = wanted.mapNotNull { it.localConfiguration?.uri }.filterNot(MediaItemMapper::isVideoUri)
    val (skipped, uris) = audio.partition(skipAudio)
    skipped.forEach(warmSkipped)
    streamCache.retainOnly(uris)
    uris.forEach(streamCache::prefetch)
}
