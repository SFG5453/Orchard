/*
 * Copyright (C) 2026 SFG545
 *
 * This file is part of Orchard, licensed under the GNU Affero General Public
 * License, version 3 or later. See <https://www.gnu.org/licenses/>.
 */
package dev.sfg.orchard.mobile.youtube

import java.util.Locale
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class YouTubeLanguageTest {
    @Test fun `language is sent in the session without changing the caller payload`() {
        val original = JSONObject().put("session", JSONObject().put("cookie", "test"))
        val result = withYouTubeLanguage(original, "ko")
        assertEquals("ko", result.getJSONObject("session").getString("language"))
        assertEquals("test", result.getJSONObject("session").getString("cookie"))
        assertFalse(original.getJSONObject("session").has("language"))
    }

    @Test fun `system language follows the device and explicit selection wins`() {
        assertEquals("ko", effectiveYouTubeLanguage("system", Locale.KOREA))
        assertEquals("ja", effectiveYouTubeLanguage("ja", Locale.KOREA))
        assertEquals("en", effectiveYouTubeLanguage("system", Locale.ROOT))
    }
}
