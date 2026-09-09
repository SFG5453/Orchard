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

import android.os.Build
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pass -e requireQnnNpu true on a known HTP device to make capability regressions fail. */
class QnnNpuDetectorDeviceTest {
    @Test
    fun reportQnnNpuCapability() {
        val result = QnnNpuDetector.detect()
        val report = "model=${Build.MODEL} status=${result.status} detail=${result.detail}"
        Log.i(TAG, report)
        println("$TAG $report")

        if (InstrumentationRegistry.getArguments().getString("requireQnnNpu") == "true") {
            assertTrue(report, result.isAvailable)
        }

        if (Build.SUPPORTED_ABIS.firstOrNull() != "arm64-v8a") {
            assertFalse("A non-ARM64 process cannot use QNN HTP", result.isAvailable)
        }
    }

    companion object {
        private const val TAG = "OrchardQnnDetector"
    }
}
