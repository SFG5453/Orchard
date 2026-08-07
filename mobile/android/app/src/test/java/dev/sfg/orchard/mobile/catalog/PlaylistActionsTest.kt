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

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaylistActionsTest {

    private class MockEngine(
        val handle: (endpoint: String, payload: JSONObject) -> JSONObject
    ) {
        val okHttpClient: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val urlPath = request.url.encodedPath
                if (urlPath == "/") {
                    return@addInterceptor Response.Builder()
                        .request(request)
                        .protocol(okhttp3.Protocol.HTTP_1_1)
                        .code(200)
                        .message("OK")
                        .body("<html></html>".toResponseBody("text/html".toMediaType()))
                        .build()
                }
                val endpoint = urlPath.substringAfterLast("/")
                val bodyStr = request.body?.let { body ->
                    val buffer = okio.Buffer()
                    body.writeTo(buffer)
                    buffer.readUtf8()
                } ?: "{}"
                val payload = if (bodyStr.isBlank()) JSONObject() else JSONObject(bodyStr)
                val responseJson = handle(endpoint, payload)
                Response.Builder()
                    .request(request)
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseJson.toString().toResponseBody("application/json".toMediaType()))
                    .build()
            }
            .build()
    }

    @Test
    fun `add track to playlist sends addedVideoId in edit_playlist action`() {
        val recordedRequests = mutableListOf<Pair<String, JSONObject>>()
        val mock = MockEngine { endpoint, payload ->
            recordedRequests.add(endpoint to payload)
            when (endpoint) {
                "browse" -> JSONObject() // empty browse page, track not in playlist yet
                "edit_playlist" -> JSONObject().put("status", "STATUS_SUCCEEDED")
                else -> JSONObject()
            }
        }
        val client = InnerTubeClient(mock.okHttpClient)
        val actions = PlaylistActions(client)

        actions.add("VLPL12345", "song777")

        assertEquals(2, recordedRequests.size)

        val (browseEndpoint, browsePayload) = recordedRequests[0]
        assertEquals("browse", browseEndpoint)
        assertEquals("VLPL12345", browsePayload.optString("browseId"))

        val (editEndpoint, editPayload) = recordedRequests[1]
        assertEquals("edit_playlist", editEndpoint)
        assertEquals("PL12345", editPayload.optString("playlistId"))

        val actionsArray = editPayload.optJSONArray("actions")
        assertEquals(1, actionsArray?.length())
        val actionObj = actionsArray!!.getJSONObject(0)
        assertEquals("ACTION_ADD_VIDEO", actionObj.optString("action"))
        assertEquals("song777", actionObj.optString("addedVideoId"))
    }

    @Test
    fun `create playlist sends title and videoIds`() {
        val recordedRequests = mutableListOf<Pair<String, JSONObject>>()
        val mock = MockEngine { endpoint, payload ->
            recordedRequests.add(endpoint to payload)
            JSONObject().put("playlistId", "PLNEW123")
        }
        val client = InnerTubeClient(mock.okHttpClient)
        val actions = PlaylistActions(client)

        val id = actions.create("My New Playlist", "song111")
        assertEquals("PLNEW123", id)
        assertEquals(1, recordedRequests.size)

        val (endpoint, payload) = recordedRequests[0]
        assertEquals("create", endpoint)
        assertEquals("My New Playlist", payload.optString("title"))
        assertEquals("song111", payload.optJSONArray("videoIds")?.getString(0))
    }
}
