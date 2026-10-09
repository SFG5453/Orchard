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
import androidx.media3.common.Player
import dev.sfg.orchard.mobile.model.CatalogJson
import dev.sfg.orchard.mobile.widget.OrchardPlayerWidgetProvider
import org.json.JSONObject

/** Carries out a home screen widget button press on the player that owns playback. */
internal fun Player.runWidgetAction(intent: Intent?) {
    when (intent?.action) {
        OrchardPlayerWidgetProvider.ACTION_TOGGLE -> togglePlayback()
        OrchardPlayerWidgetProvider.ACTION_PREVIOUS -> skipPrevious()
        OrchardPlayerWidgetProvider.ACTION_NEXT -> skipNext()
        OrchardPlayerWidgetProvider.ACTION_PLAY_RECENT ->
            intent.getStringExtra(OrchardPlayerWidgetProvider.EXTRA_TRACK_JSON)
                ?.let { json -> runCatching { CatalogJson.track(JSONObject(json)) }.getOrNull() }
                ?.let { track ->
                    setMediaItem(MediaItemMapper.toMediaItem(track))
                    prepare()
                    play()
                }
    }
}
