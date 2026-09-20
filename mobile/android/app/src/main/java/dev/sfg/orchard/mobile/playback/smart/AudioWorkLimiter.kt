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
package dev.sfg.orchard.mobile.playback.smart

import java.util.concurrent.locks.ReentrantLock

/**
 * One decoded-audio analysis job at a time. The permit covers the entire job, including structural
 * decoding and native PCM conversion, not just model inference. Callbacks run after releasing it;
 * playback itself never takes this lock.
 */
internal object AudioWorkLimiter {
    private val lock = ReentrantLock(true)

    fun <T> run(work: () -> T): T {
        lock.lockInterruptibly()
        return try { work() } finally { lock.unlock() }
    }
}
