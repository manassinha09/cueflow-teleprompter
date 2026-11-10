package com.novaflow.cueflow

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.annotation.Keep
import java.util.Locale
import kotlin.math.sqrt

@Keep
internal class WhisperEngine {
    external fun open(path: String): Long
    external fun decode(handle: Long, pcm: FloatArray, threads: Int = 3): ByteArray?
    external fun close(handle: Long)

    companion object {
        init {
            System.loadLibrary("cueflow_whisper")
        }
    }
}

internal fun scrollCommandLines(transcript: String, allowShort: Boolean = true): Int? {
    val normalized = transcript.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), " ").trim()
    val match = SCROLL_COMMAND.find(normalized)
        ?: SCROLLED_COMMAND.matchEntire(normalized)
        ?: (if (allowShort) SHORT_SCROLL_COMMAND.find(normalized) else null)
        ?: return null
    val direction = match.groupValues[1].ifEmpty { match.groupValues[2] }
    return if (direction == "up") -2 else 2
}

// Whisper sometimes writes the homophones "to"/"too" for "two". Accept these only
// inside the complete navigation phrase, never in general script normalization.
private val SCROLL_COMMAND = Regex("\\bscroll (?:(up|down) (?:two|2|to|too) lines?|(?:two|2|to|too) lines? (up|down))\\b")
// Only accept the inflected form as a standalone command, so narration like
// "I scrolled down two lines" does not become a navigation action.
private val SCROLLED_COMMAND = Regex("scrolled (up|down) (?:two|2|to|too) lines?")
private val SHORT_SCROLL_COMMAND = Regex("\\bscroll (up|down)$")

