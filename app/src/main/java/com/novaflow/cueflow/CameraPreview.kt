package com.novaflow.cueflow

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.HardwareBuffer
import android.graphics.ImageFormat
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.CamcorderProfile
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.Surface
import android.view.ViewTreeObserver
import android.graphics.SurfaceTexture
import android.graphics.Rect
import android.util.Range
import android.widget.FrameLayout
import androidx.annotation.RequiresApi
import androidx.camera.viewfinder.core.ImplementationMode
import androidx.camera.viewfinder.core.ScaleType
import androidx.camera.viewfinder.core.TransformationInfo
import androidx.camera.viewfinder.core.ViewfinderSurfaceRequest
import androidx.camera.viewfinder.core.ViewfinderSurfaceSession
import androidx.camera.viewfinder.core.camera2.Camera2TransformationInfo
import androidx.camera.viewfinder.view.ViewfinderView
import androidx.compose.ui.geometry.Offset
import java.util.concurrent.Future
import kotlin.math.abs
import kotlin.math.roundToInt

private fun CameraCharacteristics.previewSizes(): List<Size> =
    get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(SurfaceTexture::class.java).orEmpty().toList()

private fun CameraCharacteristics.supportsUhdOutput(): Boolean =
    get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(MediaCodec::class.java)
        ?.contains(Size(VideoQuality.UHD.width, VideoQuality.UHD.height)) == true

internal fun CameraCharacteristics.choosePreviewSize(availableSizes: List<Size> = previewSizes()): Size {
    val streams = checkNotNull(get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
    val sizes = availableSizes.sortedBy {
        abs(it.width.toLong() * it.height - 1280L * 720) +
            abs(it.width.toLong() * 9 - it.height.toLong() * 16) * 100
    }
    val preferred = sizes.firstOrNull() ?: error("This camera has no preview stream.")
    val encoderSizes = streams.getOutputSizes(MediaCodec::class.java).orEmpty()
    val fastEncoder = VideoQuality.entries.filter { it.maxFps >= 60 }.any { quality ->
        val size = Size(quality.width, quality.height)
        size in encoderSizes && streams.getOutputMinFrameDuration(MediaCodec::class.java, size).let { it > 0 && fitsFrameRate(it, 60) }
    }
    val has60Fps = get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty().any { it.upper >= 60 } || fastEncoder
    if (!has60Fps || fitsFrameRate(streams.getOutputMinFrameDuration(SurfaceTexture::class.java, preferred), 60)) {
        return preferred
    }
    // A fast preview must not cap the recording rate; preserve 16:9 where possible.
    val fastSizes = sizes.filter {
        fitsFrameRate(streams.getOutputMinFrameDuration(SurfaceTexture::class.java, it), 60)
    }
    return fastSizes.firstOrNull { it.width * 9 == it.height * 16 } ?: fastSizes.firstOrNull() ?: preferred
}

private val recordingFrameRates = listOf(24, 30, 60)

// Allow fractional rates such as 59.94 fps when evaluating the sensor limit.
private fun fitsFrameRate(duration: Long, fps: Int): Boolean = duration <= 1_001_000_000L / fps

internal fun CameraCharacteristics.recordingModes(
    previewSize: Size,
    includeVendorModes: Boolean = false,
    profiles: List<AdditionalVideoConfiguration> = emptyList(),
    probeMissingModes: Boolean = false,
): List<VideoMode> {
    val streams = get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return emptyList()
    if (previewSize !in streams.getOutputSizes(SurfaceTexture::class.java).orEmpty()) return emptyList()
    val previewDuration = streams.getOutputMinFrameDuration(SurfaceTexture::class.java, previewSize)
    val encoderSizes = streams.getOutputSizes(MediaCodec::class.java).orEmpty()
    // Some Motorola cameras omit working 4K outputs from the standard stream map and profiles.
    // Extra configurations are hints; automatic discovery must test their actual encoded output.
    val additional = profiles + if (includeVendorModes) parseMotorolaVideoConfigurations(runCatching {
        get(CameraCharacteristics.Key("com.lenovo.moto.quadra_cfa.video_config", IntArray::class.java))
    }.getOrNull()) else emptyList()
    val rates = get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES).orEmpty()
        .filter { it.upper in recordingFrameRates }
        .groupBy { it.upper }.values.map { ranges -> ranges.maxBy { it.lower } }
        .sortedBy { it.upper }
    val codecs = MediaCodecList(MediaCodecList.REGULAR_CODECS)
    val formats = listOf(MediaFormat.MIMETYPE_VIDEO_AVC, MediaFormat.MIMETYPE_VIDEO_HEVC)
    val encoders = formats.flatMap { mime ->
        codecs.codecInfos.asSequence().filter { it.isEncoder && mime in it.supportedTypes }
            .map { Triple(mime, it, it.getCapabilitiesForType(mime)) }
            .filter { (_, _, caps) -> MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface in caps.colorFormats }
            .toList()
    }.sortedBy { (_, info) -> !info.isHardwareAccelerated }
    return VideoQuality.entries.flatMap { quality ->
        val size = Size(quality.width, quality.height)
        val listed = size in encoderSizes
        val extraRates = additional.filter { it.width == size.width && it.height == size.height }.map { it.fps }
        if (!listed && extraRates.isEmpty() && !probeMissingModes) return@flatMap emptyList()
        val duration = if (listed) maxOf(previewDuration, streams.getOutputMinFrameDuration(MediaCodec::class.java, size))
            else previewDuration
        val candidateRates = rates.toMutableList()
        if (probeMissingModes) {
            for (fps in listOf(30, 60).filter(quality::supportsFrameRate)) {
                val sensorDuration = if (listed) streams.getOutputMinFrameDuration(MediaCodec::class.java, size) else 0L
                val evidence = fps == 30 || rates.any { it.upper >= fps } || extraRates.any { it >= fps } ||
                    (sensorDuration > 0 && fitsFrameRate(sensorDuration, fps))
                if (evidence && candidateRates.none { it.upper == fps }) candidateRates += Range(fps, fps)
            }
        }
        candidateRates.flatMap rates@{ range ->
            if (!quality.supportsFrameRate(range.upper)) return@rates emptyList()
            if (probeMissingModes) {
                // Discover maximum presets, leaving 24 fps to explicitly advertised modes.
                if (range.upper == 24 || !fitsFrameRate(previewDuration, range.upper)) return@rates emptyList()
            } else {
                if (!listed && range.upper !in extraRates) return@rates emptyList()
                if (!fitsFrameRate(duration, range.upper)) return@rates emptyList()
            }
            val targetBitrate = if (range.upper == 60) quality.bitrate * 3 / 2 else quality.bitrate
            val supportedEncoders = encoders.mapNotNull { (mime, info, codec) ->
                // Speculative large streams should not stall startup on a software encoder.
                // Standard advertised modes still allow software codecs and retain their normal fallback.
                if (probeMissingModes && !info.isHardwareAccelerated) return@mapNotNull null
                val video = codec.videoCapabilities ?: return@mapNotNull null
                if (!video.areSizeAndRateSupported(size.width, size.height, range.upper.toDouble())) {
                    return@mapNotNull null
                }
                val mode = VideoMode(
                    quality, video.bitrateRange.clamp(targetBitrate), range, mime, info.name,
                )
                mode.takeIf { codec.isFormatSupported(it.encoderFormat()) }
            }
            // Try HEVC as well if an AVC encoder advertises a mode but cannot actually run it.
            if (probeMissingModes) supportedEncoders.distinctBy { it.mimeType } else supportedEncoders.take(1)
        }
    }
}

