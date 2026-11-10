package com.novaflow.cueflow

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.CamcorderProfile
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.os.Build
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteOrder
import java.nio.ShortBuffer
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class RecordedTakeTest {
    @Test
    fun retainsLegacyScriptAndRestoresTheSelectedLibraryScript() {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "script-library-test-${UUID.randomUUID()}"
        val preferences = target.getSharedPreferences(name, 0)
        try {
            preferences.edit().putString("script", "Existing script").apply()
            val original = ScriptLibrary.load(preferences, "Sample script")
            val extra = SavedScript("extra", "Second script", "**Second** script")
            original.copy(scripts = original.scripts + extra, selectedId = extra.id).save(preferences)
            val restored = ScriptLibrary.load(preferences, "Sample script")
            assertTrue(restored.scripts.any { it.markdown == "Existing script" })
            assertTrue(restored.selected == extra)
        } finally {
            target.deleteSharedPreferences(name)
        }
    }

    @Test
    fun savesCompleteClipInTheSelectedFolder() {
        val args = InstrumentationRegistry.getArguments()
        val expectedFolder = args.getString("testFolderId")
        val path = args.getString("takePath")
        assumeTrue(expectedFolder != null && path != null)
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = target.getSharedPreferences("prompter", 0)
        val tree = Uri.parse(checkNotNull(preferences.getString("recording_folder_uri", null)))
        check(DocumentsContract.getTreeDocumentId(tree) == expectedFolder) { "Unexpected test folder." }
        val folder = RecordingFolder(tree, checkNotNull(preferences.getString("recording_folder_name", null)))
        val source = File(checkNotNull(path))
        val resolver = target.contentResolver
        val output = folder.copyRecording(resolver, Uri.fromFile(source), "CueFlow-QA-${System.nanoTime()}.mp4")
        try {
            val expected = source.inputStream().use(::digest)
            val actual = checkNotNull(resolver.openInputStream(output)).use(::digest)
            assertTrue("The selected folder should contain the full recording.", expected.contentEquals(actual))
        } finally {
            DocumentsContract.deleteDocument(resolver, output)
        }
    }

    private fun digest(stream: InputStream): ByteArray {
        val hash = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = stream.read(buffer)
            if (count == -1) return hash.digest()
            hash.update(buffer, 0, count)
        }
    }

    @Test
    fun decodesVideoAndMicrophoneAudioFromASavedTake() {
        val path = InstrumentationRegistry.getArguments().getString("takePath")
        assumeTrue("Supply a CueFlow recording with the takePath instrumentation argument.", path != null)
        val takePath = checkNotNull(path)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(takePath)
            val frame = checkNotNull(retriever.getFrameAtTime(500_000, MediaMetadataRetriever.OPTION_CLOSEST)) {
                "Could not decode a video frame."
            }
            assertTrue(frame.width > 0 && frame.height > 0)
            frame.recycle()
        } finally {
            retriever.release()
        }

        assertTrue("The recorded audio should decode to an audible signal.", decodeAudio(takePath) { pcm ->
            while (pcm.hasRemaining()) {
                if (kotlin.math.abs(pcm.get().toInt()) > 32) return@decodeAudio true
            }
            false
        })
    }

    @Test
    fun replayTakeThroughWhisper() {
        val path = InstrumentationRegistry.getArguments().getString("takePath")
        assumeTrue(path != null)
        val samples = ArrayList<Float>()
        var sum = 0
        var count = 0
        decodeAudio(checkNotNull(path)) { pcm ->
            while (pcm.hasRemaining()) {
                sum += pcm.get()
                if (++count == 3) {
                    samples.add(sum / (3 * 32768f))
                    count = 0
                    sum = 0
                }
            }
            samples.size >= 160_000
        }
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val model = importedWhisperModel(target)
        assumeTrue("Import the Whisper model in CueFlow before replaying a take.", model.isFile)
        val engine = WhisperEngine()
        val handle = engine.open(model.absolutePath)
        check(handle != 0L) { "Could not open the imported model." }
        try {
            val matcher = ScriptMatcher(SAMPLE_SCRIPT)
            for (end in 12_000..samples.size step 12_000) {
                val start = (end - 48_000).coerceAtLeast(0)
                val window = FloatArray(end - start) { samples[start + it] }
                val before = System.nanoTime()
                val text = checkNotNull(engine.decode(handle, window)).toString(Charsets.UTF_8).trim()
                val elapsed = (System.nanoTime() - before) / 1_000_000
                val offset = matcher.advance(text)
                Log.i("CueFlowProbe", "Window ending ${end / 16}ms, decode=${elapsed}ms, chars=${text.length}, match=$offset")
            }
        } finally {
            engine.close(handle)
        }
    }

    @Test
    fun liveFollowerPublishesScriptOffsetsDuringReplay() {
        val path = InstrumentationRegistry.getArguments().getString("takePath")
        assumeTrue(path != null)
        val captured = ShortArray(480_000)
        var count = 0
        decodeAudio(checkNotNull(path)) { pcm ->
            val length = minOf(pcm.remaining(), captured.size - count)
            pcm.get(captured, count, length)
            count += length
            count == captured.size
        }
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue("Import the Whisper model in CueFlow before replaying a take.", importedWhisperModel(target).isFile)
        val offset = AtomicInteger()
        val status = AtomicReference("")
        val follower = SpeechFollower(target, SAMPLE_SCRIPT, { offset.set(it) }, { status.set(it) }, useSherpa = false)
        follower.start()
        try {
            val chunk = ShortArray(960)
            val started = System.nanoTime()
            for (start in 0 until count step chunk.size) {
                val length = minOf(chunk.size, count - start)
                captured.copyInto(chunk, 0, start, start + length)
                val due = started + (start + length) * 1_000_000_000L / 48_000
                val waitMs = (due - System.nanoTime()) / 1_000_000
                if (waitMs > 0) Thread.sleep(waitMs)
                follower.offer(chunk, length)
            }
            val matchedDuringReplay = offset.get()
            Log.i("CueFlowProbe", "Live replay ended at script offset $matchedDuringReplay. ${status.get()}")
            assertTrue("Expected a highlight update while audio was arriving. ${status.get()}", matchedDuringReplay > 0)
        } finally {
            follower.stop()
        }
    }

    @Test
    fun reportPixelCameraModes() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val cameras = target.getSystemService(CameraManager::class.java)
        for (id in cameras.cameraIdList) {
            val info = cameras.getCameraCharacteristics(id)
            val preview = info.choosePreviewSize()
            val modes = info.recordingModes(preview)
            Log.i("CueFlowProbe", "Camera $id uses preview $preview and offers $modes")
            val streamMap = checkNotNull(info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
            val sizes = streamMap.getOutputSizes(MediaCodec::class.java).orEmpty()
            val uhd = Size(3840, 2160)
            val duration = if (uhd in sizes) streamMap.getOutputMinFrameDuration(MediaCodec::class.java, uhd) else -1
            val profiles = CamcorderProfile.getAll(id, CamcorderProfile.QUALITY_2160P)?.videoProfiles.orEmpty()
            val yuv = streamMap.getOutputSizes(ImageFormat.YUV_420_888).orEmpty()
            val highResolution = info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
            val maxMode = highResolution?.getOutputSizes(MediaCodec::class.java).orEmpty()
            val queried = if (Build.VERSION.SDK_INT >= 35 && cameras.isCameraDeviceSetupSupported(id)) {
                runCatching {
                    val outputs = listOf(
                        OutputConfiguration(Size(1280, 720), SurfaceTexture::class.java),
                        OutputConfiguration(uhd, MediaCodec::class.java),
                    )
                    val session = SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR, outputs, target.mainExecutor,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) = Unit
                            override fun onConfigureFailed(session: CameraCaptureSession) = Unit
                        },
                    )
                    cameras.getCameraDeviceSetup(id).isSessionConfigurationSupported(session).toString()
                }.getOrElse { it.javaClass.simpleName }
            } else "unavailable"
            Log.i("CueFlowProbe", "Camera $id, facing=${info.get(CameraCharacteristics.LENS_FACING)}, " +
                "UHD frame duration=$duration, UHD profiles=${profiles.map { it.mediaType + "/" + it.frameRate }}, " +
                "UHD YUV=${uhd in yuv}, UHD maximum-resolution mode=${uhd in maxMode}, UHD session query=$queried")
            if (Build.VERSION.SDK_INT >= 35 && cameras.isCameraDeviceSetupSupported(id)) {
                val setup = cameras.getCameraDeviceSetup(id)
                for (mode in modes.filter { it.fps == 60 && it.height >= 1080 }) {
                    val configuration = SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        listOf(
                            OutputConfiguration(preview, SurfaceTexture::class.java),
                            OutputConfiguration(Size(mode.width, mode.height), MediaCodec::class.java),
                        ),
                        target.mainExecutor,
                        object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(session: CameraCaptureSession) = Unit
                            override fun onConfigureFailed(session: CameraCaptureSession) = Unit
                        },
                    )
                    if (info.availableSessionKeys?.contains(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE) == true) {
                        configuration.setSessionParameters(setup.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                            .apply { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, mode.fpsRange) }.build())
                    }
                    val supported = setup.isSessionConfigurationSupported(configuration)
                    assertTrue("Camera $id rejected the advertised mode $mode.", supported)
                    Log.i("CueFlowProbe", "Camera $id accepted $mode with preview $preview")
                }
            }
        }
    }

    private fun decodeAudio(takePath: String, onPcm: (ShortBuffer) -> Boolean): Boolean {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(takePath)
            val track = (0 until extractor.trackCount).first {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val decoder = MediaCodec.createDecoderByType(checkNotNull(format.getString(MediaFormat.KEY_MIME)))
            try {
                decoder.configure(format, null, null, 0)
                decoder.start()
                return readAudio(decoder, extractor, onPcm)
            } finally {
                decoder.release()
            }
        } finally {
            extractor.release()
        }
    }

    private fun readAudio(decoder: MediaCodec, extractor: MediaExtractor, onPcm: (ShortBuffer) -> Boolean): Boolean {
        val deadline = System.nanoTime() + 15_000_000_000L
        val info = MediaCodec.BufferInfo()
        var endQueued = false
        while (System.nanoTime() < deadline) {
            if (!endQueued) {
                val index = decoder.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    val data = checkNotNull(decoder.getInputBuffer(index))
                    val size = extractor.readSampleData(data, 0)
                    if (size < 0) {
                        decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        endQueued = true
                    } else {
                        decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                        extractor.advance()
                    }
                }
            }
            val index = decoder.dequeueOutputBuffer(info, 10_000)
            if (index >= 0) {
                try {
                    val data = checkNotNull(decoder.getOutputBuffer(index))
                    data.position(info.offset)
                    data.limit(info.offset + info.size)
                    val pcm = data.slice().order(ByteOrder.nativeOrder()).asShortBuffer()
                    if (onPcm(pcm)) return true
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return false
                } finally {
                    decoder.releaseOutputBuffer(index, false)
                }
            }
        }
        return false
    }

    private companion object {
        const val SAMPLE_SCRIPT = "Welcome to CueFlow. This is a test of the built-in camera and live teleprompter. " +
            "The script should move as you read aloud. Take a short pause before continuing. " +
            "When you are ready, stop recording and play back the saved video."
    }
}
