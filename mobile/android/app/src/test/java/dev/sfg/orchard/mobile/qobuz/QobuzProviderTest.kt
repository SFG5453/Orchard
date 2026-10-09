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

package dev.sfg.orchard.mobile.qobuz

import dev.sfg.orchard.mobile.provider.ProviderBundle
import dev.sfg.orchard.mobile.provider.ProviderException
import dev.sfg.orchard.mobile.provider.ProviderHost
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class QobuzProviderTest {
    private val assets = File(checkNotNull(System.getProperty("orchard.providerAssets")))
    private val provider = ProviderHost(OkHttpClient(), ProviderBundle.Qobuz) { name ->
        File(assets, name).takeIf(File::isFile)?.readBytes()
    }

    @Test fun `desktop bytecode answers through the shared host`() = runBlocking {
        assertEquals(false, provider.invoke("session.set").getBoolean("connected"))
        assertEquals(true, provider.invoke("session.set", JSONObject().put("token", "t").put("userId", 7)).getBoolean("connected"))
        assertEquals(0, provider.invokeArray("runtime.warnings").length())
    }

    @Test fun `unknown methods reject and JSON is not bytes`() = runBlocking {
        try {
            provider.invoke("missing.method")
            fail()
        } catch (error: ProviderException) {
            assertTrue(error.message.orEmpty().contains("Unknown Qobuz provider method"))
        }
        try {
            provider.invokeBytes("runtime.warnings")
            fail()
        } catch (error: ProviderException) {
            assertTrue(error.message.orEmpty().contains("bytes"))
        }
    }
}
