package com.novaflow.cueflow

import android.os.Process
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Opt-in device benchmark. Feed identical audio to each configuration, without logging speech. */
@RunWith(AndroidJUnit4::class)
class WhisperPerformanceTest {
    @Test
    fun compareCpuBudgets() {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("benchmarkAudio")
        assumeTrue("Supply a mono 48 kHz PCM16 WAV with benchmarkAudio.", path != null)
        val pcm = readSpeechWave(checkNotNull(path))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = importedWhisperModel(context)
        assumeTrue("Import the Whisper model in CueFlow before benchmarking it.", model.isFile)
        // Keep the old resampling identical across trials to isolate CPU scheduling and threads.
        val audio = FloatArray(minOf(pcm.size / 3, 48_000)) { i ->
            (pcm[i * 3].toInt() + pcm[i * 3 + 1] + pcm[i * 3 + 2]) / (3 * 32768f)
        }
        val engine = WhisperEngine()
        val handle = engine.open(model.absolutePath)
        check(handle != 0L)
        val oldPriority = Process.getThreadPriority(Process.myTid())
        try {
            val trials = listOf(10 to 2, 0 to 2, 0 to 3, 0 to 4, 0 to 6)
            engine.decode(handle, audio, 2) // warm up allocations and kernels
            repeat(2) { round ->
                for ((priority, threads) in if (round == 0) trials else trials.reversed()) {
                    Process.setThreadPriority(priority)
                    val start = System.nanoTime()
                    val transcript = checkNotNull(engine.decode(handle, audio, threads)).toString(Charsets.UTF_8)
                    val elapsed = (System.nanoTime() - start) / 1_000_000
                    assertTrue("Expected audible speech to be recognized.", transcript.isNotBlank())
                    Log.i("CueFlowBenchmark", "round=$round priority=$priority threads=$threads decodeMs=$elapsed chars=${transcript.length}")
                }
            }
        } finally {
            Process.setThreadPriority(oldPriority)
            engine.close(handle)
        }
    }
}

internal fun readSpeechWave(path: String): ShortArray {
    val bytes = File(path).readBytes()
    val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    check(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE")
    buffer.position(12)
    var valid = false
    while (buffer.remaining() >= 8) {
        val kind = ByteArray(4).also(buffer::get).toString(Charsets.US_ASCII)
        val size = buffer.int
        val start = buffer.position()
        check(size >= 0 && size <= buffer.remaining())
        when (kind) {
            "fmt " -> valid = size >= 16 && buffer.getShort(start).toInt() == 1 &&
                buffer.getShort(start + 2).toInt() == 1 && buffer.getInt(start + 4) == 48_000 &&
                buffer.getShort(start + 14).toInt() == 16
            "data" -> {
                check(valid)
                return ShortArray(size / 2).also { buffer.asShortBuffer().get(it) }
            }
        }
        buffer.position(start + size + size % 2)
    }
    error("No PCM data in WAV.")
}
