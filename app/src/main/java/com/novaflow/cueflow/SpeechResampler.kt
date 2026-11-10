package com.novaflow.cueflow

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Converts microphone PCM16 to 16 kHz float while retaining filter state across chunks. */
internal class SpeechResampler(private val inputRate: Int = 48_000) {
    private val coefficients = when (inputRate) {
        48_000 -> coefficients48k
        44_100 -> coefficients44k
        16_000 -> floatArrayOf()
        else -> error("Unsupported microphone sample rate: $inputRate")
    }
    private val history = FloatArray(coefficients.size)
    private var write = 0
    private var phase = 0

    fun offer(pcm: ShortArray, size: Int, emit: (Float) -> Unit) {
        require(size in 0..pcm.size)
        if (inputRate == 16_000) {
            for (i in 0 until size) emit(pcm[i] / 32768f)
            return
        }
        for (i in 0 until size) {
            history[write] = pcm[i] / 32768f
            write = (write + 1) % history.size
            phase += 16_000
            if (phase < inputRate) continue
            phase -= inputRate
            var value = 0f
            var index = write
            for (coefficient in coefficients) {
                index = if (index == 0) history.lastIndex else index - 1
                value += coefficient * history[index]
            }
            emit(value)
        }
    }

    companion object {
        // 63-tap windowed sinc, 7 kHz cutoff. Delay is less than 1 ms.
        private val coefficients48k = lowPass(48_000)
        private val coefficients44k = lowPass(44_100)

        private fun lowPass(rate: Int): FloatArray = FloatArray(63) { index ->
            val offset = index - 31
            val cutoff = 7000.0 / rate
            val sinc = if (offset == 0) 2 * cutoff else sin(2 * PI * cutoff * offset) / (PI * offset)
            (sinc * (0.54 - 0.46 * cos(2 * PI * index / 62))).toFloat()
        }.let { taps -> val sum = taps.sum(); FloatArray(taps.size) { taps[it] / sum } }
    }
}
