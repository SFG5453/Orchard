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
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Affero General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with Orchard. If not, see <https://www.gnu.org/licenses/>.
 */

package dev.sfg.orchard.mobile.playback

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.Locale

/** HTTP identity required to fetch a signed YouTube media URL. */
data class YouTubeStreamRequestIdentity(
    val userAgent: String,
    val origin: String? = null,
    val referer: String? = null,
    val clientKey: String = "",
) {
    fun headers(): Map<String, String> = buildMap {
        put("User-Agent", userAgent)
        origin?.let { put("Origin", it) }
        referer?.let { put("Referer", it) }
    }

    companion object {
        private const val MUSIC_ORIGIN = "https://music.youtube.com"
        private const val YOUTUBE_ORIGIN = "https://www.youtube.com"

        /**
         * A googlevideo URL carries the client family in `c` and usually its version in
         * `cver`. Those values are authoritative: using the resolver's first/default user agent
         * for a URL minted by a fallback client is rejected by the CDN with HTTP 403.
         */
        fun fromUrl(url: String, fallbackUserAgent: String): YouTubeStreamRequestIdentity {
            val parsed = url.toHttpUrlOrNull()
            val client = parsed?.queryParameter("c").orEmpty().uppercase(Locale.US)
            val version = parsed?.queryParameter("cver").orEmpty()
            val userAgent = when {
                client == "WEB_REMIX" -> WEB_REMIX_USER_AGENT
                client == "WEB" || client == "WEB_CREATOR" -> WEB_USER_AGENT
                client == "MWEB" -> MWEB_USER_AGENT
                client.startsWith("TVHTML5") -> TV_USER_AGENT
                client == "IOS_MUSIC" -> IOS_MUSIC_USER_AGENT
                client.startsWith("IOS") -> if (version == "19.22.3") IPAD_USER_AGENT else IOS_USER_AGENT
                client == "ANDROID_MUSIC" -> ANDROID_MUSIC_USER_AGENT
                client == "ANDROID_TESTSUITE" -> ANDROID_TESTSUITE_USER_AGENT
                client == "ANDROID_UNPLUGGED" -> ANDROID_UNPLUGGED_USER_AGENT
                client.startsWith("ANDROID_VR") -> when (version) {
                    "1.61.48" -> ANDROID_VR_1_61_USER_AGENT
                    "1.43.32" -> ANDROID_VR_1_43_USER_AGENT
                    else -> YouTubeStreamResolver.CLIENT_USER_AGENT
                }
                client.startsWith("ANDROID") -> ANDROID_USER_AGENT
                client.startsWith("VISIONOS") -> VISION_OS_USER_AGENT
                else -> fallbackUserAgent
            }
            val (origin, referer) = when {
                client.startsWith("TVHTML5") -> YOUTUBE_ORIGIN to "$YOUTUBE_ORIGIN/tv"
                client.startsWith("WEB") || client == "MWEB" -> MUSIC_ORIGIN to "$MUSIC_ORIGIN/"
                else -> null to null
            }
            val key = listOf(client, version).filter(String::isNotBlank).joinToString("@").ifBlank {
                fallbackUserAgent
            }
            return YouTubeStreamRequestIdentity(userAgent, origin, referer, key)
        }

        const val WEB_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
        const val WEB_REMIX_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"
        const val MWEB_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 15; Pixel 9 Pro) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"
        const val TV_USER_AGENT =
            "Mozilla/5.0(SMART-TV; Linux; Tizen 4.0.0.2) AppleWebkit/605.1.15 " +
                "(KHTML, like Gecko) SamsungBrowser/9.2 TV Safari/605.1.15"
        const val IOS_USER_AGENT =
            "com.google.ios.youtube/21.26.4 (iPhone16,2; U; CPU iOS 18_3_2;)"
        const val IPAD_USER_AGENT =
            "com.google.ios.youtube/19.22.3 (iPad7,6; U; CPU iPadOS 17_7_10 like Mac OS X; en-US)"
        const val IOS_MUSIC_USER_AGENT =
            "com.google.ios.youtubemusic/7.27.0 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)"
        const val ANDROID_USER_AGENT =
            "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip"
        const val ANDROID_MUSIC_USER_AGENT =
            "com.google.android.apps.youtube.music/7.27.52 (Linux; U; Android 15; en_US; " +
                "Pixel 9 Pro; Build/AP4A.250205.002; Cronet/132.0.6834.79) gzip"
        const val ANDROID_TESTSUITE_USER_AGENT =
            "com.google.android.youtube/1.9 (Linux; U; Android 15; en_US; Pixel 9 Pro; " +
                "Build/AP4A.250205.002) gzip"
        const val ANDROID_UNPLUGGED_USER_AGENT =
            "com.google.android.apps.youtube.unplugged/8.49.0 (Linux; U; Android 15; en_US; " +
                "Pixel 9 Pro; Build/AP4A.250205.002; Cronet/132.0.6834.79) gzip"
        const val ANDROID_VR_1_61_USER_AGENT =
            "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12; en_US; " +
                "Quest 3; Build/SQ3A.220605.009.A1; Cronet/132.0.6808.3)"
        const val ANDROID_VR_1_43_USER_AGENT =
            "com.google.android.apps.youtube.vr.oculus/1.43.32 (Linux; U; Android 12; en_US; " +
                "Quest 3; Build/SQ3A.220605.009.A1; Cronet/107.0.5284.2)"
        const val VISION_OS_USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 " +
                "(KHTML, like Gecko) Version/18.0 Safari/605.1.15"
    }
}
