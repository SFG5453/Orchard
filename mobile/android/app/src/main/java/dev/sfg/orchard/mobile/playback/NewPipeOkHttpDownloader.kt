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

package dev.sfg.orchard.mobile.playback

import dev.sfg.orchard.mobile.auth.YouTubeSessionProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response

/**
 * Bridges NewPipeExtractor's [Downloader] contract to the app's shared [OkHttpClient].
 *
 * This keeps connection pooling, timeouts, and proxy settings consistent with the rest
 * of the app instead of opening a separate socket pool just for NewPipe.
 *
 * When a session is available its cookie rides along, so the extractor is subject
 * to the same bot checks a signed-in browser is rather than an anonymous one.
 * Only requests to YouTube itself carry it; the media CDN neither needs the
 * account nor should be handed it.
 */
class NewPipeOkHttpDownloader(
    private val client: OkHttpClient,
    private val sessionProvider: YouTubeSessionProvider? = null,
) : Downloader() {

    override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): Response {
        val builder = Request.Builder().url(request.url())
        request.headers().forEach { (name, values) ->
            values.forEach { builder.addHeader(name, it) }
        }
        if (request.headers().keys.none { it.equals("Cookie", ignoreCase = true) }) {
            sessionCookieFor(request.url())?.let { builder.header("Cookie", it) }
        }
        val body = request.dataToSend()
        when {
            request.httpMethod() == "POST" ->
                builder.post((body ?: ByteArray(0)).toRequestBody())
            request.httpMethod() == "PUT" ->
                builder.put((body ?: ByteArray(0)).toRequestBody())
        }
        client.newCall(builder.build()).execute().use { response ->
            val responseHeaders = mutableMapOf<String, List<String>>()
            response.headers.names().forEach { name ->
                responseHeaders[name] = response.headers.values(name)
            }
            return Response(
                response.code,
                response.message,
                responseHeaders,
                response.body.string(),
                response.request.url.toString(),
            )
        }
    }

    private fun sessionCookieFor(url: String): String? {
        val cookie = sessionProvider?.session()?.cookie?.takeIf(String::isNotBlank) ?: return null
        val host = runCatching { java.net.URI(url).host }.getOrNull().orEmpty().lowercase()
        val youtubeHost = host == "youtube.com" || host.endsWith(".youtube.com")
        return cookie.takeIf { youtubeHost }
    }
}
