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

import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.Player

/**
 * Decorator around the active [Player] for [androidx.media3.session.MediaSession].
 *
 * Ensures commands (seek previous/next, shuffle, repeat) remain advertised to external
 * controllers (Android system media controls, Bluetooth devices, KDE Connect / MPRIS)
 * even when the queue has a single item or is at the initial track boundary.
 */
class OrchardSessionPlayer(player: Player) : ForwardingPlayer(player) {
    override fun getAvailableCommands(): Player.Commands {
        return super.getAvailableCommands().buildUpon()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_PREPARE)
            .add(Player.COMMAND_STOP)
            .add(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_SET_SHUFFLE_MODE)
            .add(Player.COMMAND_SET_REPEAT_MODE)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .build()
    }

    override fun isCommandAvailable(command: Int): Boolean {
        return when (command) {
            Player.COMMAND_PLAY_PAUSE,
            Player.COMMAND_PREPARE,
            Player.COMMAND_STOP,
            Player.COMMAND_SEEK_TO_DEFAULT_POSITION,
            Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM,
            Player.COMMAND_SET_SHUFFLE_MODE,
            Player.COMMAND_SET_REPEAT_MODE,
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM,
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> true
            else -> super.isCommandAvailable(command)
        }
    }

    override fun play() {
        if (playbackState == Player.STATE_IDLE) {
            prepare()
        }
        super.play()
    }

    override fun seekToPrevious() {
        if (playbackState == Player.STATE_IDLE) {
            prepare()
        }
        if (currentPosition > 5_000) {
            seekTo(0)
        } else if (hasPreviousMediaItem()) {
            super.seekToPrevious()
        } else {
            seekTo(0)
        }
        if (playWhenReady) {
            play()
        }
    }

    override fun seekToPreviousMediaItem() {
        if (playbackState == Player.STATE_IDLE) {
            prepare()
        }
        if (currentPosition > 5_000) {
            seekTo(0)
        } else if (hasPreviousMediaItem()) {
            super.seekToPreviousMediaItem()
        } else {
            seekTo(0)
        }
        if (playWhenReady) {
            play()
        }
    }

    override fun seekToNext() {
        if (playbackState == Player.STATE_IDLE) {
            prepare()
        }
        if (hasNextMediaItem()) {
            super.seekToNext()
        }
        if (playWhenReady) {
            play()
        }
    }

    override fun seekToNextMediaItem() {
        if (playbackState == Player.STATE_IDLE) {
            prepare()
        }
        if (hasNextMediaItem()) {
            super.seekToNextMediaItem()
        }
        if (playWhenReady) {
            play()
        }
    }
}
