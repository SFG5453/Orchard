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

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.KeyEvent
import androidx.core.content.IntentCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionError
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dev.sfg.orchard.connect.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Starts or resumes, preparing first when the player was stopped. */
internal fun Player.resume() {
    if (playbackState == Player.STATE_IDLE) prepare()
    play()
}

internal fun Player.togglePlayback() = if (playWhenReady) pause() else resume()

internal fun Player.skipNext() {
    if (hasNextMediaItem()) seekToNextMediaItem()
    resume()
}

/** Restarts the song after five seconds in, as every car stereo has taught people to expect. */
internal fun Player.skipPrevious() {
    if (currentPosition <= 5_000 && hasPreviousMediaItem()) seekToPreviousMediaItem() else seekTo(0)
    resume()
}

/** Library browsing, car and Assistant requests, media keys, and Orchard's custom commands. */
@UnstableApi
internal class OrchardSessionCallback(
    private val packageName: String,
    private val browseTree: OrchardMediaLibrary,
    private val stateStore: PlaybackStateStore,
    /** Browse requests hit the network, so they are answered off the session thread. */
    private val browseScope: CoroutineScope,
    private val target: () -> Player,
    private val onSetVideoMode: (trackId: String, videoId: String, maxHeight: Int?) -> Unit,
    private val onLayoutChanged: () -> Unit,
) : MediaLibrarySession.Callback {

    override fun onGetLibraryRoot(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val rootParams = MediaLibraryService.LibraryParams.Builder()
            .setExtras(OrchardMediaLibrary.rootExtras())
            .build()
        return Futures.immediateFuture(LibraryResult.ofItem(browseTree.root(), rootParams))
    }

    override fun onGetChildren(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        parentId: String,
        page: Int,
        pageSize: Int,
        params: MediaLibraryService.LibraryParams?,
    ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
        val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
        browseScope.launch {
            val result = runCatching { browseTree.children(parentId) }
                .onFailure { Log.w(TAG, "Browse of $parentId failed", it) }
                .getOrDefault(emptyList())
            // A playlist can run to thousands of rows; hand back only the window asked for.
            val window = result.drop(page * pageSize).take(pageSize)
            future.set(LibraryResult.ofItemList(ImmutableList.copyOf(window), params))
        }
        return future
    }

    override fun onGetItem(
        session: MediaLibrarySession,
        browser: MediaSession.ControllerInfo,
        mediaId: String,
    ): ListenableFuture<LibraryResult<MediaItem>> {
        val item = browseTree.item(mediaId)
        return Futures.immediateFuture(
            if (item == null) LibraryResult.ofError(SessionError.ERROR_BAD_VALUE)
            else LibraryResult.ofItem(item, null)
        )
    }

    override fun onConnect(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
    ): MediaSession.ConnectionResult {
        // MediaLibraryServiceLegacyStub.onGetRoot returns null unless the controller holds
        // COMMAND_CODE_LIBRARY_GET_LIBRARY_ROOT, and Android Auto then spins with no error.
        val sessionCommands = MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS.buildUpon()
            .add(COMMAND_TOGGLE_SHUFFLE)
            .add(COMMAND_TOGGLE_REPEAT)
            .add(COMMAND_SET_VIDEO_MODE)
            .build()
        val playerCommands = session.player.availableCommands.buildUpon()
            .add(Player.COMMAND_PLAY_PAUSE)
            .add(Player.COMMAND_PREPARE)
            .add(Player.COMMAND_STOP)
            .add(Player.COMMAND_SEEK_TO_DEFAULT_POSITION)
            .add(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS)
            .add(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
            .add(Player.COMMAND_SEEK_TO_NEXT)
            .add(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
            .add(Player.COMMAND_SET_SHUFFLE_MODE)
            .add(Player.COMMAND_SET_REPEAT_MODE)
            .build()
        return MediaSession.ConnectionResult.AcceptedResultBuilder(session, controller)
            .setAvailableSessionCommands(sessionCommands)
            .setAvailablePlayerCommands(playerCommands)
            .build()
    }

    override fun onMediaButtonEvent(
        session: MediaSession,
        controllerInfo: MediaSession.ControllerInfo,
        intent: Intent,
    ): Boolean {
        val keyEvent = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_KEY_EVENT, KeyEvent::class.java)
        if (keyEvent == null || keyEvent.keyCode !in HANDLED_KEYS) {
            return super.onMediaButtonEvent(session, controllerInfo, intent)
        }
        // Key-up and auto-repeat are consumed so super never runs its double-tap detection.
        if (keyEvent.action == KeyEvent.ACTION_UP) return true
        if (keyEvent.action != KeyEvent.ACTION_DOWN) return super.onMediaButtonEvent(session, controllerInfo, intent)
        if (keyEvent.repeatCount > 0) return true
        val player = target()
        when (keyEvent.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> player.resume()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> player.pause()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_HEADSETHOOK -> player.togglePlayback()
            KeyEvent.KEYCODE_MEDIA_NEXT -> player.skipNext()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> player.skipPrevious()
            KeyEvent.KEYCODE_MEDIA_STOP -> player.stop()
        }
        return true
    }

    override fun onPlaybackResumption(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        isForPlayback: Boolean,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        val source = target()
        if (source.mediaItemCount > 0) {
            val queue = (0 until source.mediaItemCount).map(source::getMediaItemAt)
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(
                    queue,
                    source.currentMediaItemIndex.coerceAtLeast(0),
                    source.currentPosition.coerceAtLeast(0),
                )
            )
        }
        val restored = stateStore.load()
        if (restored.queue.isEmpty()) return Futures.immediateFailedFuture(UnsupportedOperationException())
        return Futures.immediateFuture(
            MediaSession.MediaItemsWithStartPosition(
                restored.mediaItems(),
                restored.currentIndex.coerceIn(0, restored.queue.lastIndex),
                restored.positionMs,
            )
        )
    }

    override fun onSetMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
        startIndex: Int,
        startPositionMs: Long,
    ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
        Log.d(TAG, "onSetMediaItems: ${mediaItems.size} items, startIndex=$startIndex, pos=$startPositionMs; " +
            "rawIds=${mediaItems.map { it.mediaId }}")
        // Assistant knows no media ids, so a voice request is an item carrying only a search query.
        val spoken = mediaItems.singleOrNull()?.takeIf { it.mediaId.isBlank() }?.requestMetadata?.searchQuery
        if (spoken != null) {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            browseScope.launch {
                val found = browseTree.search(spoken)
                Log.d(TAG, "onSetMediaItems: search '$spoken' -> ${found.size} tracks")
                future.set(MediaSession.MediaItemsWithStartPosition(found.map(::resolveMediaItem), 0, C.TIME_UNSET))
            }
            return future
        }
        // A car browser sends back the single row that was tapped. Orchard's own UI always sends
        // the queue it means to play, so only an outside controller gets its selection expanded.
        val expanded = if (controller.packageName != packageName && mediaItems.size == 1) {
            browseTree.queueFor(mediaItems.single().mediaId)
        } else null
        if (expanded != null) {
            val (queue, index) = expanded
            Log.d(TAG, "onSetMediaItems: expanded to ${queue.size} items at $index")
            return Futures.immediateFuture(
                MediaSession.MediaItemsWithStartPosition(queue.map(::resolveMediaItem), index, startPositionMs)
            )
        }
        val updated = mediaItems.map(::resolveMediaItem)
        Log.d(TAG, "onSetMediaItems: resolved ${updated.map { "${it.mediaId}->${it.localConfiguration?.uri}" }}")
        return Futures.immediateFuture(MediaSession.MediaItemsWithStartPosition(updated, startIndex, startPositionMs))
    }

    override fun onAddMediaItems(
        mediaSession: MediaSession,
        controller: MediaSession.ControllerInfo,
        mediaItems: MutableList<MediaItem>,
    ): ListenableFuture<MutableList<MediaItem>> {
        Log.d(TAG, "onAddMediaItems: ${mediaItems.size} items")
        return Futures.immediateFuture(mediaItems.map(::resolveMediaItem).toMutableList())
    }

    private fun resolveMediaItem(request: MediaItem): MediaItem {
        // Browsers hand back a bare media id; restore the row served so the car shows a title.
        val item = if (request.mediaMetadata.title.isNullOrBlank()) browseTree.item(request.mediaId) ?: request
            else request
        if (item.localConfiguration?.uri != null) return item
        val uri = item.requestMetadata.mediaUri
            ?: item.mediaId.takeIf { it.isNotBlank() }?.let {
                Uri.Builder().scheme("orchard").authority("stream").appendPath(it).build()
            }
            ?: return item
        return item.buildUpon()
            .setUri(uri)
            .setRequestMetadata(item.requestMetadata.buildUpon().setMediaUri(uri).build())
            .build()
    }

    override fun onCustomCommand(
        session: MediaSession,
        controller: MediaSession.ControllerInfo,
        customCommand: SessionCommand,
        args: Bundle,
    ): ListenableFuture<SessionResult> {
        val player = target()
        when (customCommand.customAction) {
            ACTION_TOGGLE_SHUFFLE -> player.shuffleModeEnabled = !player.shuffleModeEnabled
            ACTION_TOGGLE_REPEAT -> player.repeatMode = when (player.repeatMode) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                else -> Player.REPEAT_MODE_OFF
            }
            ACTION_SET_VIDEO_MODE -> onSetVideoMode(
                args.getString(VIDEO_MODE_TRACK_ID).orEmpty(),
                args.getString(VIDEO_MODE_VIDEO_ID).orEmpty(),
                args.getInt(VIDEO_MODE_MAX_HEIGHT, -1).takeIf { it >= 0 },
            )
        }
        onLayoutChanged()
        return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
    }

    companion object {
        private const val TAG = "OrchardPlayback"
        private const val ACTION_TOGGLE_SHUFFLE = "dev.sfg.orchard.ACTION_TOGGLE_SHUFFLE"
        private const val ACTION_TOGGLE_REPEAT = "dev.sfg.orchard.ACTION_TOGGLE_REPEAT"
        private const val ACTION_SET_VIDEO_MODE = "dev.sfg.orchard.ACTION_SET_VIDEO_MODE"
        internal const val VIDEO_MODE_TRACK_ID = "track_id"
        internal const val VIDEO_MODE_VIDEO_ID = "video_id"
        internal const val VIDEO_MODE_MAX_HEIGHT = "max_height"
        private val COMMAND_TOGGLE_SHUFFLE = SessionCommand(ACTION_TOGGLE_SHUFFLE, Bundle.EMPTY)
        private val COMMAND_TOGGLE_REPEAT = SessionCommand(ACTION_TOGGLE_REPEAT, Bundle.EMPTY)
        internal val COMMAND_SET_VIDEO_MODE = SessionCommand(ACTION_SET_VIDEO_MODE, Bundle.EMPTY)
        private val HANDLED_KEYS = setOf(
            KeyEvent.KEYCODE_MEDIA_PLAY,
            KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK,
            KeyEvent.KEYCODE_MEDIA_NEXT,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_MEDIA_STOP,
        )

        /** Notification, watch and car buttons for shuffle and repeat, drawn from [source]'s modes. */
        fun applyLayout(session: MediaSession, source: Player) {
            val shuffleOn = source.shuffleModeEnabled
            val shuffleButton =
                CommandButton.Builder(if (shuffleOn) CommandButton.ICON_SHUFFLE_ON else CommandButton.ICON_SHUFFLE_OFF)
                    .setCustomIconResId(if (shuffleOn) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle)
                    .setDisplayName(if (shuffleOn) "Shuffle on" else "Shuffle off")
                    .setSessionCommand(COMMAND_TOGGLE_SHUFFLE)
                    .build()
            val (repeatIcon, repeatRes, repeatTitle) = when (source.repeatMode) {
                Player.REPEAT_MODE_ONE -> Triple(CommandButton.ICON_REPEAT_ONE, R.drawable.ic_repeat_one_on, "Repeat one")
                Player.REPEAT_MODE_ALL -> Triple(CommandButton.ICON_REPEAT_ALL, R.drawable.ic_repeat_on, "Repeat all")
                else -> Triple(CommandButton.ICON_REPEAT_OFF, R.drawable.ic_repeat, "Repeat off")
            }
            val repeatButton = CommandButton.Builder(repeatIcon)
                .setCustomIconResId(repeatRes)
                .setDisplayName(repeatTitle)
                .setSessionCommand(COMMAND_TOGGLE_REPEAT)
                .build()
            val previousButton = CommandButton.Builder(CommandButton.ICON_PREVIOUS)
                .setDisplayName("Previous")
                .setPlayerCommand(Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM)
                .build()
            val nextButton = CommandButton.Builder(CommandButton.ICON_NEXT)
                .setDisplayName("Next")
                .setPlayerCommand(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)
                .build()
            session.setMediaButtonPreferences(listOf(previousButton, shuffleButton, repeatButton, nextButton))
            session.setCustomLayout(listOf(shuffleButton, repeatButton))
        }
    }
}
