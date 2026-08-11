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
    fun authorizationSignsTimestampCookieAndOrigin() {
        val authorization = YouTubeSessionAuth.authorization(
            cookieHeader = "SID=ignored; SAPISID=cookie-value",
            epochSeconds = 1_700_000_000,
        )

        assertEquals(
            "SAPISIDHASH 1700000000_a512ef68af31da56e69b30f14e2d4a443b11b755",
            authorization,
        )
    }

    @Test
    fun authorizationIncludesSecureCookieSchemes() {
        val authorization = YouTubeSessionAuth.authorization(
            cookieHeader = "__Secure-1PAPISID=one; __Secure-3PAPISID=three",
            epochSeconds = 1_700_000_000,
        ).orEmpty()

        assertEquals(3, authorization.split(' ').count { it.endsWith("HASH") })
        assert(authorization.startsWith("SAPISIDHASH "))
        assert(authorization.contains(" SAPISID1PHASH "))
        assert(authorization.contains(" SAPISID3PHASH "))
    }

    @Test
    fun dataSyncIdNormalizesDelegationAndPercentEscapes() {
        assertEquals("channel-id", YouTubeSessionAuth.normalizeDataSyncId("account-id%7C%7Cchannel-id"))
        assertEquals("account-id", YouTubeSessionAuth.normalizeDataSyncId("account-id||"))
        assertEquals("", YouTubeSessionAuth.normalizeDataSyncId("null"))
    }
}
