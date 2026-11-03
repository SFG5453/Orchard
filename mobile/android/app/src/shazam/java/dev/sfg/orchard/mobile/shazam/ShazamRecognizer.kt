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

package dev.sfg.orchard.mobile.shazam

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import com.shazam.shazamkit.AudioSampleRateInHz
import com.shazam.shazamkit.DeveloperToken
import com.shazam.shazamkit.DeveloperTokenProvider
import com.shazam.shazamkit.MatchError
import com.shazam.shazamkit.MatchResult
import com.shazam.shazamkit.ShazamKit
import com.shazam.shazamkit.ShazamKitResult
import com.shazam.shazamkit.Signature
import com.shazam.shazamkit.SignatureGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

internal class ShazamRecognizer(
    private val developerToken: suspend () -> String,
    private val forgetToken: () -> Unit,
) {
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    suspend fun identify(onStatus: (String) -> Unit): String = withTimeout(45_000) {
        onStatus("Preparing Shazam…")
        val token = DeveloperToken(developerToken())
        val catalog = ShazamKit.createShazamCatalog(object : DeveloperTokenProvider {
            override fun provideDeveloperToken() = token
        })
        val generator = ShazamKit.createSignatureGenerator(AudioSampleRateInHz.SAMPLE_RATE_48000).value()
        val session = ShazamKit.createSession(catalog).value()
        onStatus("Listening…")
        val signature = record(generator, minOf(10_000L, catalog.maximumQuerySignatureDurationInMs))
        onStatus("Finding the song…")
        when (val result = session.match(signature)) {
            is MatchResult.Match -> {
                val item = result.matchedMediaItems.firstOrNull { !it.title.isNullOrBlank() }
                    ?: error("Shazam returned a song without a title. Try again.")
                listOfNotNull(item.title, item.artist).filter(String::isNotBlank).joinToString(" ")
            }
            is MatchResult.NoMatch -> error("No song recognized. Move closer to the music and try again.")
            is MatchResult.Error -> when (result.exception.matchError) {
                MatchError.UNAUTHORIZED -> {
                    // A rotated key leaves a cached token Apple refuses; fetch a new one next time.
                    forgetToken()
                    error("Shazam is unavailable. Try again later.")
                }
                else -> error("Shazam could not identify the song. Check your connection and try again.")
            }
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private suspend fun record(generator: SignatureGenerator, durationMs: Long): Signature = withContext(Dispatchers.IO) {
        val minimum = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        check(minimum > 0) { "This microphone does not support song identification." }
        val bufferSize = maxOf(minimum, SAMPLE_RATE / 5 * 2)
        val recorder = AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.MIC)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
            .setBufferSizeInBytes(bufferSize)
            .build()
        try {
            check(recorder.state == AudioRecord.STATE_INITIALIZED) { "The microphone is unavailable. Try again." }
            recorder.startRecording()
            check(recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Could not start the microphone." }
            val buffer = ByteArray(bufferSize)
            val targetBytes = durationMs * SAMPLE_RATE * 2 / 1000
            var recorded = 0L
            val startedAt = System.currentTimeMillis()
            // Non-blocking reads keep cancellation responsive, including when the mic is silenced.
            withTimeout(durationMs + 5_000) {
                while (recorded < targetBytes) {
                    currentCoroutineContext().ensureActive()
                    val count = recorder.read(buffer, 0, minOf(buffer.size.toLong(), targetBytes - recorded).toInt(), AudioRecord.READ_NON_BLOCKING)
                    check(count >= 0) { "The microphone stopped working. Try again." }
                    if (count == 0) {
                        delay(15)
                    } else {
                        generator.append(buffer, count, startedAt + recorded * 1000 / (SAMPLE_RATE * 2))
                        recorded += count
                    }
                }
            }
            generator.generateSignature()
        } finally {
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            } finally {
                recorder.release()
            }
        }
    }

    private fun <T> ShazamKitResult<*, T>.value(): T = when (this) {
        is ShazamKitResult.Success -> data
        is ShazamKitResult.Failure -> error("Shazam could not start on this device. Try again.")
    }

    private companion object {
        const val SAMPLE_RATE = 48_000
    }
}
