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

package dev.sfg.orchard.mobile.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YouTubeSessionAuthTest {
    @Test
    fun loginCookieUsesSupportedCookiePriority() {
        val cookie = "APISID=legacy; __Secure-3PAPISID=secure; SAPISID=preferred"

        assertEquals("preferred", YouTubeSessionAuth.loginCookieValue(cookie))
        assertNull(YouTubeSessionAuth.loginCookieValue("CONSENT=yes; VISITOR_INFO1_LIVE=guest"))
    }

    @Test
    fun dataSyncIdNormalizesDelegationAndPercentEscapes() {
        assertEquals("brand-id", YouTubeSessionAuth.normalizeDataSyncId("brand-id%7C%7Cuser-id"))
        assertEquals("", YouTubeSessionAuth.normalizeDataSyncId("user-id||"))
        assertEquals("", YouTubeSessionAuth.normalizeDataSyncId("user-id"))
        assertEquals("", YouTubeSessionAuth.normalizeDataSyncId("null"))
        assertEquals("brand-id", YouTubeSessionAuth.delegatedId("owner-id||", "brand-id"))
        assertEquals("brand-id", YouTubeSessionAuth.delegatedId("brand-id||owner-id", ""))
    }

    @Test
    fun accountIdentityIncludesChannelIndexAndLogin() {
        val current = YouTubeSession("SAPISID=owner", dataSyncId = "brand-one")
        assertEquals(true, YouTubeSessionAuth.sameAccount(current, current.copy(avatarUrl = "new")))
        assertEquals(false, YouTubeSessionAuth.sameAccount(current, current.copy(dataSyncId = "brand-two")))
        assertEquals(false, YouTubeSessionAuth.sameAccount(current, current.copy(accountIndex = 1)))
        assertEquals(false, YouTubeSessionAuth.sameAccount(current, current.copy(cookie = "SAPISID=other")))
    }

    @Test
    fun chooserIgnoresCurrentAccountAfterPageIdentityChanges() {
        val current = YouTubeSession("SAPISID=owner", dataSyncId = "brand-one")
        val chooser = current.copy(accountIndex = 1)
        assertEquals(false, YouTubeSessionAuth.selectedDifferentAccount(current, chooser, current))
        assertEquals(false, YouTubeSessionAuth.selectedDifferentAccount(current, null, chooser))
        assertEquals(true, YouTubeSessionAuth.selectedDifferentAccount(
            current, chooser, current.copy(dataSyncId = "brand-two"),
        ))
    }
}
