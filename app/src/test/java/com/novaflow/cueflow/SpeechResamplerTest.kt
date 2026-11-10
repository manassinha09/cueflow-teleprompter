package com.novaflow.cueflow

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

class SpeechResamplerTest {
    @Test
    fun chunkBoundariesPreserveEverySampleAndFilterState() {
        for (rate in listOf(48_000, 44_100, 16_000)) {
            val input = tone(1000, rate)
            val whole = mutableListOf<Float>()
            SpeechResampler(rate).offer(input, input.size, whole::add)
            val chunks = mutableListOf<Float>()
            val resampler = SpeechResampler(rate)
            for (start in input.indices step 997) {
                val part = input.copyOfRange(start, minOf(start + 997, input.size))
                resampler.offer(part, part.size, chunks::add)
            }
            assertEquals(16_000, chunks.size)
            assertArrayEquals(whole.toFloatArray(), chunks.toFloatArray(), 0f)
        }
    }

    @Test
    fun preservesSpeechBandAndRejectsFrequenciesThatWouldAlias() {
        fun energy(frequency: Int): Double {
            val output = mutableListOf<Float>()
            val input = tone(frequency)
            SpeechResampler().offer(input, input.size, output::add)
            return sqrt(output.drop(100).map { (it * it).toDouble() }.average())
        }
        val speech = energy(1000)
        assertTrue(speech in 0.34..0.36)
        assertTrue(energy(5000) > speech * 0.9)
        assertTrue("Ultrasonic content must not fold into recognized speech.", energy(10000) < speech * 0.01)
    }

    private fun tone(frequency: Int, rate: Int = 48_000) = ShortArray(rate) {
        (sin(2 * PI * frequency * it / rate) * 16_384).toInt().toShort()
    }
}
