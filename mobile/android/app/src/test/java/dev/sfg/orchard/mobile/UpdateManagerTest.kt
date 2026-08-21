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

    @Test
    fun comparesBetaVersionsCorrectly() {
        // Newer beta vs older beta
        assertEquals(1, UpdateManager.compareVersions("1.8.0-beta.2", "1.8.0-beta.1"))
        // Same beta
        assertEquals(0, UpdateManager.compareVersions("1.8.0-beta.1", "1.8.0-beta.1"))
        // Older beta vs newer beta
        assertEquals(-1, UpdateManager.compareVersions("1.8.0-beta.1", "1.8.0-beta.2"))
        // Stable release is newer than its beta
        assertEquals(1, UpdateManager.compareVersions("1.8.0", "1.8.0-beta.1"))
        // Beta is older than its stable release
        assertEquals(-1, UpdateManager.compareVersions("1.8.0-beta.1", "1.8.0"))
        // Older major/minor vs beta
        assertEquals(-1, UpdateManager.compareVersions("1.7.0", "1.8.0-beta.1"))
        // Newer major/minor vs beta
        assertEquals(1, UpdateManager.compareVersions("1.9.0", "1.8.0-beta.1"))
    }

    @Test
    fun parsesGitHubReleasesWithManifestAsset() {
        val releasesJson = """
            [
              {
                "tag_name": "v1.8.0-beta.1",
                "name": "Orchard Mobile 1.8.0 (beta)",
                "prerelease": true,
                "body": "Beta notes",
                "published_at": "2026-08-20T00:00:00Z",
                "assets": [
                  {
                    "name": "latest-android.json",
                    "browser_download_url": "https://github.com/downloads/latest-android.json"
                  },
                  {
                    "name": "Orchard-1.8.0-beta.1.apk",
                    "browser_download_url": "https://github.com/downloads/Orchard-1.8.0-beta.1.apk"
                  }
                ]
              }
            ]
        """.trimIndent()

        val manifestJson = """
            {
              "version": "1.8.0-beta.1",
              "codename": "Beta Codename",
              "versionCode": 10801,
              "apkUrl": "https://github.com/downloads/Orchard-1.8.0-beta.1.apk",
              "sha256": "abc123hash",
              "publishedAt": "2026-08-20T00:00:00Z",
              "releaseNotes": "## Orchard Mobile 1.8.0-beta.1\n- Bug fixes"
            }
        """.trimIndent()

        val metadata = UpdateManager.parseGitHubReleasesMetadata(
            releasesJson = releasesJson,
            fetchManifest = { manifestJson },
        )

        org.junit.Assert.assertNotNull(metadata)
        assertEquals("1.8.0-beta.1", metadata?.version)
        assertEquals("Beta Codename", metadata?.codename)
        assertEquals(10801, metadata?.versionCode)
        assertEquals("https://github.com/downloads/Orchard-1.8.0-beta.1.apk", metadata?.apkUrl)
        assertEquals("abc123hash", metadata?.sha256)
        assertEquals("## Orchard Mobile 1.8.0-beta.1\n- Bug fixes", metadata?.releaseNotes)
    }

    @Test
    fun parsesGitHubReleasesWithApkFallback() {
        val releasesJson = """
            [
              {
                "tag_name": "v1.8.0-beta.2",
                "name": "Orchard Mobile 1.8.0 (beta)",
                "prerelease": true,
                "body": "Fallback beta notes",
                "published_at": "2026-08-21T00:00:00Z",
                "assets": [
                  {
                    "name": "Orchard-1.8.0-beta.2.apk",
                    "browser_download_url": "https://github.com/downloads/Orchard-1.8.0-beta.2.apk"
                  }
                ]
              }
            ]
        """.trimIndent()

        val metadata = UpdateManager.parseGitHubReleasesMetadata(
            releasesJson = releasesJson,
            fetchManifest = { null },
        )

        org.junit.Assert.assertNotNull(metadata)
        assertEquals("1.8.0-beta.2", metadata?.version)
        assertEquals("https://github.com/downloads/Orchard-1.8.0-beta.2.apk", metadata?.apkUrl)
        assertEquals("Fallback beta notes", metadata?.releaseNotes)
        assertEquals("2026-08-21T00:00:00Z", metadata?.publishedAt)
    }

    @Test
    fun parsesGitHubReleasesReturnsNullWhenNoAndroidAssets() {
        val releasesJson = """
            [
              {
                "tag_name": "v1.8.0-beta.1",
                "name": "Orchard Desktop Only (beta)",
                "prerelease": true,
                "body": "Desktop only release",
                "published_at": "2026-08-20T00:00:00Z",
                "assets": [
                  {
                    "name": "Orchard-Setup-1.8.0-beta.1.exe",
                    "browser_download_url": "https://github.com/downloads/Orchard-Setup-1.8.0-beta.1.exe"
                  }
                ]
              }
            ]
        """.trimIndent()

        val metadata = UpdateManager.parseGitHubReleasesMetadata(
            releasesJson = releasesJson,
            fetchManifest = { null },
        )

        org.junit.Assert.assertNull(metadata)
    }
}
