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
 */
@UnstableApi
internal class ConnectMediaSession(
    service: MediaSessionService,
    connect: ConnectRepository,
    sessionActivity: PendingIntent,
    bitmapLoader: BitmapLoader,
    onMediaChanged: () -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val player = ConnectSessionPlayer(connect, scope) { onMediaChanged() }
    val session: MediaSession = MediaSession.Builder(service, player)
        .setId(ID)
        .setSessionActivity(sessionActivity)
        .setBitmapLoader(bitmapLoader)
        .build()

    /** A target's song is showing; a paused local notification gives way to it. */
    val hasMedia: Boolean get() = player.hasMedia

    fun release() {
        scope.cancel()
        session.release()
        player.release()
    }

    companion object {
        const val ID = "connect"
        private const val NOTIFICATION_ID = DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID + 1

        fun notificationId(session: MediaSession) =
            if (session.id == ID) NOTIFICATION_ID else DefaultMediaNotificationProvider.DEFAULT_NOTIFICATION_ID
    }
}
