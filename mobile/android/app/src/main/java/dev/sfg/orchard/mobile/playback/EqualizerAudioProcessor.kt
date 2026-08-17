package dev.sfg.orchard.mobile.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import dev.sfg.orchard.mobile.model.EQ_BANDS
import dev.sfg.orchard.mobile.model.EqualizerConfig
import java.nio.ByteBuffer
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

@UnstableApi
class EqualizerAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var config: EqualizerConfig = EqualizerConfig()
        set(value) {
            field = value
            updateCoefficients()
        }

    private var channelCount = 0
    private var sampleRate = 0

    // Biquad state for 10 bands + 1 bass boost per channel
    // state array: [x[n-1], x[n-2], y[n-1], y[n-2]]
    private var eqState = Array(0) { Array(10) { DoubleArray(4) } }
    private var bassState = Array(0) { DoubleArray(4) }

    // Cached coefficients for 10 bands + 1 bass boost
    // coeff array: [b0/a0, b1/a0, b2/a0, a1/a0, a2/a0]
    private val eqCoeffs = Array(10) { DoubleArray(5) }
    private val bassCoeffs = DoubleArray(5)

    private var cachedConfig: EqualizerConfig? = null

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channelCount = inputAudioFormat.channelCount
        sampleRate = inputAudioFormat.sampleRate

        eqState = Array(channelCount) { Array(10) { DoubleArray(4) } }
        bassState = Array(channelCount) { DoubleArray(4) }
        
        cachedConfig = null
        updateCoefficients()

        return inputAudioFormat
    }

    override fun isActive(): Boolean = sampleRate != 0

    override fun queueInput(inputBuffer: ByteBuffer) {
        val frames = inputBuffer.remaining() / (2 * channelCount)
        if (frames == 0) return

        val cnf = config
        if (!cnf.enabled || channelCount == 0) {
            val output = replaceOutputBuffer(inputBuffer.remaining())
            output.put(inputBuffer)
            output.flip()
            return
        }

        val output = replaceOutputBuffer(inputBuffer.remaining())
        val preampLevel = 10.0.pow(cnf.clampedPreampDb / 20.0)
        val hasBassBoost = cnf.clampedBassBoost > 0f
        
        val activeBands = BooleanArray(10) { i -> abs(cnf.clampedGains[i]) > 0.1f }

        for (frame in 0 until frames) {
            for (channel in 0 until channelCount) {
                var sample = inputBuffer.short.toDouble() / 32768.0

                sample *= preampLevel

                // Apply active EQ bands
                for (band in 0 until 10) {
                    if (activeBands[band]) {
                        sample = biquad(eqCoeffs[band], eqState[channel][band], sample)
                    }
                }

                // Apply Bass Boost
                if (hasBassBoost) {
                    sample = biquad(bassCoeffs, bassState[channel], sample)
                }

                output.putShort((sample.coerceIn(-1.0, 1.0) * 32767.0).toInt().toShort())
            }
        }
        inputBuffer.position(inputBuffer.limit())
        output.flip()
    }

    private fun biquad(coefficients: DoubleArray, state: DoubleArray, input: Double): Double {
        val output = coefficients[0] * input + coefficients[1] * state[0] + coefficients[2] * state[1] -
            coefficients[3] * state[2] - coefficients[4] * state[3]
        state[1] = state[0]
        state[0] = input
        state[3] = state[2]
        state[2] = output
        return output
    }

    private fun updateCoefficients() {
        if (sampleRate == 0) return
        val currentConfig = config
        if (cachedConfig == currentConfig) return
        cachedConfig = currentConfig

        val gains = currentConfig.clampedGains
        for (i in 0 until 10) {
            val gainDb = gains[i].toDouble()
            val freq = EQ_BANDS[i].frequencyHz.toDouble()
            updatePeakingEq(eqCoeffs[i], freq, gainDb, 1.414) // Q = sqrt(2) approx 1.414
        }

        val bassGainDb = currentConfig.clampedBassBoost.toDouble() * 10.0 // Up to +10dB bass boost
        updateLowShelf(bassCoeffs, 80.0, bassGainDb)
    }

    private fun updatePeakingEq(coeffs: DoubleArray, freq: Double, gainDb: Double, q: Double) {
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * freq.coerceIn(20.0, sampleRate * 0.45) / sampleRate
        val alpha = sin(w0) / (2 * q)
        val cosW0 = cos(w0)

        val b0 = 1 + alpha * a
        val b1 = -2 * cosW0
        val b2 = 1 - alpha * a
        val a0 = 1 + alpha / a
        val a1 = -2 * cosW0
        val a2 = 1 - alpha / a

        coeffs[0] = b0 / a0
        coeffs[1] = b1 / a0
        coeffs[2] = b2 / a0
        coeffs[3] = a1 / a0
        coeffs[4] = a2 / a0
    }

    private fun updateLowShelf(coeffs: DoubleArray, freq: Double, gainDb: Double) {
        if (gainDb <= 0.1) {
            coeffs[0] = 1.0; coeffs[1] = 0.0; coeffs[2] = 0.0; coeffs[3] = 0.0; coeffs[4] = 0.0
            return
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * freq.coerceIn(20.0, sampleRate * 0.45) / sampleRate
        // S = 1
        val alpha = sin(w0) / 2 * sqrt((a + 1 / a) * 0.0 + 2.0)
        val cosW0 = cos(w0)
        val sqrtA = sqrt(a)
        
        val b0 = a * ((a + 1) - (a - 1) * cosW0 + 2 * sqrtA * alpha)
        val b1 = 2 * a * ((a - 1) - (a + 1) * cosW0)
        val b2 = a * ((a + 1) - (a - 1) * cosW0 - 2 * sqrtA * alpha)
        val a0 = (a + 1) + (a - 1) * cosW0 + 2 * sqrtA * alpha
        val a1 = -2 * ((a - 1) + (a + 1) * cosW0)
        val a2 = (a + 1) + (a - 1) * cosW0 - 2 * sqrtA * alpha

        coeffs[0] = b0 / a0
        coeffs[1] = b1 / a0
        coeffs[2] = b2 / a0
        coeffs[3] = a1 / a0
        coeffs[4] = a2 / a0
    }
}
