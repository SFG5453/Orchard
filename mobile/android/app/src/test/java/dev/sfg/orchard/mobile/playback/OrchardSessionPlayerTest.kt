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

import androidx.media3.common.Player
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OrchardSessionPlayerTest {

    class PlayerState {
        var state: Int = Player.STATE_IDLE
        var prepared: Boolean = false
        var played: Boolean = false
        var position: Long = 0L
        var hasPrev: Boolean = false
        var hasNxt: Boolean = false
        var soughtPosition: Long? = null
        var soughtPrev: Boolean = false
        var soughtNext: Boolean = false
    }

    private fun createTestPlayer(state: PlayerState): Player {
        return Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java),
        ) { _, method, args ->
            when (method.name) {
                "getAvailableCommands" -> Player.Commands.EMPTY
                "isCommandAvailable" -> false
                "getPlaybackState" -> state.state
                "getCurrentPosition" -> state.position
                "hasPreviousMediaItem" -> state.hasPrev
                "hasNextMediaItem" -> state.hasNxt
                "getPlayWhenReady" -> state.played
                "prepare" -> {
                    state.prepared = true
                    state.state = Player.STATE_READY
                    null
                }
                "play" -> {
                    state.played = true
                    null
                }
                "seekTo" -> {
                    val pos = args[0] as Long
                    state.soughtPosition = pos
                    state.position = pos
                    null
                }
                "seekToPrevious" -> {
                    state.soughtPrev = true
                    null
                }
                "seekToPreviousMediaItem" -> {
                    state.soughtPrev = true
                    null
                }
                "seekToNext" -> {
                    state.soughtNext = true
                    null
                }
                "seekToNextMediaItem" -> {
                    state.soughtNext = true
                    null
                }
                "equals" -> false
                "hashCode" -> 0
                "toString" -> "TestPlayerProxy"
                else -> {
                    when (method.returnType) {
                        Boolean::class.javaPrimitiveType -> false
                        Int::class.javaPrimitiveType -> 0
                        Long::class.javaPrimitiveType -> 0L
                        Float::class.javaPrimitiveType -> 0f
                        Double::class.javaPrimitiveType -> 0.0
                        else -> null
                    }
                }
            }
        } as Player
    }

    @Test
    fun advertisesAllEssentialPlaybackAndSeekCommands() {
        val state = PlayerState()
        val base = createTestPlayer(state)
        val sessionPlayer = OrchardSessionPlayer(base)

        val essentialCommands = listOf(
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
            Player.COMMAND_SET_SHUFFLE_MODE,
            Player.COMMAND_SET_REPEAT_MODE,
        )

        for (command in essentialCommands) {
            assertTrue("Command $command should be available", sessionPlayer.isCommandAvailable(command))
        }
    }

    @Test
    fun playPreparesPlayerWhenIdle() {
        val state = PlayerState().apply { this.state = Player.STATE_IDLE }
        val base = createTestPlayer(state)
        val sessionPlayer = OrchardSessionPlayer(base)

        sessionPlayer.play()

        assertTrue(state.prepared)
        assertTrue(state.played)
    }

    @Test
    fun seekToPreviousRestartsTrackWhenPastFiveSeconds() {
        val state = PlayerState().apply {
            this.state = Player.STATE_READY
            this.position = 10_000L
            this.hasPrev = true
        }
        val base = createTestPlayer(state)
        val sessionPlayer = OrchardSessionPlayer(base)

        sessionPlayer.seekToPrevious()

        assertEquals(0L, state.soughtPosition)
        assertEquals(false, state.soughtPrev)
    }

    @Test
    fun seekToPreviousMovesToPreviousWhenWithinFiveSeconds() {
        val state = PlayerState().apply {
            this.state = Player.STATE_READY
            this.position = 2_000L
            this.hasPrev = true
        }
        val base = createTestPlayer(state)
        val sessionPlayer = OrchardSessionPlayer(base)

        sessionPlayer.seekToPrevious()

        assertTrue(state.soughtPrev)
    }

    @Test
    fun seekToPreviousSeeksToZeroWhenNoPreviousTrack() {
        val state = PlayerState().apply {
            this.state = Player.STATE_READY
            this.position = 2_000L
            this.hasPrev = false
        }
        val base = createTestPlayer(state)
        val sessionPlayer = OrchardSessionPlayer(base)

        sessionPlayer.seekToPrevious()

        assertEquals(0L, state.soughtPosition)
        assertEquals(false, state.soughtPrev)
    }

    @Test
    fun seekToNextAdvancesWhenNextTrackExists() {
        val state = PlayerState().apply {
            this.state = Player.STATE_IDLE
            this.hasNxt = true
        }
        val base = createTestPlayer(state)
        val sessionPlayer = OrchardSessionPlayer(base)

        sessionPlayer.seekToNext()

        assertTrue(state.prepared)
        assertTrue(state.soughtNext)
    }

    @Test
    fun seekToNextMediaItemAdvancesWhenNextTrackExists() {
        val state = PlayerState().apply {
            this.state = Player.STATE_IDLE
            this.hasNxt = true
        }
        val base = createTestPlayer(state)
        val sessionPlayer = OrchardSessionPlayer(base)

        sessionPlayer.seekToNextMediaItem()

        assertTrue(state.prepared)
        assertTrue(state.soughtNext)
    }

    @Test
    fun seekToPreviousMediaItemRestartsWhenPastFiveSeconds() {
        val state = PlayerState().apply {
            this.state = Player.STATE_READY
            this.position = 8_000L
            this.hasPrev = true
        }
        val base = createTestPlayer(state)
        val sessionPlayer = OrchardSessionPlayer(base)

        sessionPlayer.seekToPreviousMediaItem()

        assertEquals(0L, state.soughtPosition)
        assertEquals(false, state.soughtPrev)
    }

    @Test
    fun seekToPreviousMediaItemMovesToPreviousWhenWithinFiveSeconds() {
        val state = PlayerState().apply {
            this.state = Player.STATE_READY
            this.position = 1_500L
            this.hasPrev = true
        }
        val base = createTestPlayer(state)
        val sessionPlayer = OrchardSessionPlayer(base)

        sessionPlayer.seekToPreviousMediaItem()

        assertTrue(state.soughtPrev)
    }
}

