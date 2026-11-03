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
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.SessionError
import dev.sfg.orchard.mobile.OrchardGraph
import dev.sfg.orchard.mobile.catalog.isAppleMusicId
import dev.sfg.orchard.mobile.model.APPLE_MUSIC_SOURCE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/** A browse that needs Apple Music connected first; the car shows [message] with a button to the app. */
internal class SignInRequired(message: String) : Exception(message)

/** Extras that give a car error screen a button opening the app. */
internal fun resolutionExtras(sessionActivity: PendingIntent): Bundle = Bundle().apply {
    putString(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_LABEL_COMPAT, "Open Orchard")
    putParcelable(MediaConstants.EXTRAS_KEY_ERROR_RESOLUTION_ACTION_INTENT_COMPAT, sessionActivity)
}

/**
 * Reloads car lists when the library behind them changes, and redraws the like button. Browsers
 * only hear about lists they subscribed to, so unopened ones cost nothing.
 */
@UnstableApi
internal fun CoroutineScope.notifyLibraryChanges(
    graph: OrchardGraph,
    browseTree: OrchardMediaLibrary,
    session: () -> MediaLibrarySession,
    onLibraryChanged: () -> Unit,
) = launch(Dispatchers.Main) {
    combine(graph.settings.settings, graph.library.library, graph.appleLibrary.library) { _, _, _ -> }
        .collect {
            val shown = browseTree.shownLibrary()
            val target = session()
            if (graph.settings.settings.value.homeSource != APPLE_MUSIC_SOURCE || graph.appleLibrary.active) {
                target.clearReplicatedLibraryError()
            }
            target.notifyChildrenChanged(OrchardMediaLibrary.ROOT, OrchardMediaLibrary.ROOT_CHILDREN, null)
            target.notifyChildrenChanged(OrchardMediaLibrary.LIKED, shown.likedTracks.size, null)
            target.notifyChildrenChanged(OrchardMediaLibrary.PLAYLISTS, shown.savedPlaylists.size, null)
            target.notifyChildrenChanged(OrchardMediaLibrary.RECENT, shown.recentlyPlayed.size, null)
            onLibraryChanged()
        }
}

/** Whether [item] is liked, or null when it cannot be liked (songs kept only on this phone). */
internal fun likedState(graph: OrchardGraph, item: MediaItem?): Boolean? {
    val track = item?.let(MediaItemMapper::toTrack)?.takeUnless { it.isLocal || it.id.isBlank() } ?: return null
    return graph.likes.isLiked(track)
}

/** Likes or unlikes the playing song for the car and notification button. */
@UnstableApi
internal fun CoroutineScope.toggleLike(graph: OrchardGraph, item: MediaItem?, errors: CarPlaybackErrors) {
    val track = item?.let(MediaItemMapper::toTrack)?.takeUnless { it.isLocal || it.id.isBlank() } ?: return
    launch {
        runCatching { graph.likes.toggle(track) }.onFailure {
            val message = it.message ?: graph.likes.failureMessage()
            graph.postWarning(message)
            launch(Dispatchers.Main) { errors.notify(message) }
        }
    }
}

/**
 * Gives controllers the readable reason a song failed, with a sign-in button when Apple Music is
 * the cause. The override holds until the player recovers.
 */
@UnstableApi
internal class CarPlaybackErrors(
    private val graph: OrchardGraph,
    private val sessionActivity: PendingIntent,
    private val session: () -> MediaLibrarySession,
) {
    private var overridden = false

    fun onPlayerError(player: Player, error: PlaybackException) {
        val apple = player.currentMediaItem?.mediaId?.let(::isAppleMusicId) == true
        val code = when {
            !apple -> error.errorCode
            graph.settings.settings.value.homeSource != APPLE_MUSIC_SOURCE -> PlaybackException.ERROR_CODE_SETUP_REQUIRED
            !graph.appleMusic.signedIn() -> PlaybackException.ERROR_CODE_AUTHENTICATION_EXPIRED
            else -> error.errorCode
        }
        val extras = if (code != error.errorCode) resolutionExtras(sessionActivity) else Bundle.EMPTY
        session().setPlaybackException(PlaybackException(playbackErrorMessage(error), error, code, extras))
        overridden = true
    }

    fun onEvents(player: Player) {
        if (!overridden || player.playerError != null) return
        session().setPlaybackException(null)
        overridden = false
    }

    /** A passing message for the car, which shows none of the phone's warnings. */
    fun notify(message: String) = session().sendError(SessionError(SessionError.ERROR_UNKNOWN, message))
}
