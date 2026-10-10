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

package dev.sfg.orchard.mobile.app

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import dev.sfg.orchard.mobile.OrchardGraph
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Sign-in flows for the scrobbling services linked from Settings. */
internal class AccountLinks(
    private val graph: OrchardGraph,
    private val scope: CoroutineScope,
    private val showWarning: (String) -> Unit,
) {
    fun connectLastfm(context: Context) = scope.launch {
        runCatching { graph.lastfm.connect() }
            .onSuccess { url -> context.openUrl(url) }
            .onFailure { showWarning(it.message ?: "Could not start Last.fm connection.") }
    }

    fun completeLastfmConnection() = scope.launch {
        if (!graph.lastfm.complete()) showWarning("Approve Orchard on Last.fm, then try again.")
    }

    fun connectListenBrainz(token: String) = scope.launch {
        if (!graph.listenBrainz.connect(token)) showWarning("ListenBrainz did not accept that token.")
    }

    private fun Context.openUrl(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
