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

package dev.sfg.orchard.mobile.catalog

import dev.sfg.orchard.mobile.artwork.highResolutionArtworkUrl
import org.json.JSONArray
import org.json.JSONObject

internal object JsonTraversal {
    fun renderers(root: Any?, name: String): List<JSONObject> = buildList { visit(root, name, this) }

    private fun visit(value: Any?, name: String, output: MutableList<JSONObject>) {
        when (value) {
            is JSONObject -> {
                value.optJSONObject(name)?.let(output::add)
                value.keys().forEach { visit(value.opt(it), name, output) }
            }
            is JSONArray -> for (index in 0 until value.length()) visit(value.opt(index), name, output)
        }
    }

    /**
     * True when a renderer describes something the listener uploaded themselves rather than
     * something from YouTube's catalog.
     *
     * Ported from the desktop's `isUploadedMusicItem` so both clients decide this the same way.
     * YouTube never states it outright; it is inferred from three markers that only ever appear
     * on privately owned entities: an entity id in the `<kind>_po_<id>` shape, the delete command
     * offered in the item's own menu, and the library browse ids uploads are filed under.
     */
    fun isPrivatelyOwned(root: Any?): Boolean =
        // Identity, not equality: org.json does not define structural equality, and the guard
        // only needs to stop a renderer that points back at itself.
        scanPrivatelyOwned(root, java.util.Collections.newSetFromMap(java.util.IdentityHashMap()))

    private fun scanPrivatelyOwned(value: Any?, seen: MutableSet<JSONObject>): Boolean {
        when (value) {
            is JSONObject -> {
                if (!seen.add(value)) return false
                for (key in value.keys()) {
                    val normalized = key.replace("_", "").lowercase()
                    val nested = value.opt(key)
                    when {
                        normalized == "musicdeleteprivatelyownedentitycommand" -> return true
                        normalized == "entityid" &&
                            PRIVATELY_OWNED_ENTITY_ID.containsMatchIn(nested?.toString().orEmpty()) -> return true
                        normalized == "type" &&
                            nested?.toString().orEmpty()
                                .contains("deleteprivatelyownedentity", ignoreCase = true) -> return true
                        normalized == "browseid" &&
                            nested?.toString().orEmpty().startsWith(PRIVATELY_OWNED_BROWSE_PREFIX) -> return true
                    }
                    if (scanPrivatelyOwned(nested, seen)) return true
                }
            }
            is JSONArray -> for (index in 0 until value.length()) {
                if (scanPrivatelyOwned(value.opt(index), seen)) return true
            }
        }
        return false
    }

    private val PRIVATELY_OWNED_ENTITY_ID = Regex("""^[a-z]+_po_""", RegexOption.IGNORE_CASE)
    private const val PRIVATELY_OWNED_BROWSE_PREFIX = "FEmusic_library_privately_owned"

    fun text(value: JSONObject?): String {
        if (value == null) return ""
        value.optString("simpleText").takeIf(String::isNotBlank)?.let { return it }
        value.optString("text").takeIf(String::isNotBlank)?.let { return it }
        val runs = value.optJSONArray("runs") ?: return ""
        return buildString {
            for (index in 0 until runs.length()) append(runs.optJSONObject(index)?.optString("text").orEmpty())
        }.trim()
    }

    fun runs(value: JSONObject?): List<JSONObject> {
        val runs = value?.optJSONArray("runs") ?: return emptyList()
        return buildList {
            for (index in 0 until runs.length()) runs.optJSONObject(index)?.let(::add)
        }
    }

    fun largestThumbnail(root: JSONObject?): String {
        val thumbnails = renderers(root, "thumbnail")
            .flatMap { renderer ->
                val values = renderer.optJSONArray("thumbnails") ?: JSONArray()
                buildList {
                    for (index in 0 until values.length()) values.optJSONObject(index)?.let(::add)
                }
            }
        return thumbnails.maxByOrNull { it.optInt("width") * it.optInt("height") }
            ?.optString("url").orEmpty()
            .let(::highResolutionArtworkUrl)
    }

    fun navigation(root: JSONObject?): JSONObject? {
        if (root == null) return null
        return renderers(root, "navigationEndpoint").firstOrNull()
            ?: renderers(root, "playNavigationEndpoint").firstOrNull()
    }

    fun videoId(endpoint: JSONObject?): String =
        endpoint?.optJSONObject("watchEndpoint")?.optString("videoId").orEmpty()

    /** ATV / OMV / UGC classification hanging off a watch endpoint. */
    fun musicVideoType(endpoint: JSONObject?): String = endpoint
        ?.optJSONObject("watchEndpoint")
        ?.optJSONObject("watchEndpointMusicSupportedConfigs")
        ?.optJSONObject("watchEndpointMusicConfig")
        ?.optString("musicVideoType").orEmpty()

    fun browseId(endpoint: JSONObject?): String =
        endpoint?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()

    fun pageType(endpoint: JSONObject?): String = endpoint
        ?.optJSONObject("browseEndpoint")
        ?.optJSONObject("browseEndpointContextSupportedConfigs")
        ?.optJSONObject("browseEndpointContextMusicConfig")
        ?.optString("pageType").orEmpty()
}