@Suppress("DEPRECATION")
internal fun cameraRecordingProfiles(cameraId: String): List<AdditionalVideoConfiguration> =
    listOf(CamcorderProfile.QUALITY_HIGH, CamcorderProfile.QUALITY_2160P, CamcorderProfile.QUALITY_1080P,
        CamcorderProfile.QUALITY_720P).flatMap { quality ->
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                CamcorderProfile.getAll(cameraId, quality)?.videoProfiles.orEmpty().map {
                    AdditionalVideoConfiguration(it.width, it.height, it.frameRate)
                }
            } else {
                val id = cameraId.toIntOrNull() ?: return@runCatching emptyList()
                if (!CamcorderProfile.hasProfile(id, quality)) return@runCatching emptyList()
                val profile = CamcorderProfile.get(id, quality)
                listOf(AdditionalVideoConfiguration(profile.videoFrameWidth, profile.videoFrameHeight, profile.videoFrameRate))
            }
        }.getOrDefault(emptyList())
    }.distinct()

@RequiresApi(35)
private fun CameraManager.supportsRecording(
    cameraId: String,
    physicalId: String?,
    preview: Size,
    mode: VideoMode,
): Boolean? = runCatching {
    val setup = getCameraDeviceSetup(cameraId)
    val request = setup.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
        set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, mode.fpsRange)
    }.build()
    val configuration = recordingProbe(listOf(
        OutputConfiguration(preview, SurfaceTexture::class.java).apply { setPhysicalCameraId(physicalId) },
        OutputConfiguration(Size(mode.width, mode.height), MediaCodec::class.java).apply { setPhysicalCameraId(physicalId) },
    )).apply { setSessionParameters(request) }
    setup.isSessionConfigurationSupported(configuration)
}.getOrNull()

private fun recordingProbe(outputs: List<OutputConfiguration>) = SessionConfiguration(
    SessionConfiguration.SESSION_REGULAR, outputs, { it.run() },
    object : CameraCaptureSession.StateCallback() {
        override fun onConfigured(session: CameraCaptureSession) = Unit
        override fun onConfigureFailed(session: CameraCaptureSession) = Unit
    },
)

@Suppress("DEPRECATION")
internal fun CameraDevice.supportsRecording(preview: Surface, physicalId: String?, mode: VideoMode): Boolean =
    runCatching {
        // Older Camera2 queries need actual surfaces. PRIVATE with video-encode usage matches the encoder stream.
        ImageReader.newInstance(mode.width, mode.height, ImageFormat.PRIVATE, 1, HardwareBuffer.USAGE_VIDEO_ENCODE)
            .use { output ->
                val request = createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, mode.fpsRange)
                }.build()
                isSessionConfigurationSupported(recordingProbe(listOf(
                    OutputConfiguration(preview).apply { setPhysicalCameraId(physicalId) },
                    OutputConfiguration(output.surface).apply { setPhysicalCameraId(physicalId) },
                )).apply { setSessionParameters(request) })
            }
    }.getOrDefault(false)

internal fun CameraManager.physicalRecordingLenses(logical: CameraCharacteristics): Map<String, CameraCharacteristics> =
    buildMap {
        val facing = logical.get(CameraCharacteristics.LENS_FACING)
        for (id in logical.physicalCameraIds.sorted()) {
            val info = runCatching { getCameraCharacteristics(id) }.getOrNull() ?: continue
            if (info.get(CameraCharacteristics.LENS_FACING) == facing) put(id, info)
        }
    }

