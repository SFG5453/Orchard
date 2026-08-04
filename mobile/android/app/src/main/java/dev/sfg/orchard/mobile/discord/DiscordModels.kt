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

package dev.sfg.orchard.mobile.discord

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

const val DISCORD_APPLICATION_ID = "1531666622312353803"
const val DISCORD_OAUTH_SCOPES = "openid identify sdk.social_layer_presence"
const val DISCORD_ARTWORK_PROXY_ORIGIN = "https://artwork-proxy.sfg545.dev"
const val DISCORD_ARTWORK_VERSION = "7"
const val DISCORD_SONGLINKS_ORIGIN = "https://songlinks.sfg545.dev"
const val DISCORD_ORCHARD_PROJECT_URL = "https://sfg545.dev/orchard"
const val DISCORD_REDIRECT_URI = "orchard://discord/callback"
const val DISCORD_GATEWAY_URL = "wss://gateway.discord.gg/?v=9&encoding=json"

data class DiscordAccount(
    val id: String,
    val username: String,
    val globalName: String? = null,
    val avatar: String? = null,
    val discriminator: String? = null,
) {
    val displayName: String get() = globalName?.takeIf(String::isNotBlank) ?: username

    val avatarUrl: String?
        get() = when {
            avatar.isNullOrBlank() -> null
            avatar.startsWith("a_") -> "https://cdn.discordapp.com/avatars/$id/$avatar.gif?size=128"
            else -> "https://cdn.discordapp.com/avatars/$id/$avatar.png?size=128"
        }
}

data class DiscordAuthSession(
    val accessToken: String,
    val refreshToken: String,
    val expiresAtEpochMs: Long,
    val account: DiscordAccount? = null,
) {
    val isExpired: Boolean get() = System.currentTimeMillis() >= (expiresAtEpochMs - 60_000)
}

sealed interface DiscordAuthState {
    data object SignedOut : DiscordAuthState
    data object Authorizing : DiscordAuthState
    data class SignedIn(val session: DiscordAuthSession) : DiscordAuthState
    data class Error(val message: String) : DiscordAuthState
}

object GatewayOp {
    const val DISPATCH = 0
    const val HEARTBEAT = 1
    const val IDENTIFY = 2
    const val PRESENCE_UPDATE = 3
    const val RESUME = 6
    const val RECONNECT = 7
    const val INVALID_SESSION = 9
    const val HELLO = 10
    const val HEARTBEAT_ACK = 11
}

sealed interface GatewayConnectionState {
    data object Disconnected : GatewayConnectionState
    data object Connecting : GatewayConnectionState
    data object Connected : GatewayConnectionState
    data class Ready(val sessionId: String, val resumeGatewayUrl: String?) : GatewayConnectionState
}

data class DiscordPresenceTimestamps(
    val start: Long? = null,
    val end: Long? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        if (start != null && start > 0) put("start", start)
        if (end != null && end > 0) put("end", end)
    }
}

data class DiscordPresenceAssets(
    val largeImage: String? = null,
    val largeText: String? = null,
    val smallImage: String? = null,
    val smallText: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        if (!largeImage.isNullOrBlank()) put("large_image", largeImage)
        if (!largeText.isNullOrBlank()) put("large_text", trimDiscordText(largeText))
        if (!smallImage.isNullOrBlank()) put("small_image", smallImage)
        if (!smallText.isNullOrBlank()) put("small_text", trimDiscordText(smallText))
    }
}

data class DiscordPresenceButton(
    val label: String,
    val url: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("label", trimDiscordText(label, maxLen = 32))
        put("url", url.trim())
    }
}

data class DiscordPresenceActivity(
    val name: String = "Orchard",
    val type: Int = 2, // 2 = LISTENING
    val details: String? = null,
    val state: String? = null,
    val timestamps: DiscordPresenceTimestamps? = null,
    val assets: DiscordPresenceAssets? = null,
    val buttons: List<DiscordPresenceButton> = emptyList(),
    val applicationId: String = DISCORD_APPLICATION_ID,
    val platform: String = "android",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", trimDiscordText(name, fallback = "Orchard"))
        put("type", type)
        if (!details.isNullOrBlank()) put("details", trimDiscordText(details))
        if (!state.isNullOrBlank()) put("state", trimDiscordText(state))
        if (timestamps != null) put("timestamps", timestamps.toJson())
        if (assets != null) put("assets", assets.toJson())
        if (buttons.isNotEmpty()) {
            val buttonsArray = JSONArray()
            val metadataUrls = JSONArray()
            buttons.take(2).forEach { button ->
                buttonsArray.put(trimDiscordText(button.label, maxLen = 32))
                metadataUrls.put(button.url.trim())
            }
            put("buttons", buttonsArray)
            val metadata = JSONObject()
            metadata.put("button_urls", metadataUrls)
            put("metadata", metadata)
        }
        put("application_id", applicationId)
        put("platform", platform)
    }
}

fun trimDiscordText(value: String?, fallback: String = "", maxLen: Int = 128): String {
    val text = (value?.takeIf(String::isNotBlank) ?: fallback).replace("\\s+".toRegex(), " ").trim()
    return if (text.length > maxLen) text.substring(0, maxLen).trim() else text
}

fun normalizeDiscordUrl(value: String?): String {
    val text = value?.trim().orEmpty()
    if (!text.startsWith("http://", ignoreCase = true) && !text.startsWith("https://", ignoreCase = true)) {
        return ""
    }
    return runCatching { URI(text).toASCIIString() }.getOrDefault("")
}

fun normalizeDiscordImageUrl(value: String?): String {
    val text = normalizeDiscordUrl(value)
    if (text.isBlank()) return ""
    return runCatching {
        val uri = URI(text)
        val path = uri.path.lowercase()
        if (path.endsWith(".mp4") || path.endsWith(".webm") || path.endsWith(".mov") || path.endsWith(".m4v")) {
            ""
        } else {
            text
        }
    }.getOrDefault("")
}

fun discordAnimatedArtworkUrl(value: String?): String {
    val source = normalizeDiscordUrl(value)
    if (source.isBlank()) return ""
    return runCatching {
        val uri = URI(source)
        if (!uri.scheme.equals("https", ignoreCase = true)) return ""
        if (!uri.host.equals("mvod.itunes.apple.com", ignoreCase = true)) return ""
        if (!uri.path.lowercase().endsWith(".mp4")) return ""

        val encoded = java.net.URLEncoder.encode(source, "UTF-8")
        "$DISCORD_ARTWORK_PROXY_ORIGIN/convert.gif?v=$DISCORD_ARTWORK_VERSION&url=$encoded"
    }.getOrDefault("")
}
