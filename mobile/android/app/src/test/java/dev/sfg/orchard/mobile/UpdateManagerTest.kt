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

package dev.sfg.orchard.mobile

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateManagerTest {

    @Test
    fun parsesMetadataWithReleaseNotes() {
        val json = """
            {
              "version": "1.1.0",
              "codename": "Praise Perceived",
              "versionCode": 2,
              "apkUrl": "https://downloads.sfg545.dev/orchard/Orchard-1.1.0.apk",
              "sha256": "dummyhash",
              "publishedAt": "2026-08-07T00:00:00Z",
              "releaseNotes": "## Orchard Mobile 1.1.0\n- Added release notes metadata"
            }
        """.trimIndent()

        val metadata = UpdateManager.parseUpdateMetadata(json)

        assertEquals("1.1.0", metadata.version)
        assertEquals("Praise Perceived", metadata.codename)
        assertEquals(2, metadata.versionCode)
        assertEquals("https://downloads.sfg545.dev/orchard/Orchard-1.1.0.apk", metadata.apkUrl)
        assertEquals("dummyhash", metadata.sha256)
        assertEquals("## Orchard Mobile 1.1.0\n- Added release notes metadata", metadata.releaseNotes)
    }

    @Test
    fun comparesVersionsCorrectly() {
        assertEquals(1, UpdateManager.compareVersions("1.1.0", "1.0.0"))
        assertEquals(0, UpdateManager.compareVersions("1.0.0", "1.0.0"))
        assertEquals(-1, UpdateManager.compareVersions("1.0.0", "1.1.0"))
    }

    /**
     * Build-type suffixes used to make the trailing segment parse as 0, so "1.1.1-debug" read as
     * 1.1.0 and every suffixed build believed it was one release behind.
     */
    @Test
    fun ignoresBuildSuffixWhenComparing() {
        assertEquals(0, UpdateManager.compareVersions("1.1.1", "1.1.1-debug"))
        assertEquals(0, UpdateManager.compareVersions("1.1.1", "1.1.1-rc1"))
        assertEquals(1, UpdateManager.compareVersions("1.1.2", "1.1.1-debug"))
        assertEquals(-1, UpdateManager.compareVersions("1.1.0", "1.1.1-debug"))
    }
}
