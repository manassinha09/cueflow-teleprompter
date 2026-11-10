package com.novaflow.cueflow

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import android.util.Log
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import java.io.File
import java.util.concurrent.Executors

/** Owns one selected native model. Loading, inference and release all run on the same worker. */
internal class SpeechModelLoader(
    private val context: Context,
    private val useSherpa: Boolean,
) : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT)
            task.run()
        }, "CueFlow speech")
    }
    private var model: LoadedSpeechModel? = null
    private var closed = false

    @Synchronized
    fun execute(task: () -> Unit): Boolean {
        if (closed) return false
        worker.execute(task)
        return true
    }

    fun preload(onFinished: (Boolean) -> Unit) {
        execute { onFinished(runCatching { getOrLoad() }.isSuccess) }
    }

    /** Called only from the worker. A failed preload can be retried when recording starts. */
    fun getOrLoad(onLoading: () -> Unit = {}): LoadedSpeechModel {
        model?.let { return it }
        onLoading()
        val started = System.nanoTime()
        val loaded = if (useSherpa) {
            LoadedSpeechModel.Zipformer(openSherpaRecognizer(context.assets))
        } else {
            val file = importedWhisperModel(context)
            check(file.isFile) { "The imported model is missing." }
            val engine = WhisperEngine()
            val handle = engine.open(file.absolutePath)
            check(handle != 0L) { "Whisper could not open the model." }
            LoadedSpeechModel.Whisper(engine, handle, file.name)
        }
        model = loaded
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            Log.d("CueFlowSpeech", "${if (useSherpa) "Zipformer" else "Whisper"} model loaded in ${(System.nanoTime() - started) / 1_000_000} ms")
        }
        return loaded
    }

    fun clear() {
        execute { releaseModel() }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        worker.execute { releaseModel() }
        worker.shutdown()
    }

    private fun releaseModel() {
        model?.close()
        model = null
    }
}

internal fun importedWhisperModel(context: Context): File = File(context.filesDir, "imported-whisper.bin")

internal sealed interface LoadedSpeechModel : AutoCloseable {
    class Whisper(val engine: WhisperEngine, val handle: Long, val name: String) : LoadedSpeechModel {
        override fun close() = engine.close(handle)
    }

    class Zipformer(val recognizer: OnlineRecognizer) : LoadedSpeechModel {
        override fun close() = recognizer.release()
    }
}