internal class SpeechFollower(
    private val context: Context,
    script: String,
    private val onMatch: (Int) -> Unit,
    private val onStatus: (String) -> Unit,
    private val onScroll: (Int) -> Unit = {},
    // Opt-in observer for deterministic offline evaluation; the app never logs raw speech.
    private val onRecognition: ((String) -> Unit)? = null,
    private val useSherpa: Boolean = true,
    private val preparedModel: SpeechModelLoader? = null,
) {
    private val matcher = ScriptMatcher(script)
    private val diagnostics = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    private val main = Handler(Looper.getMainLooper())
    // Retain the opening words if recording starts while Zipformer is still preloading.
    private val ring = FloatArray(if (useSherpa) 128_000 else 48_000)
    private var samplesWritten = 0L
    private var resampler = SpeechResampler()
    private val power = context.getSystemService(PowerManager::class.java)
    @Volatile private var stopped = false
    @Volatile private var inputFinished = false
    @Volatile private var revision = 0
    private var audioStart = 0L

    fun start(sampleRate: Int = 48_000) {
        if (sampleRate != 48_000) resampler = SpeechResampler(sampleRate)
        val loader = preparedModel ?: SpeechModelLoader(context, useSherpa)
        if (!loader.execute {
            try {
                follow(loader)
            } finally {
                if (preparedModel == null) loader.close()
            }
        }) status("Speech unavailable: the model was closed.")
    }

    fun stop() {
        stopped = true
    }

    fun finishInput() {
        if (useSherpa) inputFinished = true else stop()
    }

    @Synchronized
    fun seekTo(offset: Int, preserveAudio: Boolean = false) {
        matcher.seekTo(offset)
        resetAudioWindow(if (preserveAudio) audioStart else samplesWritten)
    }

    /** Discard overlapping audio and any decode still running for the old reading position. */
    private fun resetAudioWindow(start: Long = samplesWritten) {
        audioStart = start
        revision++
    }

    @Synchronized
    fun offer(pcm: ShortArray, size: Int) {
        // Only the recognition copy is filtered; recording retains the microphone's sample rate.
        resampler.offer(pcm, size) { sample ->
            ring[(samplesWritten % ring.size).toInt()] = sample
            samplesWritten++
        }
    }

    @Synchronized
    private fun snapshot(after: Long, streaming: Boolean = false): SpeechWindow? {
        val draining = streaming && inputFinished
        val minimum = if (streaming) 1_600 else if (after == 0L) 12_000 else 8_000
        if (!draining && samplesWritten - after < minimum) return null
        val oldest = samplesWritten - ring.size
        val start = if (streaming) maxOf(audioStart, oldest, after) else maxOf(audioStart, oldest)
        val end = if (streaming) minOf(samplesWritten, start + 3_200) else samplesWritten
        val endOfInput = draining && end == samplesWritten
        val size = (end - start).toInt()
        val minimumWindow = if (streaming) 1_600 else 12_000
        if (!endOfInput && size < minimumWindow) return null
        return SpeechWindow(end, FloatArray(size) { ring[((start + it) % ring.size).toInt()] }, revision, endOfInput)
    }

    private fun follow(loader: SpeechModelLoader) {
        if (stopped) return
        val failure = runCatching {
            val model = loader.getOrLoad { status(if (useSherpa) "Loading Sherpa-ONNX…" else "Loading Whisper…") }
            if (stopped) return
            when (model) {
                is LoadedSpeechModel.Zipformer -> followSherpa(model)
                is LoadedSpeechModel.Whisper -> followWhisper(model)
            }
        }.exceptionOrNull()
        if (failure != null) status("Speech unavailable: ${failure.message ?: failure.javaClass.simpleName}")
    }

    private fun followWhisper(model: LoadedSpeechModel.Whisper) {
        status("Listening · ${model.name}")
        var last = 0L
        while (!stopped) {
            val window = snapshot(last)
            if (window == null) {
                Thread.sleep(100)
                continue
            }
            val audio = window.samples
            // A slow decode may finish after the speaker pauses. Check every new sample
            // so the end of an utterance is still decoded.
            val recent = minOf(window.end - last, audio.size.toLong()).toInt()
            last = window.end
            var energy = 0.0
            for (i in audio.size - recent until audio.size) energy += audio[i] * audio[i]
            if (sqrt(energy / recent) < 0.003) continue
            val start = System.nanoTime()
            val threads = if (android.os.Build.VERSION.SDK_INT >= 29 &&
                power.currentThermalStatus >= PowerManager.THERMAL_STATUS_MODERATE) 2 else 3
            val transcript = checkNotNull(model.engine.decode(model.handle, audio, threads)) { "Whisper decoding failed." }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            if (!publish(window, transcript.toString(Charsets.UTF_8), elapsedMs)) {
                last = synchronized(this) { audioStart }
            }
        }
    }

    private fun followSherpa(model: LoadedSpeechModel.Zipformer) {
        val recognizer = model.recognizer
        // Give the feature extractor some silence before the first spoken syllable.
        fun newStream() = recognizer.createStream().also { it.acceptWaveform(FloatArray(3_200), 16_000) }
        var stream = newStream()
        try {
            status("Listening · Zipformer")
            var last = 0L
            var streamRevision = revision
            var lastText = ""
            while (!stopped) {
                val window = snapshot(last, streaming = true)
                if (window == null) {
                    Thread.sleep(30)
                    continue
                }
                if (window.revision != streamRevision || window.end - window.samples.size > last) {
                    stream.release()
                    stream = newStream()
                    streamRevision = window.revision
                    lastText = ""
                }
                val start = System.nanoTime()
                if (window.samples.isNotEmpty()) stream.acceptWaveform(window.samples, 16_000)
                if (window.endOfInput) {
                    // Supply the model's right context even if Stop immediately follows a word.
                    stream.acceptWaveform(FloatArray(12_800), 16_000)
                    stream.inputFinished()
                }
                var decoded = false
                while (!stopped && recognizer.isReady(stream)) {
                    recognizer.decode(stream)
                    decoded = true
                }
                if (decoded || window.endOfInput) {
                    val text = recognizer.getResult(stream).text
                    val endpoint = window.endOfInput || recognizer.isEndpoint(stream)
                    if (text.isNotBlank() && (text != lastText || endpoint)) {
                        val elapsed = (System.nanoTime() - start) / 1_000_000
                        val accepted = publish(window, text, elapsed, allowShortCommand = endpoint, finalized = endpoint)
                        if (!accepted && !window.endOfInput) continue
                    }
                    if (window.endOfInput) break
                    if (endpoint) {
                        recognizer.reset(stream)
                        lastText = ""
                    } else {
                        lastText = text
                    }
                }
                last = window.end
            }
        } finally {
            stream.release()
        }
    }

    @Synchronized
    private fun publish(
        window: SpeechWindow,
        text: String,
        elapsedMs: Long,
        allowShortCommand: Boolean = true,
        finalized: Boolean = false,
    ): Boolean {
        if (window.revision != revision || stopped) return false
        onRecognition?.invoke(text)
        val lines = if (window.endOfInput) null else scrollCommandLines(text, allowShortCommand)
        val offset = if (lines == null) matcher.advance(text, finalized) else null
        if (lines != null) resetAudioWindow(window.end)
        val resultRevision = revision
        if (diagnostics && offset != null) {
            Log.d("CueFlowSpeech", "Matched script offset $offset in ${elapsedMs}ms")
        }
        main.post {
            if (!stopped && revision == resultRevision) {
                if (lines != null) {
                    onScroll(lines)
                    onStatus(if (lines < 0) "Scrolled up two lines" else "Scrolled down two lines")
                } else {
                    if (offset != null) onMatch(offset)
                    val engine = if (useSherpa) "Zipformer" else "Whisper"
                    onStatus("${if (offset == null) "Listening" else "Following"} · $engine · ${elapsedMs} ms")
                }
            }
        }
        return true
    }

    private data class SpeechWindow(val end: Long, val samples: FloatArray, val revision: Int, val endOfInput: Boolean)

    private fun status(text: String) {
        main.post { if (!stopped) onStatus(text) }
    }
}
