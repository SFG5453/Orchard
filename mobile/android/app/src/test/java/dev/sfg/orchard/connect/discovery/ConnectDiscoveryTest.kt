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

package dev.sfg.orchard.connect.discovery

import org.junit.Assert.assertEquals
import org.junit.Test

/** Protocol URL fixtures copied from the desktop pairing URL construction. */
class ConnectDiscoveryTest {
    @Test
    fun parsesDesktopAppPairingLink() {
        val input = ConnectDiscovery.parsePairingInput(
            "orchard-connect://pair?server=http%3A%2F%2F192.168.1.8%3A32145&token=abc-123"
        )

        assertEquals("http://192.168.1.8:32145", input.serverUrl)
        assertEquals("abc-123", input.token)
    }

    @Test
    fun stripsPathsFromManualDesktopUrl() {
        val input = ConnectDiscovery.parsePairingInput("http://desktop.local:32145/connect?token=pair")

        assertEquals("http://desktop.local:32145", input.serverUrl)
        assertEquals("pair", input.token)
    }

    @Test
    fun rejectsNonHttpEndpoint() {
        assertEquals("", ConnectDiscovery.cleanServerUrl("file:///tmp/orchard"))
        assertEquals("", ConnectDiscovery.cleanServerUrl("javascript:alert(1)"))
    }
}
