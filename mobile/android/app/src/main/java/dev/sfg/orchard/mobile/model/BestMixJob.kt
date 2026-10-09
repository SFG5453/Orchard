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

package dev.sfg.orchard.mobile.model

import androidx.compose.runtime.compositionLocalOf

/** The Best Mix run in flight. [key] names its origin so each screen can tell whether it owns the run. */
data class BestMixJob(val key: String, val status: String) {
    companion object {
        const val QUEUE_KEY = "queue"
        fun collectionKey(title: String) = "collection:$title"
    }
}

/** Survives screen changes because the launcher owns the run, not the screen that started it. */
val LocalBestMixJob = compositionLocalOf<BestMixJob?> { null }
