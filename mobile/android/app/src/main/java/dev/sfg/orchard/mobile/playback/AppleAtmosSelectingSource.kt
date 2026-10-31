/* Copyright (C) 2026 SFG545. Licensed under the GNU Affero General Public License v3 or later. */
package dev.sfg.orchard.mobile.playback

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.CompositeMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.Allocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Reads catalog traits off the playback thread and prepares the corresponding source. */
@UnstableApi
internal class AppleAtmosSelectingSource(
    private var item: MediaItem,
    private val chooseAtmos: () -> Boolean,
    private val stereo: (MediaItem) -> MediaSource,
    private val atmos: (MediaItem) -> MediaSource,
    private val atmosItem: (MediaItem) -> MediaItem,
) : CompositeMediaSource<Int>() {
    private var scope: CoroutineScope? = null
    private var child: MediaSource? = null
    private var selectedAtmos = false
    private var generation = 0

    override fun getMediaItem(): MediaItem = item

    // Metadata updates (such as embedded artwork) must not rebuild the source mid-song.
    override fun canUpdateMediaItem(mediaItem: MediaItem): Boolean =
        child?.canUpdateMediaItem(childItem(mediaItem)) ?: (mediaItem.localConfiguration == item.localConfiguration)

    override fun updateMediaItem(mediaItem: MediaItem) {
        item = mediaItem
        child?.updateMediaItem(childItem(mediaItem))
    }

    private fun childItem(mediaItem: MediaItem) = if (selectedAtmos) atmosItem(mediaItem) else mediaItem

    override fun prepareSourceInternal(mediaTransferListener: TransferListener?) {
        super.prepareSourceInternal(mediaTransferListener)
        val handler = Handler(checkNotNull(Looper.myLooper()))
        val current = ++generation
        val work = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = work
        work.launch {
            val selected = runCatching(chooseAtmos).getOrDefault(false)
            handler.post {
                if (current == generation) {
                    selectedAtmos = selected
                    val source = if (selected) atmos(atmosItem(item)) else stereo(item)
                    child = source
                    prepareChildSource(0, source)
                }
            }
        }
    }

    override fun onChildSourceInfoRefreshed(id: Int, mediaSource: MediaSource, timeline: Timeline) = refreshSourceInfo(timeline)
    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
        checkNotNull(child).createPeriod(id, allocator, startPositionUs)
    override fun releasePeriod(mediaPeriod: MediaPeriod) = checkNotNull(child).releasePeriod(mediaPeriod)

    override fun releaseSourceInternal() {
        generation++
        scope?.cancel()
        scope = null
        super.releaseSourceInternal()
        child = null
    }
}
