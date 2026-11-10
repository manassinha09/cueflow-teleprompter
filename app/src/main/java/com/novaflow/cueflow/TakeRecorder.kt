package com.novaflow.cueflow

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Range
import android.view.Surface
import java.io.FileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal class RecordingModeRejected(message: String, cause: Throwable? = null) : IllegalStateException(message, cause)

internal data class VideoMode(
    val quality: VideoQuality,
    val bitrate: Int,
    val fpsRange: Range<Int>,
    val mimeType: String,
    val encoderName: String,
    val physicalCameraId: String? = null,
) {
    init {
        require(quality.supportsFrameRate(fpsRange.upper)) { "Unsupported recording preset: ${quality.label} at ${fpsRange.upper} fps" }
    }

    val width: Int get() = quality.width
    val height: Int get() = quality.height
    val fps: Int get() = fpsRange.upper

    override fun toString(): String {
        val rate = if (fpsRange.lower == fps) "$fps fps" else "up to $fps fps"
        return "${quality.label} / $rate"
    }

    fun encoderFormat(): MediaFormat =
        MediaFormat.createVideoFormat(mimeType, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
        }
}

/** Owns both encoders and the muxer on one worker. The camera writes directly to the video encoder. */
internal class TakeRecorder(
    private val context: Context,
    private val mode: VideoMode,
    private val rotation: Int,
    private val speech: SpeechFollower?,
    private val recordingFolder: RecordingFolder?,
    private val microphoneInput: MicrophoneInput,
    private val onMicrophoneRoute: (String) -> Unit,
    private val onMicrophoneInterrupted: () -> Unit,
    private val onSurface: (Surface) -> Unit,
    private val onFinished: (Result<SavedTake>) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val cameraReady = CountDownLatch(1)
    private val videoInputDetached = CountDownLatch(1)
    @Volatile private var stopping = false
    @Volatile private var stopReason: Throwable? = null
    @Volatile private var epochNs = 0L

    fun prepare() {
        Thread({ record() }, "CueFlow recorder").start()
    }

    fun startCapture() {
        epochNs = System.nanoTime()
        cameraReady.countDown()
    }

    fun stop(reason: Throwable? = null, waitForCamera: Boolean = false) {
        if (reason != null) stopReason = reason
        if (!waitForCamera) videoInputDetached.countDown()
        stopping = true
        cameraReady.countDown()
    }

    @SuppressLint("MissingPermission")
    private fun record() {
        var video: MediaCodec? = null
        var audio: MediaCodec? = null
        var microphone: AudioRecord? = null
        var inputCallback: AudioDeviceCallback? = null
        var microphoneInterrupted = false
        var surface: Surface? = null
        var muxer: TakeMuxer? = null
        var pendingUri: Uri? = null
        val resolver = context.contentResolver
        val audioManager = context.getSystemService(AudioManager::class.java)
        val fileName = "CueFlow-${System.currentTimeMillis()}.mp4"
        val finalized = runCatching {
            val (videoCodec, videoSurface) = try {
                val format = mode.encoderFormat()
                val codec = MediaCodec.createByCodecName(mode.encoderName)
                video = codec
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                val input = codec.createInputSurface()
                surface = input
                codec.start()
                codec to input
            } catch (error: Exception) {
                if (error is MediaCodec.CodecException && (error.isTransient || error.isRecoverable)) throw error
                throw RecordingModeRejected("The video encoder could not start $mode.", error)
            }

            val mic = openMicrophone(audioManager)
            microphone = mic
            val sampleRate = mic.sampleRate

            val audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audio = audioCodec
            audioCodec.configure(
                MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE, if (sampleRate == 16_000) 64_000 else 128_000)
                }, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE
            )
            audioCodec.start()

            val uri = checkNotNull(resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/CueFlow")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            })) { "Could not create a video file." }
            pendingUri = uri
            val writer = checkNotNull(resolver.openFileDescriptor(uri, "rw")).use { descriptor ->
                TakeMuxer(descriptor.fileDescriptor, rotation)
            }
            muxer = writer
            main.post { if (!stopping) onSurface(videoSurface) }
            check(cameraReady.await(10, TimeUnit.SECONDS)) { "The camera did not start recording." }
            if (stopping || epochNs == 0L) throw stopReason ?: IllegalStateException("Recording cancelled.")

            val inputsChanged = AtomicBoolean(false)
            inputCallback = audioManager.watchAudioInputs { inputsChanged.set(true) }
            microphoneInput.preferredDevice(audioManager)?.let { mic.setPreferredDevice(it) }
            val micStartNs = System.nanoTime()
            mic.startRecording()
            check(mic.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "Could not start the microphone." }
            speech?.start(sampleRate)
            val timestamp = AudioTimestamp()
            val samples = ShortArray(sampleRate / 50)
            fun audioPts(originNs: Long, frames: Long) =
                ((originNs - epochNs) / 1_000 + frames * 1_000_000L / sampleRate).coerceAtLeast(0L)
            var frames = 0L
            var audioOriginNs = micStartNs
            var clockEstablished = false
            var heldInput = -1
            var nextRouteCheckNs = 0L
            var lastRoute: String? = null
            var lastSamplesNs = micStartNs
            while (!stopping) {
                if (inputsChanged.getAndSet(false)) {
                    val preferred = microphoneInput.preferredDevice(audioManager)
                    if (mic.preferredDevice?.id != preferred?.id) mic.setPreferredDevice(preferred)
                }
                val nowNs = System.nanoTime()
                if (nowNs >= nextRouteCheckNs) {
                    val route = mic.routedDevice?.microphoneLabel() ?: "Checking mic…"
                    if (route != lastRoute) {
                        lastRoute = route
                        main.post { onMicrophoneRoute(route) }
                    }
                    nextRouteCheckNs = nowNs + TimeUnit.MILLISECONDS.toNanos(250)
                }
                if (heldInput < 0) heldInput = audioCodec.dequeueInputBuffer(0)
                if (heldInput >= 0) {
                    val input = checkNotNull(audioCodec.getInputBuffer(heldInput))
                    input.clear()
                    val count = mic.read(samples, 0, minOf(samples.size, input.capacity() / 2), AudioRecord.READ_NON_BLOCKING)
                    if (count < 0 || (count == 0 && nowNs - lastSamplesNs > TimeUnit.SECONDS.toNanos(2))) {
                        check(frames > 0) { "Could not capture microphone audio." }
                        microphoneInterrupted = true
                        main.post { onMicrophoneInterrupted() }
                        break
                    }
                    if (count > 0) {
                        lastSamplesNs = nowNs
                        if (!clockEstablished) {
                            if (mic.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_MONOTONIC) == AudioRecord.SUCCESS) {
                                audioOriginNs = timestamp.nanoTime - timestamp.framePosition * 1_000_000_000L / sampleRate
                            }
                            clockEstablished = true
                        }
                        input.order(ByteOrder.nativeOrder()).asShortBuffer().put(samples, 0, count)
                        audioCodec.queueInputBuffer(heldInput, 0, count * 2, audioPts(audioOriginNs, frames), 0)
                        heldInput = -1
                        frames += count
                        speech?.offer(samples, count)
                    }
                }
                writer.drain(videoCodec, VIDEO, epochNs / 1_000)
                writer.drain(audioCodec, AUDIO, 0)
                Thread.sleep(4)
            }

            speech?.finishInput()
            if (microphoneInterrupted) runCatching { mic.stop() } else mic.stop()
            // Keep consuming frames until the camera stops writing to the encoder surface.
            val detachDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4)
            while (System.nanoTime() < detachDeadline && !videoInputDetached.await(4, TimeUnit.MILLISECONDS)) {
                writer.drain(videoCodec, VIDEO, epochNs / 1_000)
                writer.drain(audioCodec, AUDIO, 0)
            }
            videoCodec.signalEndOfInputStream()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var audioEnded = false
            var videoEnded = false
            var audioSignalled = false
            while ((!audioEnded || !videoEnded) && System.nanoTime() < deadline) {
                if (!audioSignalled) {
                    if (heldInput < 0) heldInput = audioCodec.dequeueInputBuffer(0)
                    if (heldInput >= 0) {
                        audioCodec.queueInputBuffer(
                            heldInput, 0, 0, audioPts(audioOriginNs, frames), MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                        audioSignalled = true
                    }
                }
                if (!videoEnded) videoEnded = writer.drain(videoCodec, VIDEO, epochNs / 1_000)
                if (!audioEnded) audioEnded = writer.drain(audioCodec, AUDIO, 0)
                Thread.sleep(4)
            }
            check(videoEnded && audioEnded) { "Timed out finalizing the video." }
            writer.finish()
            uri
        }
        if (finalized.isFailure && stopping) {
            // A take can be cancelled while its camera session is still configuring.
            runCatching { videoInputDetached.await(4, TimeUnit.SECONDS) }
        }
        inputCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        if (finalized.isFailure && epochNs != 0L) speech?.stop()
        microphone?.let { runCatching { it.release() } }
        video?.let { runCatching { it.release() } }
        audio?.let { runCatching { it.release() } }
        surface?.release()
        muxer?.release()

        val result = finalized.mapCatching { uri ->
            val folder = recordingFolder
            if (folder != null) {
                val destination = runCatching { folder.copyRecording(resolver, uri, fileName) }.getOrNull()
                if (destination != null) {
                    runCatching { resolver.delete(uri, null, null) }
                    return@mapCatching SavedTake(destination, folder.name, microphoneInterrupted = microphoneInterrupted)
                }
            }
            check(resolver.update(uri, ContentValues().apply {
                put(MediaStore.Video.Media.IS_PENDING, 0)
            }, null, null) == 1) { "Could not publish the video." }
            SavedTake(uri, DEFAULT_RECORDING_FOLDER, usedFallback = folder != null, microphoneInterrupted = microphoneInterrupted)
        }
        if (result.isFailure) pendingUri?.let { runCatching { resolver.delete(it, null, null) } }
        main.post { onFinished(result) }
    }

    @SuppressLint("MissingPermission")
    private fun openMicrophone(audioManager: AudioManager): AudioRecord {
        val sources = if (microphoneInput == MicrophoneInput.AUTOMATIC && audioManager.usbMicrophone() != null) {
            listOf(MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.CAMCORDER)
        } else {
            listOf(MediaRecorder.AudioSource.CAMCORDER, MediaRecorder.AudioSource.MIC)
        }
        for (rate in listOf(48_000, 44_100, 16_000)) {
            val buffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (buffer <= 0) continue
            for (source in sources) {
                val mic = runCatching {
                    AudioRecord(
                        source, rate, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT, maxOf(buffer * 4, rate / 2),
                    )
                }.getOrNull() ?: continue
                if (mic.state == AudioRecord.STATE_INITIALIZED) return mic
                mic.release()
            }
        }
        error("Could not open the microphone.")
    }

    private class TakeMuxer(output: FileDescriptor, rotation: Int) {
        private val muxer = MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        private val tracks = intArrayOf(-1, -1)
        private val counts = intArrayOf(0, 0)
        private val info = MediaCodec.BufferInfo()
        private val pending = mutableListOf<Sample>()
        private var pendingBytes = 0
        private var started = false

        init {
            muxer.setOrientationHint(rotation)
        }

        fun drain(codec: MediaCodec, stream: Int, offsetUs: Long): Boolean {
            while (true) {
                when (val index = codec.dequeueOutputBuffer(info, 0)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> return false
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        tracks[stream] = muxer.addTrack(codec.outputFormat)
                        if (tracks.all { it >= 0 }) {
                            muxer.start()
                            started = true
                            pending.forEach { write(it.stream, ByteBuffer.wrap(it.bytes), it.info) }
                            pending.clear()
                        }
                    }
                    else -> if (index >= 0) {
                        val end = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        try {
                            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                                val data = checkNotNull(codec.getOutputBuffer(index))
                                info.presentationTimeUs = (info.presentationTimeUs - offsetUs).coerceAtLeast(0)
                                if (started) {
                                    write(stream, data, info)
                                } else {
                                    pendingBytes += info.size
                                    check(pendingBytes <= 8 * 1024 * 1024) { "Both media tracks did not start." }
                                    data.position(info.offset)
                                    data.limit(info.offset + info.size)
                                    val bytes = ByteArray(info.size)
                                    data.get(bytes)
                                    val copy = MediaCodec.BufferInfo().apply {
                                        set(0, bytes.size, info.presentationTimeUs, info.flags)
                                    }
                                    pending.add(Sample(stream, bytes, copy))
                                }
                            }
                        } finally {
                            codec.releaseOutputBuffer(index, false)
                        }
                        if (end) return true
                    }
                }
            }
        }

        private fun write(stream: Int, data: ByteBuffer, info: MediaCodec.BufferInfo) {
            muxer.writeSampleData(tracks[stream], data, info)
            counts[stream]++
        }

        fun finish() {
            check(started && counts.all { it > 0 }) { "The recording is missing audio or video." }
            muxer.stop()
        }

        fun release() {
            runCatching { muxer.release() }
        }

        private data class Sample(val stream: Int, val bytes: ByteArray, val info: MediaCodec.BufferInfo)
    }

    companion object {
        private const val VIDEO = 0
        private const val AUDIO = 1
    }
}
