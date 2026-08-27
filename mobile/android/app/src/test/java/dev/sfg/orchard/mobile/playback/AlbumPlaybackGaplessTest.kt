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

import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import dev.sfg.orchard.mobile.model.Track
import dev.sfg.orchard.mobile.playback.smart.CrossfadeMode
import dev.sfg.orchard.mobile.playback.smart.TrackAnalysis
import java.lang.reflect.Proxy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class AlbumPlaybackGaplessTest {

    private fun createMockPlayer(
        shuffle: Boolean = false,
        currentIndex: Int = 0,
        nextIndex: Int = 1,
        contextTitle: String = "Test Album",
    ): ExoPlayer {
        val metadata = MediaMetadata.Builder().setTitle(contextTitle).build()
        return Proxy.newProxyInstance(
            ExoPlayer::class.java.classLoader,
            arrayOf(ExoPlayer::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "getShuffleModeEnabled" -> shuffle
                "getCurrentMediaItemIndex" -> currentIndex
                "getNextMediaItemIndex" -> nextIndex
                "getPlaylistMetadata" -> metadata
                else -> null
            }
        } as ExoPlayer
    }

    private fun createEngine(): CrossfadeEngine {
        return CrossfadeEngine(
            handler = Handler(Looper.getMainLooper()),
            config = {
                CrossfadeEngine.Config(
                    enabled = true,
                    fadeSeconds = 6.0,
                    mode = CrossfadeMode.SMART,
                )
            },
            analysisFor = { TrackAnalysis() },
            onPlan = {},
            preparedFor = { _, _ -> null },
            filters = { null },
            onHandoff = { _, _ -> },
        )
    }

    @Test
    fun `sequential album playthrough without shuffle is recognized as album playthrough`() {
        val engine = createEngine()
        val player = createMockPlayer(
            shuffle = false,
            currentIndex = 0,
            nextIndex = 1,
            contextTitle = "Abbey Road",
        )
        val track = Track(id = "1", title = "Come Together", artist = "The Beatles", album = "Abbey Road")

        assertTrue(engine.isAlbumPlaythrough(player, track))
    }

    @Test
    fun `album with Best Mix context title is not treated as gapless album playthrough`() {
        val engine = createEngine()
        val player = createMockPlayer(
            shuffle = false,
            currentIndex = 0,
            nextIndex = 1,
            contextTitle = "Abbey Road • Best Mix",
        )
        val track = Track(id = "1", title = "Come Together", artist = "The Beatles", album = "Abbey Road")

        assertFalse(engine.isAlbumPlaythrough(player, track))
    }

    @Test
    fun `generic Best Mix context title is not treated as gapless album playthrough`() {
        val engine = createEngine()
        val player = createMockPlayer(
            shuffle = false,
            currentIndex = 0,
            nextIndex = 1,
            contextTitle = "Best Mix",
        )
        val track = Track(id = "1", title = "Come Together", artist = "The Beatles", album = "Abbey Road")

        assertFalse(engine.isAlbumPlaythrough(player, track))
    }

    @Test
    fun `shuffled album is not treated as gapless album playthrough`() {
        val engine = createEngine()
        val player = createMockPlayer(
            shuffle = true,
            currentIndex = 0,
            nextIndex = 1,
            contextTitle = "Abbey Road",
        )
        val track = Track(id = "1", title = "Come Together", artist = "The Beatles", album = "Abbey Road")

        assertFalse(engine.isAlbumPlaythrough(player, track))
    }

    @Test
    fun `non-adjacent next item in album is not treated as gapless album playthrough`() {
        val engine = createEngine()
        val player = createMockPlayer(
            shuffle = false,
            currentIndex = 0,
            nextIndex = 3,
            contextTitle = "Abbey Road",
        )
        val track = Track(id = "1", title = "Come Together", artist = "The Beatles", album = "Abbey Road")

        assertFalse(engine.isAlbumPlaythrough(player, track))
    }
}
