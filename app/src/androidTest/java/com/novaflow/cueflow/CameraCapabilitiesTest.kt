package com.novaflow.cueflow

import android.annotation.SuppressLint
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.HardwareBuffer
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.CamcorderProfile
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Opt-in hardware diagnostics; no preferences or user recordings are changed. */
@RunWith(AndroidJUnit4::class)
class CameraCapabilitiesTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val manager = context.getSystemService(CameraManager::class.java)

    @Test
    fun reportCameraCapabilities() {
        Log.i(TAG, "Device ${Build.MANUFACTURER} ${Build.MODEL}, ${Build.DEVICE}, API ${Build.VERSION.SDK_INT}")
        for (id in manager.cameraIdList) {
            val info = manager.getCameraCharacteristics(id)
            Log.i(TAG, "Camera $id facing=${info.get(CameraCharacteristics.LENS_FACING)}, physical=${info.physicalCameraIds}, " +
                "AE=${info.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList()}, " +
                "setup=${Build.VERSION.SDK_INT >= 35 && manager.isCameraDeviceSetupSupported(id)}")
            val streams = checkNotNull(info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
            Log.i(TAG, "Camera $id encoder sizes=${streams.getOutputSizes(MediaCodec::class.java).toList()}")
            for (size in listOf(Size(3840, 2160), Size(1920, 1080), Size(1280, 720), info.choosePreviewSize()).distinct()) {
                val video = runCatching { streams.getOutputMinFrameDuration(MediaCodec::class.java, size) }.getOrNull()
                val preview = runCatching { streams.getOutputMinFrameDuration(SurfaceTexture::class.java, size) }.getOrNull()
                Log.i(TAG, "Camera $id $size videoMinNs=$video previewMinNs=$preview")
            }
            Log.i(TAG, "Camera $id highSpeed=${streams.highSpeedVideoSizes.map { it to streams.getHighSpeedVideoFpsRangesFor(it).toList() }}")
            if (Build.VERSION.SDK_INT >= 31) {
                for (quality in listOf(CamcorderProfile.QUALITY_2160P, CamcorderProfile.QUALITY_1080P)) {
                    Log.i(TAG, "Camera $id profile=$quality: ${CamcorderProfile.getAll(id, quality)?.videoProfiles?.map { "${it.width}x${it.height}@${it.frameRate} ${it.mediaType}" }}")
                }
            }
            for (key in info.keys.filter { it.name.startsWith("com.lenovo.moto.quadra_cfa") }) {
                val value = runCatching { info.get(key) }.getOrNull()
                Log.i(TAG, "Camera $id ${key.name}=${if (value is IntArray) value.toList() else value}")
            }
            val vendorConfig = runCatching {
                info.get(CameraCharacteristics.Key("com.lenovo.moto.quadra_cfa.video_config", IntArray::class.java))?.toList()
            }
            Log.i(TAG, "Camera $id explicit Motorola video_config=$vendorConfig")
            Log.i(TAG, "Camera $id modes=${info.recordingModes(info.choosePreviewSize())}")
        }
        for (codec in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { it.isEncoder }) {
            for (mime in codec.supportedTypes.filter { it in listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC) }) {
                val caps = codec.getCapabilitiesForType(mime).videoCapabilities ?: continue
                Log.i(TAG, "Encoder ${codec.name} $mime hardware=${codec.isHardwareAccelerated}, " +
                    "4K30=${caps.areSizeAndRateSupported(3840, 2160, 30.0)}, FHD60=${caps.areSizeAndRateSupported(1920, 1080, 60.0)}")
            }
        }
    }

    /** Probe an explicit mode even if absent from metadata: -e probeCamera 1 -e probeQuality 4K -e probeFps 30. */
    @SuppressLint("MissingPermission")
    @Test
    fun probeExplicitRecordingMode() {
        val args = InstrumentationRegistry.getArguments()
        val id = args.getString("probeCamera")
        assumeTrue("Pass probeCamera to run the explicit recording probe.", id != null)
        val quality = VideoQuality.entries.first { it.label == (args.getString("probeQuality") ?: "4K") }
        val fps = args.getString("probeFps")?.toInt() ?: 30
        val format = MediaFormat.MIMETYPE_VIDEO_AVC
        val codecInfo = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.first {
            it.isEncoder && it.isHardwareAccelerated && format in it.supportedTypes &&
                it.getCapabilitiesForType(format).videoCapabilities?.areSizeAndRateSupported(quality.width, quality.height, fps.toDouble()) == true
        }
        val mode = VideoMode(quality, quality.bitrate, Range(fps, fps), format, codecInfo.name)
        val thread = HandlerThread("CameraProbe").apply { start() }
        val handler = Handler(thread.looper)
        val opened = CountDownLatch(1)
        val configured = CountDownLatch(1)
        val device = AtomicReference<CameraDevice>()
        val session = AtomicReference<CameraCaptureSession>()
        val failure = AtomicReference<String>()
        val codec = MediaCodec.createByCodecName(mode.encoderName)
        val previewSize = if (fps > 30) Size(1920, 1080) else Size(1280, 720)
        val preview = ImageReader.newInstance(previewSize.width, previewSize.height, ImageFormat.PRIVATE, 3, HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE)
        var input: android.view.Surface? = null
        var started = false
        try {
            preview.setOnImageAvailableListener({ reader -> reader.acquireLatestImage()?.close() }, handler)
            codec.configure(mode.encoderFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            input = codec.createInputSurface()
            codec.start()
            started = true
            manager.openCamera(checkNotNull(id), object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) { device.set(camera); opened.countDown() }
                override fun onDisconnected(camera: CameraDevice) { camera.close(); failure.set("disconnected"); opened.countDown() }
                override fun onError(camera: CameraDevice, error: Int) { camera.close(); failure.set("camera error $error"); opened.countDown() }
            }, handler)
            assertTrue("Camera open timeout", opened.await(10, TimeUnit.SECONDS))
            val camera = checkNotNull(device.get()) { failure.get() ?: "No camera" }
            val request = camera.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(preview.surface)
                addTarget(input)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, mode.fpsRange)
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }.build()
            val configuration = SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(preview.surface), OutputConfiguration(input)),
                { handler.post(it) }, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(value: CameraCaptureSession) { session.set(value); configured.countDown() }
                    override fun onConfigureFailed(value: CameraCaptureSession) { value.close(); failure.set("configuration rejected"); configured.countDown() }
                }).apply { setSessionParameters(request) }
            Log.i(TAG, "Explicit camera $id $mode query=${runCatching { camera.isSessionConfigurationSupported(configuration) }}")
            camera.createCaptureSession(configuration)
            assertTrue("Session timeout", configured.await(10, TimeUnit.SECONDS))
            val capture = checkNotNull(session.get()) { failure.get() ?: "No session" }
            capture.setRepeatingRequest(request, object : CameraCaptureSession.CaptureCallback() {
                private var reported = false
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    if (!reported) {
                        reported = true
                        Log.i(TAG, "Explicit camera $id capture crop=${result.get(CaptureResult.SCALER_CROP_REGION)} duration=${result.get(CaptureResult.SENSOR_FRAME_DURATION)}")
                    }
                }
            }, handler)
            val buffer = MediaCodec.BufferInfo()
            val deadline = System.nanoTime() + 5_000_000_000L
            val timestamps = mutableListOf<Long>()
            while (System.nanoTime() < deadline) {
                val index = codec.dequeueOutputBuffer(buffer, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) Log.i(TAG, "Explicit output ${codec.outputFormat}")
                if (index >= 0) {
                    if (buffer.size > 0 && buffer.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) timestamps += buffer.presentationTimeUs
                    codec.releaseOutputBuffer(index, false)
                }
            }
            assertTrue("No sustained frames for camera $id $mode", timestamps.size > fps * 2)
            val measured = (timestamps.size - 1) * 1_000_000.0 / (timestamps.last() - timestamps.first())
            Log.i(TAG, "Explicit camera $id $mode: ${timestamps.size} frames, measured=$measured fps")
            assertTrue("Incorrect frame rate $measured", measured in fps * 0.9..fps * 1.1)
        } finally {
            runCatching { session.get()?.stopRepeating() }
            session.get()?.close()
            device.get()?.close()
            if (started) runCatching { codec.stop() }
            codec.release()
            input?.release()
            preview.close()
            thread.quitSafely()
            thread.join(3_000)
        }
    }

    companion object { private const val TAG = "CueFlowProbe" }
}