internal fun CameraCharacteristics.chooseRecordingPreview(
    physicalLenses: Collection<CameraCharacteristics>,
): Size {
    val preferred = choosePreviewSize()
    val uhdLenses = physicalLenses.filter { it.supportsUhdOutput() }
    if (uhdLenses.any { preferred in it.previewSizes() }) return preferred
    val available = previewSizes().toSet()
    for (lens in uhdLenses) {
        val shared = lens.previewSizes().filter { it in available }
        if (shared.isNotEmpty()) return lens.choosePreviewSize(shared)
    }
    return preferred
}

internal fun CameraManager.choosePhysicalFrontCamera(cameraId: String, logical: CameraCharacteristics): String? {
    if (Build.VERSION.SDK_INT < 35 ||
        logical.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_FRONT ||
        logical.recordingModes(logical.choosePreviewSize()).any {
            it.quality == VideoQuality.UHD && it.mimeType == MediaFormat.MIMETYPE_VIDEO_AVC
        } ||
        !isCameraDeviceSetupSupported(cameraId)
    ) return null
    return logical.physicalCameraIds.sorted().firstOrNull { physicalId ->
        runCatching {
            val info = getCameraCharacteristics(physicalId)
            val preview = info.choosePreviewSize()
            info.recordingModes(preview).any {
                it.quality == VideoQuality.UHD && it.fps == 30 &&
                    supportsRecording(cameraId, physicalId, preview, it) == true
            }
        }.getOrDefault(false)
    }
}

internal fun CameraManager.verifiedRecordingModes(
    cameraId: String,
    logical: CameraCharacteristics,
    preferredPhysical: String?,
    previewSize: Size,
    baseModes: List<VideoMode>,
    physicalLenses: Map<String, CameraCharacteristics> = physicalRecordingLenses(logical),
    supportsOnOpenedCamera: ((String?, VideoMode) -> Boolean)? = null,
): List<VideoMode> {
    val queryWithSetup = Build.VERSION.SDK_INT >= 35 &&
        runCatching { isCameraDeviceSetupSupported(cameraId) }.getOrDefault(false)
    fun supports(physicalId: String?, mode: VideoMode): Boolean? {
        val configured = if (Build.VERSION.SDK_INT >= 35 && queryWithSetup) {
            supportsRecording(cameraId, physicalId, previewSize, mode)
        } else null
        return configured ?: physicalId?.let { supportsOnOpenedCamera?.invoke(it, mode) }
    }
    val modes = baseModes.filter { mode ->
        val supported = supports(preferredPhysical, mode)
        if (preferredPhysical == null) supported != false else supported == true
    }.toMutableList()
    for ((physicalId, info) in physicalLenses) {
        if (physicalId == preferredPhysical) continue
        val lensModes = runCatching { info.recordingModes(previewSize) }.getOrDefault(emptyList())
        for (candidate in lensModes) {
            val index = modes.indexOfFirst { it.quality == candidate.quality && it.fps == candidate.fps }
            if (index >= 0 && modes[index].fpsRange.lower >= candidate.fpsRange.lower) continue
            if (supports(physicalId, candidate) != true) continue
            val routed = candidate.copy(physicalCameraId = physicalId)
            if (index < 0) modes.add(routed) else modes[index] = routed
        }
    }
    return modes.sortedWith(compareBy<VideoMode> { it.quality }.thenBy { it.fps })
}

internal data class CameraTemperature(
    val supported: Boolean = false,
    val adjustable: Boolean = false,
    val warmth: Float? = null,
)

