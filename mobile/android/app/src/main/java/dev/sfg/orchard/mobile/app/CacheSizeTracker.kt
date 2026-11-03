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
import dev.sfg.orchard.mobile.OrchardGraph
import dev.sfg.orchard.mobile.settings.CacheManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Cache size shown in settings, and the one clear-cache job allowed at a time. */
class CacheSizeTracker(
    private val context: Context,
    private val graph: OrchardGraph,
    private val scope: CoroutineScope,
) {
    private val mutableSizeBytes = MutableStateFlow(0L)
    val sizeBytes: StateFlow<Long> = mutableSizeBytes.asStateFlow()

    private val mutableClearing = MutableStateFlow(false)
    val clearing: StateFlow<Boolean> = mutableClearing.asStateFlow()

    fun refresh() {
        scope.launch(Dispatchers.IO) {
            mutableSizeBytes.value = CacheManager.calculateCacheSizeBytes(context)
        }
    }

    fun clear(onComplete: ((Long) -> Unit)? = null) {
        if (mutableClearing.value) return
        scope.launch {
            mutableClearing.value = true
            val bytesCleared = withContext(Dispatchers.IO) {
                CacheManager.clearAllCache(context, graph)
            }
            mutableSizeBytes.value = withContext(Dispatchers.IO) {
                CacheManager.calculateCacheSizeBytes(context)
            }
            mutableClearing.value = false
            onComplete?.invoke(bytesCleared)
        }
    }
}
