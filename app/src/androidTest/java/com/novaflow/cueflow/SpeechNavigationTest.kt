package com.novaflow.cueflow

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import android.util.Log
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class SpeechNavigationTest {
    private val useSherpa: Boolean
        get() = InstrumentationRegistry.getArguments().getString("speechEngine") != "whisper"

    @Test
    fun liveSpeechFollowsSeveralSentencesAndFinishesAfterAPause() {
        val path = InstrumentationRegistry.getArguments().getString("benchmarkAudio")
        assumeTrue("Supply the generated reference speech with benchmarkAudio.", path != null)
        val script = "Welcome to our camera test. The script should move as you read aloud. " +
            "Take a short pause before continuing. When you are ready, stop recording and play back the saved video."
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Import the Whisper model in CueFlow before testing it.", useSherpa || importedWhisperModel(context).isFile)
        val offsets = CopyOnWriteArrayList<Int>()
        val hypotheses = CopyOnWriteArrayList<String>()
        val matchTimes = CopyOnWriteArrayList<Long>()
        val ready = CountDownLatch(1)
        val follower = SpeechFollower(context, script, {
            matchTimes.add(System.nanoTime())
            offsets.add(it)
        }, {
            if (it.startsWith("Listening")) ready.countDown()
        }, onRecognition = hypotheses::add, useSherpa = useSherpa)
        follower.start()
        try {
            assertTrue("The speech model should load before feeding the fixture.", ready.await(15, TimeUnit.SECONDS))
            val pcm = readSpeechWave(checkNotNull(path))
            val started = System.nanoTime()
            feed(follower, pcm)
            val feedEnd = System.nanoTime()
            if (useSherpa && InstrumentationRegistry.getArguments().getString("finishImmediately") == "true") {
                follower.finishInput()
            } else {
                feed(follower, ShortArray(96_000))
            }
            Thread.sleep(1_000)
            val firstMs = matchTimes.firstOrNull()?.let { (it - started) / 1_000_000 }
            val lastFromEndMs = matchTimes.lastOrNull()?.let { (it - feedEnd) / 1_000_000 }
            val engine = if (useSherpa) "Zipformer" else "Whisper"
            Log.i("CueFlowBenchmark", "$engine firstHighlightMs=$firstMs lastHighlightFromAudioEndMs=$lastFromEndMs " +
                "updates=${offsets.size} finalOffset=${offsets.lastOrNull()} scriptLength=${script.length}")
            assertTrue("Expected frequent progress during continuous speech: $offsets", offsets.size >= 6)
            val finalOffset = offsets.lastOrNull() ?: 0
            assertEquals("Should highlight the last word. Fixture hypotheses: ${hypotheses.distinct()}",
                script.lastIndexOf('.'), finalOffset)
            assertTrue("Following must not rewind unexpectedly.", offsets.zipWithNext().all { (a, b) -> b >= a })
        } finally {
            follower.stop()
        }
    }

    @Test
    fun repeatedVoiceCommandsAreDeliveredOncePerUtterance() {
        val path = InstrumentationRegistry.getArguments().getString("commandAudioPath")
        assumeTrue("Supply a mono 48 kHz PCM16 WAV saying the requested scroll command.", path != null)
        val pcm = readWave(checkNotNull(path))
        val expected = InstrumentationRegistry.getArguments().getString("commandLines")?.toInt() ?: 2
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Import the Whisper model in CueFlow before testing it.", useSherpa || importedWhisperModel(context).isFile)
        val commands = CopyOnWriteArrayList<Int>()
        val hypotheses = CopyOnWriteArrayList<String>()
        val ready = CountDownLatch(1)
        val received = CountDownLatch(2)
        lateinit var follower: SpeechFollower
        follower = SpeechFollower(context, "Read this sentence. Read the next sentence.", {}, {
            if (it.startsWith("Listening")) ready.countDown()
        }, {
            commands.add(it)
            received.countDown()
            follower.seekTo(0, preserveAudio = true)
        }, hypotheses::add, useSherpa = useSherpa)
        follower.start()
        try {
            assertTrue("The speech model should load before feeding the fixture.", ready.await(15, TimeUnit.SECONDS))
            repeat(2) {
                feed(follower, pcm)
                feed(follower, ShortArray(48_000))
            }
            received.await(8, TimeUnit.SECONDS)
            Thread.sleep(1_000)
            assertEquals("Each utterance should produce exactly one two-line movement. Fixture hypotheses: $hypotheses",
                listOf(expected, expected), commands.toList())
        } finally {
            follower.stop()
        }
    }

    private fun feed(follower: SpeechFollower, pcm: ShortArray) {
        val chunk = ShortArray(960)
        for (start in pcm.indices step chunk.size) {
            val count = minOf(chunk.size, pcm.size - start)
            pcm.copyInto(chunk, 0, start, start + count)
            follower.offer(chunk, count)
            Thread.sleep(20)
        }
    }

    private fun readWave(path: String): ShortArray {
        val bytes = File(path).readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF")
        check(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE")
        buffer.position(12)
        var validFormat = false
        while (buffer.remaining() >= 8) {
            val type = ByteArray(4).also { buffer.get(it) }.toString(Charsets.US_ASCII)
            val size = buffer.int
            val start = buffer.position()
            check(size >= 0 && size <= buffer.remaining())
            when (type) {
                "fmt " -> {
                    check(size >= 16)
                    validFormat = buffer.getShort(start).toInt() == 1 &&
                        buffer.getShort(start + 2).toInt() == 1 && buffer.getInt(start + 4) == 48_000 &&
                        buffer.getShort(start + 14).toInt() == 16
                }
                "data" -> {
                    check(validFormat)
                    return ShortArray(size / 2).also { buffer.asShortBuffer().get(it) }
                }
            }
            buffer.position(start + size + size % 2)
        }
        error("WAV has no PCM data.")
    }
}
