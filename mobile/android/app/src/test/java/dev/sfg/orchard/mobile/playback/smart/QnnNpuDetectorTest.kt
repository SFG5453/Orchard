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

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class QnnNpuDetectorTest {
    @Test
    fun `x86 emulator is rejected even if it advertises translated ARM support`() {
        val result = classifyQnnNpu("x86_64", qnnProviderAvailable = true, htpBackendError = null)

        assertFalse(result.isAvailable)
        assertTrue(result.status == QnnNpuStatus.UNSUPPORTED_ABI)
    }

    @Test
    fun `ARM64 still requires a QNN-enabled runtime`() {
        val result = classifyQnnNpu("arm64-v8a", qnnProviderAvailable = false, htpBackendError = null)

        assertFalse(result.isAvailable)
        assertTrue(result.status == QnnNpuStatus.RUNTIME_UNAVAILABLE)
    }

    @Test
    fun `runtime support without a loadable HTP backend is not NPU support`() {
        val result = classifyQnnNpu(
            "arm64-v8a",
            qnnProviderAvailable = true,
            htpBackendError = UnsatisfiedLinkError("backend missing"),
        )

        assertFalse(result.isAvailable)
        assertTrue(result.status == QnnNpuStatus.HTP_BACKEND_UNAVAILABLE)
    }

    @Test
    fun `ARM64 QNN runtime and HTP backend are available`() {
        val result = classifyQnnNpu("arm64-v8a", qnnProviderAvailable = true, htpBackendError = null)

        assertTrue(result.isAvailable)
        assertTrue(result.status == QnnNpuStatus.AVAILABLE)
    }
}
