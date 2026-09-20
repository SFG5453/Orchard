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

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import dev.sfg.orchard.connect.R

/**
 * Provides CastOptions for Orchard, supporting a custom registered Google Cast App ID
 * or defaulting to Google's standard Default Media Receiver.
 */
class OrchardCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions {
        val configured =
            runCatching { context.getString(R.string.cast_app_id).trim() }.getOrNull()

        val appId =
            if (!configured.isNullOrBlank()) {
                configured
            } else {
                CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID
            }

        return CastOptions.Builder()
            .setReceiverApplicationId(appId)
            .setStopReceiverApplicationWhenEndingSession(true)
            .build()
    }

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider> =
        emptyList()
}
