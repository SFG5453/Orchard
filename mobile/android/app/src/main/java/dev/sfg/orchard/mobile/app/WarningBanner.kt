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

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** The single warning banner; a newer warning replaces the shown one. */
class WarningBanner(private val scope: CoroutineScope) {
    private val mutableMessage = MutableStateFlow("")
    val message: StateFlow<String> = mutableMessage.asStateFlow()
    private var dismissJob: Job? = null

    /** Post a warning that auto-dismisses after [durationMs]. */
    fun show(message: String, durationMs: Long = 8_000L) {
        mutableMessage.value = message
        dismissJob?.cancel()
        dismissJob = scope.launch {
            delay(durationMs)
            mutableMessage.value = ""
        }
    }

    fun dismiss() {
        dismissJob?.cancel()
        mutableMessage.value = ""
    }
}