internal class CameraPreview(
    context: Context,
    private val front: Boolean,
    private val onModes: (List<VideoMode>) -> Unit,
    private val onReady: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val onAdjustmentError: (String) -> Unit,
    private val onLensChanged: (Boolean, Boolean) -> Unit,
    private val onZoomChanged: (Float, Range<Float>) -> Unit,
    private val onTemperatureChanged: (CameraTemperature) -> Unit,
) : FrameLayout(context) {
    private val manager = context.getSystemService(CameraManager::class.java)
    private val displays = context.getSystemService(DisplayManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            // Turning between landscape sides can change rotation without changing view size.
            if (display?.displayId == displayId) invalidateViewfinderTransform()
        }
    }
    private val viewfinder = ViewfinderView(context).apply { setScaleType(ScaleType.FIT_CENTER) }
    private var viewfinderTransformDirty = true
    private var transformedDisplayDegrees = -1
    private var transformedCharacteristics: CameraCharacteristics? = null
    private val prepareViewfinderTransform = ViewTreeObserver.OnPreDrawListener {
        // Viewfinder redraws during layout using its last source rotation. Correct it after the
        // new bounds are laid out and before that same frame, rather than on the following vsync.
        val info = sessionCharacteristics ?: characteristics
        val displayDegrees = displayRotationDegrees()
        if (info != null && (viewfinderTransformDirty || displayDegrees != transformedDisplayDegrees ||
                info !== transformedCharacteristics)) {
            updateViewfinderTransform(info, displayDegrees)
        }
        true
    }
    private var surfaceSession: ViewfinderSurfaceSession? = null
    private var closingSurface: ViewfinderSurfaceSession? = null
    private var pendingSurface: Future<ViewfinderSurfaceSession>? = null
    private var camera: CameraDevice? = null
    private var cameraId: String? = null
    private var closingCamera: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var endingSession: CameraCaptureSession? = null
    private var recorder: TakeRecorder? = null
    private var modeDiscovery: RecordingModeDiscovery? = null
    private var skipDiscovery = false
    private var cameraGeneration = 0
    private var sessionGeneration = 0
    private var active = false
    private var opening = false
    private enum class PreviewRecovery { NONE, REOPENING, RETRYING }
    private var previewRecovery = PreviewRecovery.NONE
    private val previewRecoveryTimeout = Runnable {
        if (active && !ready && recorder == null && !recoverPreview()) {
            suspendCamera()
            onError("Could not restore the camera preview.")
        }
    }
    private val captureDrainTimeout = Runnable { camera?.let(::closeCameraDevice) }
    private val cameraCloseTimeout = Runnable { closingCamera?.let(::cameraClosed) }
    private var characteristics: CameraCharacteristics? = null
    private var sessionCharacteristics: CameraCharacteristics? = null
    private var logicalCharacteristics: CameraCharacteristics? = null
    private var physicalCameraId: String? = null
    private var allowPhysicalCamera = true
    private var actualFront = front
    private var modes = emptyList<VideoMode>()
    private val rejectedModes = mutableSetOf<VideoMode>()
    private var ready = false
    private var zoom = 1f
    private var baseZoomRange = Range(1f, 1f)
    private var zoomRange = Range(1f, 1f)
    private var repeatingRequest: CaptureRequest.Builder? = null
    private var previewSize: Size? = null
    private var focusPoint: Offset? = null
    private var defaultFocusMode: Int? = null
    private var defaultFocusRegions: Array<MeteringRectangle>? = null
    private val resetFocus = Runnable { clearMeteringFocus() }
    private var temperatureSupported = false
    private var warmth: Float? = null
    private var autoColor: AutoColor? = null
    private var samplingColor = false
    private var defaultColorMode: Int? = null
    private val resampleColor = Runnable { prepareTemperature() }

    private data class AutoColor(val gains: RggbChannelVector, val transform: ColorSpaceTransform)

    init {
        addView(viewfinder, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun resume() {
        active = true
        openCamera()
    }

    fun suspendCamera() {
        active = false
        modeDiscovery?.cancel()
        modeDiscovery = null
        clearPreviewRecovery()
        val device = camera
        if (device != null) {
            closeCameraDevice(device)
        } else {
            cameraGeneration++
            opening = false
            closeSession()
            if (closingCamera == null) recorder?.stop()
        }
        focusPoint = null
        removeCallbacks(resetFocus)
        releaseViewfinder()
    }

    private fun releaseViewfinder() {
        pendingSurface?.cancel(false)
        pendingSurface = null
        surfaceSession?.let {
            if (closingCamera != null) closingSurface = it else it.close()
        }
        surfaceSession = null
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        displays.registerDisplayListener(displayListener, mainHandler)
        viewfinderTransformDirty = true
        viewTreeObserver.addOnPreDrawListener(prepareViewfinderTransform)
        // The child gets its Display after this parent callback returns.
        post { openCamera() }
    }

    override fun onDetachedFromWindow() {
        displays.unregisterDisplayListener(displayListener)
        viewTreeObserver.removeOnPreDrawListener(prepareViewfinderTransform)
        val wasActive = active
        suspendCamera()
        active = wasActive
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        invalidateViewfinderTransform()
        if (camera == null) post { openCamera() }
    }

    private fun invalidateViewfinderTransform() {
        // Also request a draw for 180-degree turns, which do not change the view's bounds.
        viewfinderTransformDirty = true
        invalidate()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (!active || opening || camera != null || closingCamera != null || recorder != null || !viewfinder.isAttachedToWindow ||
            viewfinder.display == null || width == 0 || height == 0
        ) return
        opening = true
        val generation = ++cameraGeneration
        runCatching {
            val previousId = cameraId
            if (previousId != null && surfaceSession?.surface?.isValid == true) {
                openDevice(previousId, generation)
                return@runCatching
            }
            releaseViewfinder()
            val cameras = manager.cameraIdList.groupBy {
                manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING)
            }
            val frontId = cameras[CameraCharacteristics.LENS_FACING_FRONT]?.firstOrNull()
            val backId = cameras[CameraCharacteristics.LENS_FACING_BACK]?.firstOrNull()
            val id = (if (front) frontId ?: backId else backId ?: frontId)
                ?: cameras[CameraCharacteristics.LENS_FACING_EXTERNAL]?.firstOrNull()
                ?: error("This device has no available camera.")
            cameraId = id
            actualFront = id == frontId
            onLensChanged(actualFront, frontId != null && backId != null)
            val logical = manager.getCameraCharacteristics(id)
            logicalCharacteristics = logical
            physicalCameraId = if (allowPhysicalCamera) {
                manager.choosePhysicalFrontCamera(id, logical)
            } else null
            val info = physicalCameraId?.let { manager.getCameraCharacteristics(it) } ?: logical
            characteristics = info
            temperatureSupported = listOf(logical, info).all { cameraInfo ->
                cameraInfo.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
                    ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING) == true
            }
            if (!temperatureSupported) warmth = null
            if (warmth == null) autoColor = null
            reportTemperature()
            val reportedZoom = if (Build.VERSION.SDK_INT >= 30) {
                logical.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            } else null
            val digitalZoom = logical.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) ?: 1f
            baseZoomRange = when {
                reportedZoom == null -> Range(1f, digitalZoom.coerceAtLeast(1f))
                physicalCameraId != null && reportedZoom.contains(1f) -> Range(1f, reportedZoom.upper)
                else -> reportedZoom
            }
            zoomRange = baseZoomRange
            zoom = zoomRange.clamp(zoom)
            onZoomChanged(zoom, zoomRange)
            val physicalLenses = manager.physicalRecordingLenses(logical)
            val preferredPhysical = physicalCameraId
            val size = if (preferredPhysical == null) info.chooseRecordingPreview(physicalLenses.values)
                else info.choosePreviewSize()
            previewSize = size
            val advertised = info.recordingModes(size)
            updateViewfinderTransform(info)
            val request = ViewfinderSurfaceRequest(
                size.width, size.height, ImplementationMode.EMBEDDED, generation.toString()
            )
            val result = viewfinder.requestSurfaceSessionAsync(request)
            pendingSurface = result
            result.addListener({
                runCatching {
                    val acquired = result.get()
                    if (generation != cameraGeneration || !active) {
                        acquired.close()
                    } else {
                        pendingSurface = null
                        surfaceSession = acquired
                        openDevice(id, generation, detectModes = { device, preview ->
                            manager.verifiedRecordingModes(id, logical, preferredPhysical, size, advertised, physicalLenses) { lens, mode ->
                                device.supportsRecording(preview, lens, mode)
                            }
                        }, discoveryCandidates = manager.recordingDiscoveryCandidates(id, logical, preferredPhysical, size, physicalLenses))
                    }
                }.onFailure {
                    if (generation == cameraGeneration && !recoverPreview()) {
                        suspendCamera()
                        onError(it.message ?: "Could not prepare the camera preview.")
                    }
                }
            }, context.mainExecutor)
        }.onFailure {
            opening = false
            if (!recoverPreview()) {
                clearPreviewRecovery()
                onError(it.message ?: "Could not open the camera.")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun openDevice(
        id: String,
        generation: Int,
        detectModes: ((CameraDevice, Surface) -> List<VideoMode>)? = null,
        discoveryCandidates: List<VideoMode> = emptyList(),
    ) {
        manager.openCamera(id, context.mainExecutor, object : CameraDevice.StateCallback() {
                override fun onOpened(device: CameraDevice) {
                    if (generation != cameraGeneration || !active) {
                        device.close()
                        return
                    }
                    val preview = surfaceSession?.surface
                    if (preview == null || !preview.isValid) {
                        lost(device, "The camera preview surface is unavailable.", retryable = true)
                        return
                    }
                    opening = false
                    camera = device
                    if (detectModes != null) {
                        modes = detectModes(device, preview)
                        onModes(modes.filterNot(rejectedModes::contains))
                        if (!skipDiscovery && (discoveryCandidates.isNotEmpty() || modes.any { it.fps == 60 && it.fpsRange.lower == 60 })) {
                            val discovery = RecordingModeDiscovery(context.applicationContext, device, preview,
                                checkNotNull(previewSize), physicalCameraId)
                            modeDiscovery = discovery
                            discovery.start(modes, discoveryCandidates) { discovered ->
                                if (active && generation == cameraGeneration && camera === device && modeDiscovery === discovery) {
                                    modeDiscovery = null
                                    modes = discovered
                                    onModes(modes.filterNot(rejectedModes::contains))
                                    if (modes.isEmpty()) onError("No compatible recording preset for this camera.")
                                    configure()
                                }
                            }
                            return
                        }
                        if (modes.isEmpty()) onError("No compatible recording preset for this camera.")
                    }
                    configure()
                }

                override fun onClosed(device: CameraDevice) = cameraClosed(device)
                override fun onDisconnected(device: CameraDevice) = lost(device, "Camera disconnected.")
                override fun onError(device: CameraDevice, error: Int) =
                    lost(device, "Camera error ($error).",
                        error == ERROR_CAMERA_DEVICE || error == ERROR_CAMERA_IN_USE || error == ERROR_MAX_CAMERAS_IN_USE)

                private fun lost(device: CameraDevice, message: String, retryable: Boolean = false) {
                    if (closingCamera === device) return
                    if (generation != cameraGeneration) {
                        device.close()
                        return
                    }
                    if (modeDiscovery != null) {
                        modeDiscovery?.availableModes()?.let { modes = it; onModes(modes.filterNot(rejectedModes::contains)) }
                        modeDiscovery?.cancel()
                        modeDiscovery = null
                        skipDiscovery = true
                        closeCameraDevice(device)
                        return
                    }
                    if (retryable && recoverPreview(device)) return
                    closeCameraDevice(device)
                    suspendCamera()
                    onError(message)
                }
        })
    }

    private fun clearPreviewRecovery() {
        previewRecovery = PreviewRecovery.NONE
        mainHandler.removeCallbacks(previewRecoveryTimeout)
    }

    private fun schedulePreviewRecoveryCheck() {
        mainHandler.removeCallbacks(previewRecoveryTimeout)
        if (active && !ready && recorder == null && previewRecovery != PreviewRecovery.NONE) {
            val timeout = if (previewRecovery == PreviewRecovery.REOPENING) 3_500L else 6_000L
            mainHandler.postDelayed(previewRecoveryTimeout, timeout)
        }
    }

    private fun recoverPreview(device: CameraDevice? = camera): Boolean {
        if (!active || previewRecovery != PreviewRecovery.REOPENING) return false
        previewRecovery = PreviewRecovery.RETRYING
        if (device != null) {
            closeCameraDevice(device)
        } else {
            cameraGeneration++
            opening = false
            closeSession()
        }
        releaseViewfinder()
        schedulePreviewRecoveryCheck()
        // If a camera is closing, cameraClosed will reopen it after the encoder has drained.
        openCamera()
        return true
    }

    private fun closeCameraDevice(device: CameraDevice) {
        mainHandler.removeCallbacks(captureDrainTimeout)
        endingSession = null
        closingCamera = device
        camera = null
        cameraGeneration++
        opening = false
        recorder?.stop(waitForCamera = true)
        closeSession()
        device.close()
        // A broken driver can omit onClosed. Let the recorder save and try reopening once.
        mainHandler.postDelayed(cameraCloseTimeout, 2_500)
    }

    private fun cameraClosed(device: CameraDevice) {
        if (closingCamera !== device) return
        mainHandler.removeCallbacks(cameraCloseTimeout)
        closingCamera = null
        closingSurface?.close()
        closingSurface = null
        val take = recorder
        if (take != null) take.stop() else openCamera()
    }

    fun record(
        mode: VideoMode,
        speech: SpeechFollower?,
        recordingFolder: RecordingFolder?,
        microphoneInput: MicrophoneInput,
        onStarted: (VideoMode) -> Unit,
        onMicrophoneRoute: (String) -> Unit,
        onMicrophoneInterrupted: () -> Unit,
        onFinished: (Result<SavedTake>) -> Unit,
    ) {
        if (!ready || recorder != null) {
            onFinished(Result.failure(IllegalStateException("The camera is still getting ready.")))
            return
        }
        setReady(false)
        clearPreviewRecovery()
        fun startTake(selected: VideoMode) {
            lateinit var take: TakeRecorder
            take = TakeRecorder(
                context.applicationContext, selected, recordingRotation(selected), speech, recordingFolder, microphoneInput,
                onMicrophoneRoute = onMicrophoneRoute,
                onMicrophoneInterrupted = {
                    if (recorder === take) {
                        stopRecording()
                        onMicrophoneInterrupted()
                    }
                },
                onSurface = { surface ->
                    if (recorder === take && active) {
                        configure(surface, selected) {
                            take.startCapture()
                            onStarted(selected)
                        }
                    } else {
                        take.stop()
                    }
                },
                onFinished = finished@{ result ->
                    if (recorder !== take) {
                        onFinished(result)
                        return@finished
                    }
                    recorder = null
                    if (active && camera != null && result.exceptionOrNull() is RecordingModeRejected) {
                        rejectedModes += selected
                        val available = modes.filterNot(rejectedModes::contains)
                        onModes(available)
                        val selectedArea = selected.width.toLong() * selected.height
                        val eligible = available.filter { it.width.toLong() * it.height <= selectedArea }
                        val next = eligible.firstOrNull { it.fps == selected.fps }
                            ?: eligible.filter { it.fps <= selected.fps }.maxWithOrNull(
                                compareBy<VideoMode> { it.width.toLong() * it.height }.thenBy { it.fps },
                            )
                        if (next != null) {
                            startTake(next)
                            return@finished
                        }
                    }
                    onFinished(result)
                    schedulePreviewRecoveryCheck()
                    if (camera != null && active) configure() else openCamera()
                },
            )
            recorder = take
            take.prepare()
        }
        startTake(mode)
    }

    fun stopRecording() {
        val take = recorder ?: return
        if (endingSession != null || closingCamera != null) return
        previewRecovery = PreviewRecovery.REOPENING
        val device = camera
        if (device == null) {
            take.stop()
            return
        }
        take.stop(waitForCamera = true)
        val current = session
        repeatingRequest = null
        if (current == null || runCatching { current.stopRepeating() }.isFailure) {
            closeCameraDevice(device)
        } else {
            endingSession = current
            mainHandler.postDelayed(captureDrainTimeout, 1_000)
        }
    }

    fun setZoom(value: Float) {
        val next = zoomRange.clamp(value)
        if (next == zoom) return
        val request = repeatingRequest
        val activeSession = session
        if (request != null && activeSession != null) {
            val updated = runCatching {
                applyZoom(request, next)
                activeSession.setRepeatingRequest(request.build(), null, null)
            }
            if (updated.isFailure) {
                applyZoom(request, zoom)
                onAdjustmentError("Could not change camera zoom.")
                return
            }
        }
        zoom = next
        onZoomChanged(zoom, zoomRange)
        clearMeteringFocus()
    }

    fun focusAt(tap: Offset): Boolean {
        val info = sessionCharacteristics ?: characteristics ?: return false
        val size = previewSize ?: return false
        val transform = viewfinder.transformationInfo
        val point = previewMeteringPoint(tap, width, height, size.width, size.height, transform.sourceRotation,
            transform.isSourceMirroredHorizontally, transform.isSourceMirroredVertically) ?: return false
        if ((info.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) == 0 ||
            info.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                ?.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) != true
        ) return false
        focusPoint = point
        if (!submitFocus(point)) {
            clearMeteringFocus()
            return false
        }
        removeCallbacks(resetFocus)
        postDelayed(resetFocus, 4_000)
        return true
    }

    private fun submitFocus(point: Offset): Boolean {
        val builder = repeatingRequest ?: return false
        val activeSession = session ?: return false
        val info = logicalCharacteristics ?: return false
        val size = previewSize ?: return false
        val sensor = builder.get(CaptureRequest.SCALER_CROP_REGION)
            ?: info.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return false
        if ((info.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF) ?: 0) == 0) return false
        // The preview stream is a centered aspect-ratio crop of the requested sensor region.
        val aspect = size.width.toFloat() / size.height
        val cropWidth = minOf(sensor.width().toFloat(), sensor.height() * aspect)
        val cropHeight = minOf(sensor.height().toFloat(), sensor.width() / aspect)
        val x = (sensor.exactCenterX() + (point.x - 0.5f) * cropWidth).roundToInt()
        val y = (sensor.exactCenterY() + (point.y - 0.5f) * cropHeight).roundToInt()
        val half = (minOf(cropWidth, cropHeight) * 0.06f).roundToInt().coerceAtLeast(1)
        val region = arrayOf(MeteringRectangle(
            Rect(maxOf(sensor.left, x - half), maxOf(sensor.top, y - half),
                minOf(sensor.right, x + half), minOf(sensor.bottom, y + half)),
            MeteringRectangle.METERING_WEIGHT_MAX,
        ))
        return runCatching {
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            activeSession.capture(builder.build(), null, null)
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            builder.set(CaptureRequest.CONTROL_AF_REGIONS, region)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
            activeSession.capture(builder.build(), null, null)
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
            activeSession.setRepeatingRequest(builder.build(), null, null)
        }.isSuccess
    }

    private fun clearMeteringFocus() {
        removeCallbacks(resetFocus)
        if (focusPoint == null) return
        focusPoint = null
        val builder = repeatingRequest ?: return
        val activeSession = session ?: return
        builder.set(CaptureRequest.CONTROL_AF_MODE, defaultFocusMode)
        // An omitted region can leave the previous request's target active on the camera.
        val previous = builder.get(CaptureRequest.CONTROL_AF_REGIONS)?.firstOrNull()
        builder.set(CaptureRequest.CONTROL_AF_REGIONS, defaultFocusRegions
            ?: previous?.let { arrayOf(MeteringRectangle(it.rect, MeteringRectangle.METERING_WEIGHT_DONT_CARE)) })
        runCatching {
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
            activeSession.capture(builder.build(), null, null)
        }
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        runCatching { activeSession.setRepeatingRequest(builder.build(), null, null) }
    }

    fun prepareTemperature() {
        if (!temperatureSupported || warmth != null || samplingColor) return
        val request = repeatingRequest?.build() ?: return
        val current = session ?: return
        samplingColor = true
        runCatching {
            current.capture(request, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    if (session !== this@CameraPreview.session) return
                    samplingColor = false
                    if (warmth != null) return
                    val gains = result.get(CaptureResult.COLOR_CORRECTION_GAINS)
                    val transform = result.get(CaptureResult.COLOR_CORRECTION_TRANSFORM)
                    if (gains != null && transform != null) {
                        autoColor = AutoColor(gains, transform)
                        reportTemperature()
                    }
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    if (session === this@CameraPreview.session) samplingColor = false
                }
            }, mainHandler)
        }.onFailure { samplingColor = false }
    }

    fun setTemperature(value: Float?) {
        val builder = repeatingRequest ?: return
        val current = session ?: return
        if (!temperatureSupported || value == warmth || (value != null && autoColor == null)) return
        applyTemperature(builder, value)
        if (runCatching { current.setRepeatingRequest(builder.build(), null, null) }.isFailure) {
            applyTemperature(builder, warmth)
            onAdjustmentError("Could not change white balance.")
            return
        }
        warmth = value
        if (value == null) {
            autoColor = null
            removeCallbacks(resampleColor)
            postDelayed(resampleColor, 350)
        }
        reportTemperature()
    }

    private fun applyTemperature(builder: CaptureRequest.Builder, value: Float?) {
        val base = autoColor
        if (value == null || base == null) {
            builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            builder.set(CaptureRequest.COLOR_CORRECTION_MODE, defaultColorMode)
            builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, null)
            builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, null)
            return
        }
        val gains = base.gains
        builder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
        builder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
        builder.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, base.transform)
        builder.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(
            gains.red * (1f + 0.35f * value.coerceAtLeast(0f)), gains.greenEven, gains.greenOdd,
            gains.blue * (1f + 0.35f * (-value).coerceAtLeast(0f)),
        ))
    }

    private fun reportTemperature() {
        onTemperatureChanged(CameraTemperature(temperatureSupported, autoColor != null, warmth))
    }

    private fun applyZoom(request: CaptureRequest.Builder, ratio: Float) {
        val info = logicalCharacteristics ?: return
        if (Build.VERSION.SDK_INT >= 30 && info.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) != null) {
            request.set(CaptureRequest.CONTROL_ZOOM_RATIO, ratio)
        } else {
            val activeArray = info.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
            val insetX = ((activeArray.width() - activeArray.width() / ratio) / 2).toInt()
            val insetY = ((activeArray.height() - activeArray.height() / ratio) / 2).toInt()
            request.set(CaptureRequest.SCALER_CROP_REGION, Rect(activeArray).apply { inset(insetX, insetY) })
        }
    }

    private fun configure(
        encoder: Surface? = null,
        mode: VideoMode? = null,
        tryEis: Boolean = true,
        onStarted: () -> Unit = {},
    ) {
        closeSession()
        val device = camera ?: return
        val generation = sessionGeneration
        val preview = surfaceSession?.surface
        if (preview == null || !preview.isValid) {
            failSession("The camera preview surface is unavailable.")
            return
        }
        val routedPhysical = mode?.physicalCameraId ?: physicalCameraId
        val captureInfo = mode?.physicalCameraId?.let(manager::getCameraCharacteristics) ?: characteristics
        val supportsEis = logicalCharacteristics?.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
            ?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true &&
            captureInfo?.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                ?.contains(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON) == true
        val enableEis = mode != null && supportsEis && tryEis && (mode.fps <= 30 || Build.DEVICE == "husky")
        fun handleConfigurationFailure(message: String) {
            if (enableEis) configure(encoder, mode, tryEis = false, onStarted = onStarted)
            else failSession(message)
        }
        val outputs = mutableListOf(OutputConfiguration(preview).apply { setPhysicalCameraId(routedPhysical) })
        if (encoder != null) {
            outputs += OutputConfiguration(encoder).apply {
                setPhysicalCameraId(routedPhysical)
                // Older Android releases already use the monotonic clock for video encoder surfaces.
                if (Build.VERSION.SDK_INT >= 33) {
                    timestampBase = OutputConfiguration.TIMESTAMP_BASE_MONOTONIC
                    mirrorMode = OutputConfiguration.MIRROR_MODE_NONE
                }
            }
        }
        runCatching {
            val template = if (encoder == null) CameraDevice.TEMPLATE_PREVIEW else CameraDevice.TEMPLATE_RECORD
            val builder = device.createCaptureRequest(template).apply {
                addTarget(preview)
                if (Build.VERSION.SDK_INT >= 31 &&
                    captureInfo?.get(CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES)
                        ?.contains(CaptureRequest.SCALER_ROTATE_AND_CROP_NONE) == true
                ) {
                    set(CaptureRequest.SCALER_ROTATE_AND_CROP, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE)
                }
                if (encoder != null && mode != null) {
                    addTarget(encoder)
                    set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, mode.fpsRange)
                    if (supportsEis) {
                        set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, if (enableEis)
                            CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                    }
                    if (enableEis && logicalCharacteristics?.availableCaptureRequestKeys
                            ?.contains(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE) == true) {
                        set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                    }
                }
            }
            val configuration = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, outputs, context.mainExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onReady(readySession: CameraCaptureSession) {
                        if (endingSession === readySession) closeCameraDevice(device)
                    }

                    override fun onConfigured(configured: CameraCaptureSession) {
                        if (!active || generation != sessionGeneration) {
                            configured.close()
                            return
                        }
                        session = configured
                        sessionCharacteristics = captureInfo
                        runCatching {
                            zoomRange = if (mode?.physicalCameraId != null && baseZoomRange.contains(1f)) {
                                Range(1f, baseZoomRange.upper)
                            } else baseZoomRange
                            zoom = zoomRange.clamp(zoom)
                            onZoomChanged(zoom, zoomRange)
                            applyZoom(builder, zoom)
                            defaultFocusMode = builder.get(CaptureRequest.CONTROL_AF_MODE)
                            defaultFocusRegions = builder.get(CaptureRequest.CONTROL_AF_REGIONS)
                            defaultColorMode = builder.get(CaptureRequest.COLOR_CORRECTION_MODE)
                            if (warmth != null) applyTemperature(builder, warmth)
                            configured.setRepeatingRequest(builder.build(), null, null)
                            repeatingRequest = builder
                            focusPoint?.let { if (!submitFocus(it)) clearMeteringFocus() }
                            // The viewfinder can apply its transform once the camera surface is active.
                            captureInfo?.let { updateViewfinderTransform(it) }
                            if (encoder == null) setReady(true) else onStarted()
                        }.onFailure { handleConfigurationFailure(it.message ?: "Could not start camera capture.") }
                    }

                    override fun onConfigureFailed(failed: CameraCaptureSession) {
                        failed.close()
                        if (generation == sessionGeneration) {
                            handleConfigurationFailure(if (mode == null) "Camera rejected this preview." else "Camera rejected $mode.")
                        }
                    }
                }
            )
            val sessionKeys = logicalCharacteristics?.availableSessionKeys.orEmpty()
            if (mode != null && (CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE in sessionKeys ||
                    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE in sessionKeys)) {
                configuration.setSessionParameters(builder.build())
            }
            device.createCaptureSession(configuration)
        }.onFailure { handleConfigurationFailure(it.message ?: "Could not configure the camera.") }
    }

    private fun failSession(message: String) {
        if (recorder == null && recoverPreview()) return
        if (recorder == null && physicalCameraId != null) {
            val recovering = previewRecovery != PreviewRecovery.NONE
            allowPhysicalCamera = false
            suspendCamera()
            if (recovering) previewRecovery = PreviewRecovery.RETRYING
            resume()
            schedulePreviewRecoveryCheck()
            return
        }
        closeSession()
        val take = recorder
        if (take == null) {
            clearPreviewRecovery()
            onError(message)
        } else take.stop(RecordingModeRejected(message))
    }

    private fun closeSession() {
        sessionGeneration++
        removeCallbacks(resampleColor)
        samplingColor = false
        repeatingRequest = null
        sessionCharacteristics = null
        setReady(false)
        session?.let {
            runCatching { it.stopRepeating() }
            runCatching { it.close() }
        }
        session = null
    }

    private fun setReady(value: Boolean) {
        ready = value
        if (value) clearPreviewRecovery()
        onReady(value)
    }

    private fun displayRotationDegrees(): Int =
        when (display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

    private fun recordingRotation(mode: VideoMode): Int {
        val info = mode.physicalCameraId?.let(manager::getCameraCharacteristics) ?: characteristics
        val sensor = info?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val displayDegrees = displayRotationDegrees()
        // The encoder receives unmirrored frames. MP4 stores a clockwise rotation.
        return (sensor + if (actualFront) displayDegrees else 360 - displayDegrees) % 360
    }

    private fun updateViewfinderTransform(
        info: CameraCharacteristics,
        displayDegrees: Int = displayRotationDegrees(),
    ) {
        val source = Camera2TransformationInfo.createFromCharacteristics(info)
        val sensor = info.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        val rotation = (sensor + if (actualFront) 360 - displayDegrees else displayDegrees) % 360
        val transformation = TransformationInfo(
            rotation, source.isSourceMirroredHorizontally, source.isSourceMirroredVertically,
        )
        if (viewfinder.transformationInfo != transformation) {
            viewfinder.setTransformationInfo(transformation)
        }
        transformedCharacteristics = info
        transformedDisplayDegrees = displayDegrees
        viewfinderTransformDirty = false
    }
}
