package dev.sfg.orchard.mobile.playback

import dev.sfg.orchard.mobile.auth.YouTubeSessionAuth
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
 * When a session is available its cookie and SAPISID auth ride along, so the extractor
 * is authenticated for age-restricted and explicit content.
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
        val session = sessionProvider?.session()
        val cookie = session?.cookie?.takeIf(String::isNotBlank)
        val host = runCatching { java.net.URI(request.url()).host }.getOrNull().orEmpty().lowercase()
        val youtubeHost = host == "youtube.com" || host.endsWith(".youtube.com")

        if (cookie != null && youtubeHost) {
            if (request.headers().keys.none { it.equals("Cookie", ignoreCase = true) }) {
                builder.header("Cookie", cookie)
            }
            val origin = if (host.contains("music.youtube.com")) "https://music.youtube.com" else "https://www.youtube.com"
            YouTubeSessionAuth.authorization(cookie, origin = origin)?.let { auth ->
                if (request.headers().keys.none { it.equals("Authorization", ignoreCase = true) }) {
                    builder.header("Authorization", auth)
                }
            }
            builder.header("X-Goog-AuthUser", "0")
            if (request.headers().keys.none { it.equals("Origin", ignoreCase = true) }) {
                builder.header("Origin", origin)
            }
            if (request.headers().keys.none { it.equals("X-Origin", ignoreCase = true) }) {
                builder.header("X-Origin", origin)
            }
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
}
