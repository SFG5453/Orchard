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

package dev.sfg.orchard.mobile.connect

import android.media.MediaDataSource
import android.util.Log
import dev.sfg.orchard.mobile.playback.RemoteMix
import dev.sfg.orchard.mobile.playback.smart.AdaptiveMix
import dev.sfg.orchard.mobile.playback.smart.PreparedMix
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Adaptive mix on the desktop mix host while this phone is the target (spec: Mix Host).
 * Uploads both songs' cached bytes, asks MixPrepare, and turns the PCM stream that comes back
 * into the same [PreparedMix] a local render produces. The phone keeps the clock and the output:
 * the render is stamped with this player's media time and spliced here like a local one.
 */
internal class ConnectMix(private val repository: ConnectRepository) : RemoteMix {
    private class Pending(val job: Job, val render: CompletableDeferred<Pair<JSONObject, ByteArray>>)

    private val pending = ConcurrentHashMap<String, Pending>()
    // Upload stream id -> mix id, so a failed upload abandons its mix at once.
    private val uploads = ConcurrentHashMap<String, String>()

    override fun prepare(
        request: JSONObject,
        outgoing: () -> MediaDataSource?,
        incoming: () -> MediaDataSource?,
    ): PreparedMix? {
        val sessionId = repository.mixSession() ?: return null
        val mixId = UUID.randomUUID().toString()
        val prepared = try {
            runBlocking {
                pending[mixId] = Pending(coroutineContext.job, CompletableDeferred())
                withTimeoutOrNull(BUDGET_MS) { remote(sessionId, mixId, request, outgoing, incoming) }
            }
        } catch (_: CancellationException) {
            null
        } catch (error: Exception) {
            Log.w(TAG, "Remote mix failed", error)
            null
        } finally {
            pending.remove(mixId)
            uploads.values.removeAll { it == mixId }
        }
        when (prepared) {
            is PreparedMix.Ready -> Log.i(TAG, "AdaptiveMix: remote plan | ${prepared.log}")
            null -> Log.i(TAG, "AdaptiveMix: remote mix unavailable, mixing locally")
            else -> Unit
        }
        return prepared
    }

    private suspend fun remote(
        sessionId: String,
        mixId: String,
        request: JSONObject,
        outgoing: () -> MediaDataSource?,
        incoming: () -> MediaDataSource?,
    ): PreparedMix? {
        val sources = listOf(
            Triple("outgoing", outgoing, request.optJSONObject("currentTrack")?.optString("id").orEmpty()),
            Triple("incoming", incoming, request.optJSONObject("nextTrack")?.optString("id").orEmpty()),
        )
        for ((role, source, trackId) in sources) {
            // The provider's own bytes: nothing re-encoded, and a few MB per song.
            val bytes = source()?.use(::readAll) ?: return null
            val meta = JSONObject().put("codec", "source").put("track", trackId)
                .put("meta", JSONObject().put("mix", mixId).put("role", role))
            val stream = repository.sendStream(sessionId, meta, bytes) ?: return null
            uploads[stream] = mixId
        }
        val params = JSONObject().put("mix", mixId).put("request", request)
            .put("codecs", JSONArray(listOf("pcm_s16", "pcm_f32")))
        val reply = repository.request("mix", "MixPrepare", params, timeoutMs = RPC_TIMEOUT_MS) ?: return null
        val plan = reply.optJSONObject("result") ?: return null
        plan.optString("error").takeIf { it.isNotEmpty() }?.let { error ->
            // The host's refusal stands; any other failure is worth a local attempt.
            return if (error.startsWith(AdaptiveMix.NATURAL_BOUNDARY)) PreparedMix.NoMix(error) else null
        }
        val render = pending[mixId]?.render ?: return null
        val (header, payload) = withTimeoutOrNull(STREAM_WAIT_MS) { render.await() } ?: return null
        return ready(plan, header, payload)
    }

    /** A finished AudioChunk stream from the mix host. */
    fun onAudio(header: JSONObject, payload: ByteArray) {
        val mixId = header.optJSONObject("meta")?.optString("mix") ?: return
        pending[mixId]?.render?.complete(header to payload)
    }

    fun onStreamFailed(stream: String) {
        uploads.remove(stream)?.let { pending[it]?.job?.cancel() }
    }

    fun onStreamSent(stream: String) {
        uploads.remove(stream)
    }

    companion object {
        private const val TAG = "OrchardAdaptiveMix"
        // The engine starts a pair two minutes out; a failed remote attempt leaves time to mix locally.
        private const val BUDGET_MS = 75_000L
        private const val RPC_TIMEOUT_MS = 60_000L
        private const val STREAM_WAIT_MS = 15_000L
        // Room for CD-quality FLAC; longer hi-res files mix on the phone.
        private const val MAX_SOURCE_BYTES = 64L * 1024 * 1024

        private fun readAll(source: MediaDataSource): ByteArray? {
            val size = source.size
            if (size <= 0 || size > MAX_SOURCE_BYTES) return null
            val bytes = ByteArray(size.toInt())
            var offset = 0
            while (offset < bytes.size) {
                val read = source.readAt(offset.toLong(), bytes, offset, bytes.size - offset)
                if (read <= 0) return null
                offset += read
            }
            return bytes
        }

        /** The render as a local [PreparedMix.Ready], or null when it does not add up. */
        internal fun ready(plan: JSONObject, header: JSONObject, payload: ByteArray): PreparedMix.Ready? {
            val rate = header.optInt("sample_rate")
            val incomingRate = plan.optInt("incomingRate")
            if (header.optInt("channels") != 2 || rate <= 0 || rate != plan.optInt("rate") || incomingRate <= 0) return null
            val pcm = when (header.optString("codec")) {
                "pcm_s16" -> ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                    .let { buffer -> ShortArray(buffer.remaining()).also { buffer.get(it) } }
                "pcm_f32" -> ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                    .let { buffer -> ShortArray(buffer.remaining()) { (buffer.get(it).coerceIn(-1f, 1f) * 32767f).toInt().toShort() } }
                else -> return null
            }
            val duration = pcm.size / 2.0 / rate
            val outgoingStart = plan.optDouble("outgoingStart", -1.0)
            if (duration <= 0 || duration > 30 || abs(duration - plan.optDouble("duration")) > 0.01) return null
            // The host stamps the render with this player's media time; anything else is someone else's song.
            if (outgoingStart < 0 || abs(header.optDouble("timestamp", -1.0) - outgoingStart) > 1e-6) return null
            return PreparedMix.Ready(
                outgoingStart = outgoingStart,
                incomingCue = plan.optDouble("incomingCue"),
                incomingResume = plan.optDouble("incomingResume"),
                duration = duration,
                strategy = plan.optString("strategy"),
                targetBpm = plan.optDouble("targetBpm", 0.0),
                rate = rate,
                incomingRate = incomingRate,
                pcm = pcm,
                log = AdaptiveMix.planLog(plan),
            )
        }
    }
}
