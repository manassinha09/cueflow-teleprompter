package com.novaflow.cueflow

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal fun CameraManager.recordingDiscoveryCandidates(
    cameraId: String,
    logical: CameraCharacteristics,
    preferredPhysical: String?,
    preview: Size,
    lenses: Map<String, CameraCharacteristics> = physicalRecordingLenses(logical),
): List<VideoMode> {
    val routes = linkedMapOf<String?, CameraCharacteristics>()
    if (preferredPhysical == null) routes[null] = logical
    else routes[preferredPhysical] = getCameraCharacteristics(preferredPhysical)
    lenses.forEach { (id, info) -> routes[id] = info }
    return routes.flatMap { (physical, info) ->
        info.recordingModes(preview, includeVendorModes = true,
            profiles = cameraRecordingProfiles(physical ?: cameraId), probeMissingModes = true)
            .map { it.copy(physicalCameraId = physical) }
    }.distinct().sortedWith(compareBy<VideoMode> { it.quality }.thenByDescending { it.fps })
}

/** Automatic, silent capability checks. Encoded buffers are discarded; no audio or files are captured. */
internal class RecordingModeDiscovery(
    private val context: Context,
    private val device: CameraDevice,
    private val preview: Surface,
    private val previewSize: Size,
    private val preferredPhysical: String?,
    private val useCache: Boolean = true,
) {
    private val cancelled = AtomicBoolean(false)
    private val discovered = AtomicReference<List<VideoMode>>()
    private val cache = context.getSharedPreferences("camera_mode_checks_v1", Context.MODE_PRIVATE)

    fun cancel() { cancelled.set(true) }
    fun availableModes(): List<VideoMode>? = discovered.get()

    fun start(base: List<VideoMode>, candidates: List<VideoMode>, onComplete: (List<VideoMode>) -> Unit) {
        Thread({
            val modes = runCatching { discover(base, candidates) }.getOrDefault(base)
            context.mainExecutor.execute { if (!cancelled.get()) onComplete(modes) }
        }, "CueFlow-camera-modes").start()
    }

    /** Must run off the main thread, which receives Camera2 callbacks. Also used by device tests. */
    fun discover(base: List<VideoMode>, candidates: List<VideoMode>): List<VideoMode> {
        val modes = base.filter { it.quality.supportsFrameRate(it.fps) }.toMutableList()
        discovered.set(modes.toList())
        // Fixed 60 fps must really deliver 60, even when the camera metadata claims support.
        for (mode in base.filter { it.fps == 60 && it.fpsRange.lower == 60 }) {
            if (cancelled.get()) return modes
            if (!check(mode)) modes.remove(mode)
            discovered.set(modes.toList())
        }
        for (mode in candidates) {
            if (cancelled.get()) return modes
            if (!mode.quality.supportsFrameRate(mode.fps)) continue
            if (modes.any { it.quality == mode.quality && it.fps == mode.fps }) continue
            if (check(mode)) modes += mode
            discovered.set(modes.toList())
        }
        return modes.sortedWith(compareBy<VideoMode> { it.quality }.thenBy { it.fps })
    }

    private fun cacheKey(mode: VideoMode): String = listOf(Build.FINGERPRINT, device.id,
        mode.physicalCameraId ?: preferredPhysical, previewSize, mode.quality, mode.fpsRange,
        mode.encoderName, mode.mimeType).joinToString("|")

    private fun check(mode: VideoMode): Boolean {
        val key = cacheKey(mode)
        if (useCache) {
            val value = cache.getLong(key, 0L)
            if (value > 0) return true
            // A low-light or busy-camera failure is temporary, not a permanent hardware verdict.
            if (value < 0 && System.currentTimeMillis() + value in 0 until 5 * 60_000L) return false
        }
        val supported = probe(mode)
        if (useCache && !cancelled.get()) {
            cache.edit().putLong(key, if (supported) System.currentTimeMillis() else -System.currentTimeMillis()).apply()
        }
        Log.i("CueFlowCameraModes", "Camera ${device.id}/${mode.physicalCameraId ?: preferredPhysical}: $mode verified=$supported")
        return supported
    }

    private fun await(latch: CountDownLatch, millis: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + millis
        while (!cancelled.get() && SystemClock.elapsedRealtime() < deadline) {
            if (latch.await(50, TimeUnit.MILLISECONDS)) return true
        }
        return false
    }

    private fun probe(mode: VideoMode): Boolean {
        var codec: MediaCodec? = null
        var surface: Surface? = null
        var started = false
        val session = AtomicReference<CameraCaptureSession>()
        val configured = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val finished = AtomicBoolean(false)
        try {
            codec = MediaCodec.createByCodecName(mode.encoderName)
            codec.configure(mode.encoderFormat(), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            surface = codec.createInputSurface()
            codec.start()
            started = true
            if (cancelled.get()) return false
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(preview)
                addTarget(surface)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, mode.fpsRange)
                // Stabilization can reduce the rate; it is negotiated separately when recording.
                set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
            }.build()
            val physical = mode.physicalCameraId ?: preferredPhysical
            val configuration = SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                listOf(OutputConfiguration(preview).apply { setPhysicalCameraId(physical) },
                    OutputConfiguration(surface).apply { setPhysicalCameraId(physical) }),
                context.mainExecutor, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(value: CameraCaptureSession) {
                        if (finished.get() || cancelled.get()) { value.close(); configured.countDown(); return }
                        session.set(value)
                        runCatching { value.setRepeatingRequest(request, null, null) }
                            .onFailure { session.set(null); value.close() }
                        configured.countDown()
                    }
                    override fun onConfigureFailed(value: CameraCaptureSession) { value.close(); configured.countDown() }
                    override fun onClosed(value: CameraCaptureSession) { closed.countDown() }
                }).apply { setSessionParameters(request) }
            device.createCaptureSession(configuration)
            if (!await(configured, 2_500) || session.get() == null) return false
            val buffer = MediaCodec.BufferInfo()
            val timestamps = mutableListOf<Long>()
            var correctDimensions = false
            var warmup = 0
            val deadline = SystemClock.elapsedRealtime() + 2_000
            while (!cancelled.get() && SystemClock.elapsedRealtime() < deadline) {
                val index = codec.dequeueOutputBuffer(buffer, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = codec.outputFormat
                    // Some encoders expose padded dimensions plus a crop describing the visible frame.
                    val width = if (format.containsKey("crop-right") && format.containsKey("crop-left"))
                        format.getInteger("crop-right") - format.getInteger("crop-left") + 1 else format.getInteger(MediaFormat.KEY_WIDTH)
                    val height = if (format.containsKey("crop-bottom") && format.containsKey("crop-top"))
                        format.getInteger("crop-bottom") - format.getInteger("crop-top") + 1 else format.getInteger(MediaFormat.KEY_HEIGHT)
                    correctDimensions = width == mode.width && height == mode.height
                }
                if (index >= 0) {
                    if (buffer.size > 0 && buffer.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        if (++warmup > 8) timestamps += buffer.presentationTimeUs
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (timestamps.size > 1 && timestamps.last() - timestamps.first() >= 900_000) break
                }
            }
            return !cancelled.get() && correctDimensions && deliversFrameRate(timestamps, mode.fps)
        } catch (_: Exception) {
            return false
        } finally {
            finished.set(true)
            session.get()?.let {
                runCatching { it.stopRepeating() }
                it.close()
                // Release camera ownership of the encoder surface before stopping the encoder.
                closed.await(1_000, TimeUnit.MILLISECONDS)
            }
            if (started) runCatching { codec?.stop() }
            runCatching { codec?.release() }
            surface?.release()
        }
    }
}
