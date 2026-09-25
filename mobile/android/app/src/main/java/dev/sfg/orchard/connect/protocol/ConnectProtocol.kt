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

package dev.sfg.orchard.connect.protocol

/**
 * Single source of truth for the Orchard Connect wire contract.
 *
 * These values mirror `electron/connect/orchardConnectServer.js`. Changing one requires a
 * coordinated desktop protocol change; UI code must not repeat the literal event or command names.
 */
object ConnectProtocol {
    const val PROTOCOL_VERSION = 4
    const val PREFERRED_PORT = 32145
    const val INFO_PATH = "/connect-info"
    const val PAIRING_SCHEME = "orchard-connect"
    const val PAIRING_HOST = "pair"
    const val ENGINE_IO_VERSION = 4

    object Event {
        const val HELLO = "connect:hello"
        const val COMMAND = "connect:command"
        const val SEARCH = "connect:search"
        const val LIBRARY = "connect:library"
        const val ANALYSIS = "connect:analysis"
        const val APPROVED = "connect:approved"
        const val STATE = "connect:state"
        const val DEVICE_STATE = "connect:device-state"
        const val SEARCH_RESULTS = "connect:search-results"
        const val LIBRARY_RESULTS = "connect:library-results"
        const val ANALYSIS_RESULTS = "connect:analysis-results"
        const val REJECTED = "connect:rejected"
        const val REVOKED = "connect:revoked"
    }

    object CommandType {
        const val PLAY_PAUSE = "play-pause"
        const val PLAY = "play"
        const val PAUSE = "pause"
        const val NEXT = "next"
        const val PREVIOUS = "previous"
        const val VOLUME = "volume"
        const val SEEK = "seek"
        const val AUDIO_ENGINE_PRESET = "audio-engine-preset"
        const val AUDIO_ENGINE_AUTO_EQ = "audio-engine-auto-eq"
        const val AUDIO_ENGINE_MANUAL_EQ = "audio-engine-manual-eq"
        const val PLAY_QUEUE_INDEX = "play-queue-index"
        const val REMOVE_QUEUE_INDEX = "remove-queue-index"
        const val MOVE_QUEUE_INDEX = "move-queue-index"
        const val CLEAR_UPCOMING = "clear-upcoming"
        const val PLAY_NEXT = "play-next"
        const val ADD_TO_QUEUE = "add-to-queue"
        const val TOGGLE_SHUFFLE = "toggle-shuffle"
        const val CYCLE_REPEAT = "cycle-repeat"
        const val PLAY_TRACK = "play-track"
        const val TRANSFER = "transfer"
        const val REPLACE_QUEUE = "replace-queue"
    }

    /** JSON field names used on both outbound and inbound protocol messages. */
    object Field {
        const val OK = "ok"
        const val DATA = "data"
        const val ERROR = "error"
        const val STATUS = "status"
        const val STATE = "state"
        const val PROTOCOL_VERSION = "protocolVersion"
        const val TOKEN = "token"
        const val DEVICE_TOKEN = "deviceToken"
        const val NAME = "name"
        const val TYPE = "type"
        const val VALUE = "value"
        const val FROM = "from"
        const val TO = "to"
        const val QUERY = "query"
        const val TRACK_IDS = "trackIds"
        const val REQUEST_ID = "requestId"
        const val RESULTS = "results"
        const val POSITION_SECONDS = "positionSeconds"
        const val SHUFFLE = "shuffle"
        const val REPEAT_MODE = "repeatMode"
        const val AUTOPLAY = "autoplay"
        const val QUEUE = "queue"
        const val TRACK = "track"
        const val TRACKS = "tracks"
        const val START_INDEX = "startIndex"
        const val CONTEXT_TITLE = "contextTitle"
        const val PLAY = "play"
    }
}
