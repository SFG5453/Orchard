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
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import dev.sfg.orchard.mobile.connect.ConnectRepository
import dev.sfg.orchard.mobile.connect.ConnectSessionPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * The song on the Connect target this phone controls, as its own session. The app's controller
 * stays on the local session, so the phone's queue never mixes with the target's.
 *
 * The session exists only while the target has a song. An idle session still counts as active to
 * other devices, and a watch can pick it over the session that is actually playing.
 */
@UnstableApi
internal class ConnectMediaSession(
    private val service: MediaSessionService,
    connect: ConnectRepository,
    private val sessionActivity: PendingIntent,
    private val bitmapLoader: BitmapLoader,
    private val onMediaChanged: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var ready = false
    private val player = ConnectSessionPlayer(connect, scope) {
        if (ready) sync()
        onMediaChanged()
    }
    private var session: MediaSession? = null

    init {
        ready = true
        sync()
    }

    /** A target's song is showing; a paused local notification gives way to it. */
    val hasMedia: Boolean get() = player.hasMedia

    private fun sync() {
        val current = session
        if (player.hasMedia && current == null) {
            session = MediaSession.Builder(service, player)
                .setId(ID)
                .setSessionActivity(sessionActivity)
                .setBitmapLoader(bitmapLoader)
                .build()
                .also(service::addSession)
        } else if (!player.hasMedia && current != null) {
            session = null
            service.removeSession(current)
            current.release()
        }
    }

    fun release() {
        scope.cancel()
        session?.let {
            service.removeSession(it)
            it.release()
        }
        session = null
        player.release()
    }

    companion object {
        const val ID = "connect"
        private const val NOTIFICATION_ID = DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID + 1

        fun notificationId(session: MediaSession) =
            if (session.id == ID) NOTIFICATION_ID else DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID
    }
}
