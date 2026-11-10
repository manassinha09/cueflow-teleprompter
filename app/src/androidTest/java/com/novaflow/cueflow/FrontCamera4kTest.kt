package com.novaflow.cueflow

import android.graphics.SurfaceTexture
import android.graphics.Rect
import android.content.ContentUris
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.util.Size
import android.provider.MediaStore
import android.view.Surface
import android.view.ViewGroup
import android.view.InputDevice
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import androidx.camera.viewfinder.core.ScaleType
import androidx.camera.viewfinder.view.ViewfinderView
import androidx.core.view.descendants
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlin.math.abs
import kotlin.math.roundToInt
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FrontCamera4kTest {

    @Test
    fun recordsAdvertisedMaximumThroughTheApp() {
        val args = InstrumentationRegistry.getArguments()
        val facing = when (args.getString("compatCamera")) {
            "front" -> CameraCharacteristics.LENS_FACING_FRONT
            "back" -> CameraCharacteristics.LENS_FACING_BACK
            else -> return
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(CameraManager::class.java)
        val id = manager.cameraIdList.first {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing
        }
        val logical = manager.getCameraCharacteristics(id)
        val physical = if (Build.DEVICE == "husky") manager.choosePhysicalFrontCamera(id, logical) else null
        val info = physical?.let(manager::getCameraCharacteristics) ?: logical
        val lenses = manager.physicalRecordingLenses(logical)
        val preview = if (physical == null) info.chooseRecordingPreview(lenses.values) else info.choosePreviewSize()
        val available = manager.verifiedRecordingModes(id, logical, physical, preview, info.recordingModes(preview), lenses)
        assertTrue("The test camera must offer at least one recording preset.", available.isNotEmpty())
        val presetSizes = setOf(3840 to 2160, 2560 to 1440, 1920 to 1080, 1280 to 720)
        assertTrue(available.all { (it.width to it.height) in presetSizes && it.fps in setOf(24, 30, 60) })
        assertEquals(available.size, available.map { it.quality to it.fps }.distinct().size)
        val byResolution = compareBy<VideoMode> { it.width.toLong() * it.height }.thenBy { it.fps }
        val byRate = compareBy<VideoMode> { it.fps }.thenBy { it.width.toLong() * it.height }
        val maximum = available.maxWith(if (args.getString("compatMaximum") == "fps") byRate else byResolution)
        val preferences = context.getSharedPreferences("prompter", 0)
        val original = preferences.all
        val keys = listOf("front", "video_width", "video_height", "video_fps", "latest_recording_uri",
            "recording_folder_uri", "recording_folder_name", "sherpa_model", "imported_model")
        val recordedUris = mutableListOf<android.net.Uri>()
        try {
            preferences.edit().putBoolean("front", facing == CameraCharacteristics.LENS_FACING_FRONT)
                .putInt("video_width", maximum.width).putInt("video_height", maximum.height)
                .putInt("video_fps", maximum.fps).putBoolean("sherpa_model", true).putBoolean("imported_model", false)
                .remove("latest_recording_uri").remove("recording_folder_uri").remove("recording_folder_name").commit()
            ActivityScenario.launch(MainActivity::class.java).use {
                waitForControl("Start recording", timeoutMillis = 90_000)
                clickControl("Settings")
                waitForControl(maximum.toString(), partial = true)
                clickControl("Done")
                repeat(2) {
                    clickControl("Start recording")
                    waitForControl("Listening · Zipformer", partial = true)
                    Thread.sleep(3_000)
                    clickControl("Stop recording")
                    val retryAppeared = waitForPreviewAfterStop()
                    waitForControl("Video saved", partial = true)
                    val uri = android.net.Uri.parse(checkNotNull(preferences.getString("latest_recording_uri", null)))
                    assertTrue("Each take should save a new clip.", uri !in recordedUris)
                    recordedUris += uri
                    assertTrue("Retry should stay hidden while the preview recovers.", !retryAppeared)
                    assertTrue("Preview should recover after the take.", findControl("Camera error (4)") == null)
                }
            }
            for (recordedUri in recordedUris) {
                verifyAdvertisedTake(context, recordedUri, maximum, facing)
            }
        } finally {
            recordedUris.forEach { context.contentResolver.delete(it, null, null) }
            preferences.edit().apply {
                for (key in keys) {
                    when (val value = original[key]) {
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is String -> putString(key, value)
                        else -> remove(key)
                    }
                }
            }.commit()
        }
    }

    private fun waitForPreviewAfterStop(): Boolean {
        val deadline = System.nanoTime() + 20_000_000_000L
        var retryAppeared = false
        while (System.nanoTime() < deadline) {
            if (findControl("Retry") != null) retryAppeared = true
            if (findControl("Start recording") != null) return retryAppeared
            Thread.sleep(40)
        }
        error("The preview did not recover after stopping the take.")
    }

    private fun verifyAdvertisedTake(
        context: android.content.Context,
        recordedUri: android.net.Uri,
        maximum: VideoMode,
        facing: Int,
    ) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, recordedUri, null)
            val tracks = (0 until extractor.trackCount).associateWith(extractor::getTrackFormat)
            assertTrue(tracks.values.any { it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true })
            val video = tracks.entries.first { it.value.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
            assertEquals(maximum.width, video.value.getInteger(MediaFormat.KEY_WIDTH))
            assertEquals(maximum.height, video.value.getInteger(MediaFormat.KEY_HEIGHT))
            extractor.selectTrack(video.key)
            val first = extractor.sampleTime
            var last = first
            var frames = 0
            do {
                last = extractor.sampleTime
                frames++
            } while (extractor.advance())
            val fps = (frames - 1) * 1_000_000.0 / (last - first)
            assertTrue("Expected a sustained video track, measured $fps fps.", last - first > 2_000_000 && fps > 0)
            assertTrue("Expected at most ${maximum.fps} fps, measured $fps.", fps <= maximum.fps * 1.1)
            if (maximum.fps == 60 && maximum.fpsRange.lower == 60) {
                assertTrue("Expected approximately 60 fps, measured $fps.", fps >= 55)
            }
            Log.i("CueFlowProbe", "$facing camera: $maximum, measured $fps fps")
        } finally {
            extractor.release()
        }
    }

    @Test
    fun physicalFrontCameraSupports4k30() {
        assumeTrue(Build.VERSION.SDK_INT >= 35 && Build.MODEL == "Pixel 8 Pro")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(CameraManager::class.java)
        val front = manager.cameraIdList.first {
            manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT
        }
        val characteristics = manager.getCameraCharacteristics(front)
        val setup = manager.getCameraDeviceSetup(front)
        var supported = false
        Log.i("CueFlowProbe", "Front $front physical cameras ${characteristics.physicalCameraIds}")
        for (physicalId in characteristics.physicalCameraIds) {
            val info = manager.getCameraCharacteristics(physicalId)
            Log.i("CueFlowProbe", "Physical $physicalId focalLengths=${info.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.contentToString()} " +
                "activeArray=${info.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)} " +
                "sensorSize=${info.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)} " +
                "OIS=${info.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)?.contentToString()} " +
                "EIS=${info.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.contentToString()}")
            val streams = checkNotNull(info.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP))
            val size = Size(3840, 2160)
            val sizes = streams.getOutputSizes(MediaCodec::class.java).orEmpty()
            Log.i("CueFlowProbe", "Physical $physicalId codec sizes ${sizes.joinToString()}")
            if (size !in sizes) continue
            val request = setup.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(30, 30))
            }.build()
            val config = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                listOf(
                    OutputConfiguration(Size(1280, 720), SurfaceTexture::class.java).apply { setPhysicalCameraId(physicalId) },
                    OutputConfiguration(size, MediaCodec::class.java).apply { setPhysicalCameraId(physicalId) },
                ),
                context.mainExecutor,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) = Unit
                    override fun onConfigureFailed(session: CameraCaptureSession) = Unit
                },
            ).apply { setSessionParameters(request) }
            val accepted = setup.isSessionConfigurationSupported(config)
            Log.i("CueFlowProbe", "Physical $physicalId 4K30 plus 720p preview accepted=$accepted duration=${streams.getOutputMinFrameDuration(MediaCodec::class.java, size)}")
            supported = supported || accepted
        }
        assertTrue("No physical front camera accepted 4K30 with preview.", supported)
        val selected = manager.choosePhysicalFrontCamera(front, characteristics)
        Log.i("CueFlowProbe", "CueFlow selected physical front camera $selected through logical $front")
        assertTrue("The app must discover the supported physical camera.", selected != null)
    }

    @Test
    fun recordsFront4k30ThroughTheApp() {
        assumeTrue(Build.VERSION.SDK_INT >= 35 && Build.MODEL == "Pixel 8 Pro")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("prompter", 0)
        val original = preferences.all
        val changedKeys = listOf("front", "video_width", "video_height", "video_fps", "orientation",
            "recording_folder_uri", "recording_folder_name", "latest_recording_uri", "sherpa_model", "imported_model",
            "script_library")
        val args = InstrumentationRegistry.getArguments()
        val benchmark = args.getString("benchmarkAudio") != null
        val useSherpa = !benchmark && args.getString("speechEngine") != "whisper"
        val hasImportedModel = importedWhisperModel(context).isFile
        assumeTrue("Import the Whisper model in CueFlow before testing it.", useSherpa || hasImportedModel)
        val landscapeSide = args.getString("landscapeSide")
        val layoutOnly = landscapeSide != null && args.getString("layoutOnly") == "true"
        val checkZoom = args.getString("checkZoom") == "true"
        val checkTemperature = args.getString("checkTemperature") == "true"
        val missingVideo = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Long.MAX_VALUE)
        preferences.edit().putBoolean("front", true).putInt("video_width", 3840)
            .putInt("video_height", 2160).putInt("video_fps", 30)
            .putInt("orientation", ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT)
            .putString("latest_recording_uri", missingVideo.toString())
            .putBoolean("imported_model", !useSherpa)
            .putBoolean("sherpa_model", false)
            .remove("recording_folder_uri").remove("recording_folder_name").commit()
        val startedAt = System.currentTimeMillis()
        var recordedUri: android.net.Uri? = null
        var expectedRotation: Int? = null
        try {
            // The benchmark supplies its own recognizer, so record without a prompter script.
            if (benchmark) ScriptLibrary(emptyList(), null).save(preferences)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                if (useSherpa && landscapeSide == null) {
                    waitForControl("Loading speech model…")
                    waitForControl("Sherpa-ONNX · Zipformer")
                    assertTrue("Recording controls must stay off the loading screen.",
                        findControl("Start recording", enabledOnly = false) == null)
                }
                waitForControl("Start recording")
                verifySystemBarsHidden(scenario)
                if (layoutOnly) verifyFullPreview(scenario)
                val portraitDock = if (layoutOnly || checkZoom || checkTemperature) {
                    verifyCenteredRecord(scenario, vertical = false)
                } else null
                if (checkZoom) verifyZoomPresets(vertical = false)
                if (checkTemperature) verifyTemperatureToolbar(vertical = false, reversed = false)
                assertTrue("The empty tile should have no playback action.", clickableControl("No recent recording") == null)
                clickControl("Settings")
                waitForControl("4K / 30 fps", partial = true)
                if (layoutOnly) {
                    val qualityRow = checkNotNull(clickableAncestor(waitForControl("Video quality", partial = true)))
                    touchControl(qualityRow, xFraction = 0.15f)
                    clickControl("4K / 30 fps")
                    waitForControl("Recording settings")
                }
                verifySystemBarsHidden(scenario)
                if (useSherpa && landscapeSide == null && hasImportedModel) {
                    clickControl("Sherpa-ONNX · Zipformer", partial = true)
                    clickControl("Whisper (imported)")
                    clickControl("Whisper (imported)", partial = true)
                    clickControl("Sherpa-ONNX · English Zipformer")
                    waitForControl("Sherpa-ONNX · Zipformer", partial = true)
                    waitForControl("Loading speech model…")
                    waitForControl("Recording settings")
                }
                clickControl("Done")
                if (landscapeSide != null) {
                    val reverse = landscapeSide == "reverse"
                    val displayRotation = if (reverse) Surface.ROTATION_270 else Surface.ROTATION_90
                    lateinit var originalActivity: MainActivity
                    scenario.onActivity { activity ->
                        originalActivity = activity
                        assertEquals(WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS,
                            activity.window.attributes.rotationAnimation)
                    }
                    fun rotateTo(requestedOrientation: Int, rotation: Int, orientation: Int) {
                        scenario.onActivity { it.requestedOrientation = requestedOrientation }
                        val deadline = System.nanoTime() + 10_000_000_000L
                        var rotated = false
                        while (!rotated && System.nanoTime() < deadline) {
                            scenario.onActivity { activity ->
                                assertSame("Rotation must keep the activity and loaded model alive.", originalActivity, activity)
                                rotated = activity.display?.rotation == rotation &&
                                    activity.resources.configuration.orientation == orientation
                            }
                            if (!rotated) Thread.sleep(100)
                        }
                        assertTrue("The requested orientation should be active.", rotated)
                    }
                    rotateTo(
                        if (reverse) ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
                        else ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                        displayRotation, Configuration.ORIENTATION_LANDSCAPE,
                    )
                    verifySystemBarsHidden(scenario)
                    val landscapeDock = verifyCenteredRecord(scenario, vertical = true, reverseOrder = !reverse)
                    if (portraitDock != null) {
                        assertTrue("Record should have the same clearance from the dock edge in both orientations.",
                            abs(landscapeDock.edgeClearance - portraitDock.edgeClearance) <= 2)
                        assertTrue("Dock icons should have the same spacing in portrait and landscape.",
                            portraitDock.iconOffsets.zip(landscapeDock.iconOffsets).all { (portrait, landscape) ->
                                abs(portrait - landscape) <= 2
                            })
                    }
                    assertTrue("Changing orientation must not show the model loading screen.",
                        findControl("Loading speech model…") == null)
                    val prompter = Rect().also { waitForControl("Scroll down two lines").getBoundsInScreen(it) }
                    val record = Rect().also { waitForControl("Start recording").getBoundsInScreen(it) }
                    scenario.onActivity { activity ->
                        val cutout = checkNotNull(activity.window.decorView.rootWindowInsets?.displayCutout)
                        assertTrue("The landscape cutout should be on a side.",
                            cutout.safeInsetLeft != cutout.safeInsetRight)
                        val notchOnRight = cutout.safeInsetRight > cutout.safeInsetLeft
                        assertEquals("The prompter should be closer to the notch than the record controls.",
                            notchOnRight, prompter.centerX() > record.centerX())
                    }
                    if (layoutOnly) {
                        verifyFullPreview(scenario)
                        if (checkZoom) verifyZoomPresets(vertical = true, reversed = !reverse)
                        if (checkTemperature) verifyTemperatureToolbar(vertical = true, reversed = !reverse)
                        rotateTo(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Surface.ROTATION_0, Configuration.ORIENTATION_PORTRAIT)
                        verifyCenteredRecord(scenario, vertical = false)
                        verifyFullPreview(scenario)
                        return@use
                    }
                    val manager = context.getSystemService(CameraManager::class.java)
                    val frontId = manager.cameraIdList.first { id ->
                        manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) ==
                            CameraCharacteristics.LENS_FACING_FRONT
                    }
                    val logical = manager.getCameraCharacteristics(frontId)
                    val cameraInfo = manager.choosePhysicalFrontCamera(frontId, logical)
                        ?.let(manager::getCameraCharacteristics) ?: logical
                    val sensor = checkNotNull(cameraInfo.get(CameraCharacteristics.SENSOR_ORIENTATION))
                    // An unmirrored front sensor at 270° is upright at display rotation 90°.
                    val upright = if (reverse) 90 else 270
                    expectedRotation = if (sensor == upright) 0 else 180
                }
                if (checkZoom) waitForControl("Camera zoom 1×")
                if (checkTemperature) {
                    clickControl("White balance")
                    setTemperature(warm = true, reversed = landscapeSide == "normal")
                    touchControl(waitForControl("White balance"))
                }
                clickControl("Start recording")
                waitForControl("Stop recording")
                if (checkTemperature) {
                    clickControl("White balance")
                    waitForControl("Auto")
                    clickControl("Auto")
                    Thread.sleep(600)
                    setTemperature(warm = false, reversed = landscapeSide == "normal")
                }
                if (!benchmark) waitForControl(if (useSherpa) "Listening · Zipformer" else "Listening · Whisper", partial = true)
                if (benchmark) {
                    WhisperPerformanceTest().compareCpuBudgets()
                } else {
                    if (checkZoom) {
                        Thread.sleep(1_500)
                        touchControl(waitForControl("Set camera zoom to 1.4×"))
                        waitForControl("Camera zoom 1.4×")
                    }
                    Thread.sleep(5_000)
                }
                if (checkTemperature) touchControl(waitForControl("Stop recording"))
                else clickControl("Stop recording")
                val retryAppeared = waitForPreviewAfterStop()
                waitForControl("Video saved")
                val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                recordedUri = context.contentResolver.query(
                    collection, arrayOf(MediaStore.Video.Media._ID, MediaStore.Video.Media.DISPLAY_NAME),
                    "${MediaStore.Video.Media.DISPLAY_NAME} LIKE ? AND ${MediaStore.Video.Media.DATE_ADDED} >= ?",
                    arrayOf("CueFlow-%.mp4", (startedAt / 1000).toString()),
                    "${MediaStore.Video.Media._ID} DESC",
                )!!.use { cursor ->
                    check(cursor.moveToFirst()) { "No test recording was saved." }
                    val createdAt = cursor.getString(1).removePrefix("CueFlow-").removeSuffix(".mp4").toLong()
                    check(createdAt >= startedAt) { "Found a recording from before this test." }
                    ContentUris.withAppendedId(collection, cursor.getLong(0))
                }
                assertTrue("Retry should stay hidden while the preview recovers.", !retryAppeared)
                assertTrue("A saved recording should have a playable thumbnail.", clickableControl("Open latest recording") != null)
                scenario.recreate()
                assertTrue("The thumbnail should survive activity recreation.", clickableControl("Open latest recording") != null)
            }
            if (layoutOnly) return
            val uri = checkNotNull(recordedUri)
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, uri, null)
                val formats = (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                assertTrue("Recording should contain microphone audio.", formats.any {
                    it.getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
                })
                val index = formats.indexOfFirst { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
                check(index >= 0) { "Recording has no video." }
                val format = formats[index]
                assertTrue("Expected a 3840x2160 video track.", format.getInteger(MediaFormat.KEY_WIDTH) == 3840 &&
                    format.getInteger(MediaFormat.KEY_HEIGHT) == 2160)
                extractor.selectTrack(index)
                val first = extractor.sampleTime
                var last = first
                var frames = 0
                while (extractor.sampleTime >= 0) {
                    last = extractor.sampleTime
                    frames++
                    if (!extractor.advance()) break
                }
                val fps = (frames - 1) * 1_000_000.0 / (last - first)
                Log.i("CueFlowProbe", "Front app recording 3840x2160, $frames frames, measured $fps fps")
                assertTrue("Expected at least 4 seconds of video.", frames >= 120)
                assertTrue("Expected actual 30 fps, measured $fps.", fps in 29.0..31.0)
            } finally {
                extractor.release()
            }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                expectedRotation?.let { expected ->
                    val actual = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt()
                    assertEquals("Saved landscape rotation for $landscapeSide.", expected, actual)
                    Log.i("CueFlowProbe", "Landscape $landscapeSide saved rotation=$actual")
                }
                val frame = checkNotNull(retriever.getFrameAtTime(1_000_000))
                assertTrue("Expected a decodable 4K frame.", setOf(frame.width, frame.height) == setOf(3840, 2160))
                frame.recycle()
            } finally {
                retriever.release()
            }
        } finally {
            recordedUri?.let { context.contentResolver.delete(it, null, null) }
            preferences.edit().apply {
                for (key in changedKeys) {
                    when (val value = original[key]) {
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is String -> putString(key, value)
                        else -> remove(key)
                    }
                }
            }.commit()
        }
    }

    @Test
    fun scriptsShowGestureBarAndOneBackSwipeReturnsToPrompter() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForControl("Start recording")
            verifySystemBarsHidden(scenario)
            clickControl("Scripts")
            waitForControl("Add script")
            val window = Rect()
            scenario.onActivity { activity ->
                val insets = checkNotNull(activity.window.decorView.rootWindowInsets)
                assertTrue("Scripts should show the navigation bar.", insets.isVisible(WindowInsets.Type.navigationBars()))
                window.set(activity.windowManager.currentWindowMetrics.bounds)
            }
            swipe(window.left + 2, window.centerY(), window.left + window.width() / 4, window.centerY())
            waitForControl("Start recording")
            verifySystemBarsHidden(scenario)
        }
    }

    private fun verifySystemBarsHidden(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            val insets = checkNotNull(activity.window.decorView.rootWindowInsets)
            assertTrue("CueFlow should hide the status bar.", !insets.isVisible(WindowInsets.Type.statusBars()))
            assertTrue("CueFlow should hide the navigation bar.", !insets.isVisible(WindowInsets.Type.navigationBars()))
        }
    }

    private fun verifyFullPreview(scenario: ActivityScenario<MainActivity>) {
        scenario.onActivity { activity ->
            val root = activity.window.decorView as ViewGroup
            val viewfinder = root.descendants.filterIsInstance<ViewfinderView>().single()
            val bounds = Rect()
            assertTrue("The camera viewfinder should be visible.", viewfinder.getGlobalVisibleRect(bounds))
            assertEquals("The viewfinder should display the full stream.", ScaleType.FIT_CENTER, viewfinder.getScaleType())
            assertEquals("The preview should fill the window behind the controls.",
                activity.windowManager.currentWindowMetrics.bounds, bounds)
        }
    }

    private fun verifyZoomPresets(vertical: Boolean, reversed: Boolean = false) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(500, 4_000)
        val one = waitForControl("Camera zoom 1×")
        val close = waitForControl("Set camera zoom to 1.4×")
        val oneBounds = Rect().also(one::getBoundsInScreen)
        val closeBounds = Rect().also(close::getBoundsInScreen)
        val record = controlBounds("Start recording")
        val centerOffset = if (vertical) oneBounds.centerY() - record.centerY() else oneBounds.centerX() - record.centerX()
        assertTrue("The 1× preset should line up with Record.", abs(centerOffset) <= 2)
        val increase = if (vertical) closeBounds.centerY() - oneBounds.centerY()
            else closeBounds.centerX() - oneBounds.centerX()
        assertEquals("The zoom presets should rotate with the controls.", !reversed, increase > 0)
        touchControl(one)
        val scale = waitForControl("Adjust camera zoom")
        val bounds = Rect().also(scale::getBoundsInScreen)
        assertEquals("The zoom scale should follow the preset axis.", vertical, bounds.height() > bounds.width())
        clickControl("Close zoom slider, camera zoom 1×")
        waitForControl("Set camera zoom to 1.4×")

        val start = controlBounds("Camera zoom 1×")
        val distance = start.width() * 3
        val endX = if (vertical) start.centerX() else start.centerX() - distance
        val endY = if (vertical) start.centerY() + if (reversed) -distance else distance else start.centerY()
        swipe(start.centerX(), start.centerY(), endX, endY)
        clickControl("Set camera zoom to 1×")
        waitForControl("Camera zoom 1×")
    }

    private fun verifyTemperatureToolbar(vertical: Boolean, reversed: Boolean) {
        val record = controlBounds("Start recording")
        val toggle = controlBounds("White balance")
        touchControl(waitForControl("White balance"))
        val slider = controlBounds("White balance temperature")
        assertEquals("The temperature track should follow the toolbar axis.", vertical, slider.height() > slider.width())
        assertEquals("Opening temperature should leave Record in place.", record, controlBounds("Start recording"))
        assertEquals("The thermometer should stay in the same toolbar slot.", toggle, controlBounds("White balance"))
        setTemperature(warm = true, reversed = reversed)
        touchControl(waitForControl("Auto"))
        touchControl(waitForControl("White balance"))
        waitForControl("Camera zoom 1×")
        assertEquals(record, controlBounds("Start recording"))
    }

    private fun controlBounds(label: String): Rect = Rect().also(waitForControl(label)::getBoundsInScreen)

    private fun swipe(startX: Int, startY: Int, endX: Int, endY: Int) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val command = "input swipe $startX $startY $endX $endY 300"
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).use { it.readBytes() }
    }

    private fun setTemperature(warm: Boolean, reversed: Boolean) {
        val slider = waitForControl("White balance temperature")
        val bounds = Rect().also(slider::getBoundsInScreen)
        val fraction = if (warm != reversed) 0.84f else 0.16f
        if (bounds.height() > bounds.width()) touchControl(slider, yFraction = fraction)
        else touchControl(slider, xFraction = fraction)
        waitForControl("Auto")
    }

    private fun touchControl(
        control: AccessibilityNodeInfo, holdMillis: Long = 80, xFraction: Float = 0.5f, yFraction: Float = 0.5f,
    ) {
        val bounds = Rect().also(control::getBoundsInScreen)
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val down = SystemClock.uptimeMillis()
        fun send(action: Int) {
            val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action,
                bounds.left + bounds.width() * xFraction, bounds.top + bounds.height() * yFraction, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            try {
                assertTrue(automation.injectInputEvent(event, true))
            } finally {
                event.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN)
        Thread.sleep(holdMillis)
        send(MotionEvent.ACTION_UP)
    }

    private data class DockLayout(val edgeClearance: Int, val iconOffsets: List<Int>)

    private fun verifyCenteredRecord(
        scenario: ActivityScenario<MainActivity>,
        vertical: Boolean,
        reverseOrder: Boolean = false,
    ): DockLayout {
        val window = Rect()
        var bottomClearance = 0
        scenario.onActivity {
            window.set(it.windowManager.currentWindowMetrics.bounds)
            bottomClearance = (56 * it.resources.displayMetrics.density).roundToInt()
        }
        val displayCenter = if (vertical) window.centerY() else window.centerX()
        val deadline = System.nanoTime() + 5_000_000_000L
        val portraitOrder = listOf(
            "No recent recording", "Settings", "Start recording", "Scripts", "Switch to rear camera",
        )
        val labels = if (reverseOrder) portraitOrder.reversed() else portraitOrder
        while (System.nanoTime() < deadline) {
            val bounds = labels.mapNotNull { label ->
                findControl(label, enabledOnly = false)?.let { node ->
                    Rect().also(node::getBoundsInScreen)
                }
            }
            val measured = bounds.map { if (vertical) it.centerY() else it.centerX() }
            if (measured.size == labels.size && measured.zipWithNext().all { (a, b) -> a < b } &&
                abs(2 * measured[2] - measured.first() - measured.last()) <= 4 &&
                abs(measured[2] - displayCenter) <= 2) {
                val record = bounds[2]
                assertTrue("Record should leave at least 56 dp for bottom gestures.",
                    window.bottom - record.bottom >= bottomClearance - 2)
                val edgeClearance = when {
                    !vertical -> window.bottom - record.bottom
                    reverseOrder -> window.right - record.right
                    else -> record.left - window.left
                }
                assertTrue("Record should leave at least 56 dp from the dock's outer edge.", edgeClearance >= bottomClearance - 2)
                return DockLayout(edgeClearance, measured.map { it - measured[2] })
            }
            Thread.sleep(100)
        }
        error("The toolbar should keep its order with Record centered when the phone rotates.")
    }

    private fun waitForControl(label: String, partial: Boolean = false, timeoutMillis: Long = 20_000): AccessibilityNodeInfo {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000
        while (System.nanoTime() < deadline) {
            findControl(label, partial)?.let { return it }
            Thread.sleep(100)
        }
        error("Control '$label' was not available.")
    }

    private fun findControl(label: String, partial: Boolean = false, enabledOnly: Boolean = true): AccessibilityNodeInfo? {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val automation = instrumentation.uiAutomation
        val packageName = instrumentation.targetContext.packageName
        // Zoom swaps the preset row and scale, invalidating the previous virtual nodes.
        if (Build.VERSION.SDK_INT >= 34) automation.clearCache()
        val nodes = java.util.ArrayDeque<AccessibilityNodeInfo>()
        automation.rootInActiveWindow?.let(nodes::add)
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            if (node.packageName?.toString() == packageName && (!enabledOnly || node.isEnabled)) {
                val text = node.text?.toString()
                if (text == label || node.contentDescription?.toString() == label ||
                    (partial && text?.contains(label) == true)) return node
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(nodes::add)
        }
        return null
    }

    private fun clickableControl(label: String): AccessibilityNodeInfo? {
        return clickableAncestor(waitForControl(label))
    }

    private fun clickableAncestor(control: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        var node = control
        while (node != null && !node.isClickable) node = node.parent
        return node?.takeIf { it.isEnabled }
    }

    private fun clickControl(label: String, partial: Boolean = false) {
        val deadline = System.nanoTime() + 20_000_000_000L
        while (System.nanoTime() < deadline) {
            val node = clickableAncestor(findControl(label, partial))
            if (node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true) return
            Thread.sleep(100)
        }
        error("Could not click '$label'.")
    }
}
