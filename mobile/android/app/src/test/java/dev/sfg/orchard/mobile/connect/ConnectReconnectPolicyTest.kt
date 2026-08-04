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

package dev.sfg.orchard.mobile.connect

import dev.sfg.orchard.connect.protocol.ConnectClientStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectReconnectPolicyTest {
    @Test
    fun backoffGrowsExponentiallyAndCapsAtThirtySeconds() {
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L),
            (0..6).map(ConnectReconnectPolicy::delayMs))
    }

    @Test
    fun onlyDisconnectedTransportIsRetried() {
        assertTrue(ConnectReconnectPolicy.shouldRetry(ConnectClientStatus.DISCONNECTED))
        assertFalse(ConnectReconnectPolicy.shouldRetry(ConnectClientStatus.AWAITING_APPROVAL))
        assertFalse(ConnectReconnectPolicy.shouldRetry(ConnectClientStatus.APPROVED))
        assertFalse(ConnectReconnectPolicy.shouldRetry(ConnectClientStatus.REVOKED))
    }
}
