package com.novaflow.cueflow

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Range
import android.view.Surface
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.absolutePadding
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.core.tween
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.novaflow.cueflow.ui.theme.CueFlowTheme
import com.novaflow.cueflow.ui.theme.StudioColors
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private val ToolbarActionSize = 56.dp
private val RecordButtonSize = 68.dp
private val DockPadding = 8.dp
private val DockControlSpacing = 4.dp
private val DockGestureClearance = 56.dp
private const val CameraOverlayOpacity = 0.72f

class MainActivity : ComponentActivity() {
    private var permitted by mutableStateOf(false)
    private var resumed by mutableStateOf(false)
    private var showScriptNavigationBar = false
    private val permissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        refreshPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.attributes = window.attributes.apply {
            rotationAnimation = WindowManager.LayoutParams.ROTATION_ANIMATION_SEAMLESS
        }
        requestedOrientation = getSharedPreferences("prompter", MODE_PRIVATE)
            .getInt("orientation", ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }
        refreshPermissions()
        setContent {
            CueFlowTheme {
                if (permitted) {
                    BoxWithConstraints(Modifier.fillMaxSize()) {
                        Studio(Modifier.fillMaxSize(), IntSize(constraints.maxWidth, constraints.maxHeight))
                    }
                } else {
                    val lightNavigation = !isSystemInDarkTheme()
                    LaunchedEffect(lightNavigation) {
                        updateSystemBars(showNavigationBar = false)
                        WindowCompat.getInsetsController(window, window.decorView)
                            .isAppearanceLightNavigationBars = lightNavigation
                    }
                    Scaffold { insets ->
                        Box(Modifier.fillMaxSize().padding(insets).padding(24.dp), contentAlignment = Alignment.Center) {
                            Column(
                                Modifier.widthIn(max = 360.dp).verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                CueFlowMark()
                                Text("CueFlow", style = MaterialTheme.typography.headlineLarge)
                                Text(
                                    "Allow camera and microphone access to record with CueFlow.",
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center,
                                )
                                Button(onClick = { permissionRequest.launch(permissions) }) {
                                    Text("Enable camera and mic")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        refreshPermissions()
    }

    override fun onPause() {
        resumed = false
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) updateSystemBars()
    }

    private fun updateSystemBars(showNavigationBar: Boolean = showScriptNavigationBar) {
        showScriptNavigationBar = showNavigationBar
        WindowCompat.getInsetsController(window, window.decorView).apply {
            if (showNavigationBar) {
                hide(WindowInsetsCompat.Type.statusBars())
                show(WindowInsetsCompat.Type.navigationBars())
            } else {
                hide(WindowInsetsCompat.Type.systemBars())
            }
        }
    }

    private fun refreshPermissions() {
        permitted = permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    @Composable
    private fun Studio(modifier: Modifier, viewport: IntSize) {
        val preferences = remember { getSharedPreferences("prompter", MODE_PRIVATE) }
        val landscape = viewport.width > viewport.height
        var library by remember { mutableStateOf(ScriptLibrary.load(preferences, DEFAULT_SCRIPT)) }
        var showScripts by rememberSaveable { mutableStateOf(false) }
        var fontSize by remember { mutableIntStateOf(preferences.getInt("font", 26)) }
        var opacityOverride by remember {
            mutableStateOf(preferences.getInt("prompter_opacity", -1).takeIf { it in 0..100 })
        }
        val opacity = opacityOverride ?: if (landscape) 48 else 68
        var front by remember { mutableStateOf(preferences.getBoolean("front", false)) }
        var actualFront by remember { mutableStateOf(front) }
        var canSwitchLens by remember { mutableStateOf(false) }
        var orientation by remember { mutableIntStateOf(requestedOrientation) }
        var settings by remember { mutableStateOf(false) }
        var retry by remember { mutableIntStateOf(0) }
        var ready by remember { mutableStateOf(false) }
        var modes by remember { mutableStateOf(emptyList<VideoMode>()) }
        var mode by remember { mutableStateOf<VideoMode?>(null) }
        var phase by remember { mutableStateOf(TakePhase.IDLE) }
        var continuedWithoutSpeech by remember { mutableStateOf(false) }
        var spokenOffset by remember { mutableStateOf<Int?>(0) }
        var manualOffset by remember { mutableStateOf<Int?>(null) }
        var activeFollower by remember { mutableStateOf<SpeechFollower?>(null) }
        var speechStatus by remember { mutableStateOf("") }
        var microphoneInput by remember {
            mutableStateOf(
                MicrophoneInput.entries.firstOrNull { it.name == preferences.getString("microphone_input", null) }
                    ?: MicrophoneInput.AUTOMATIC
            )
        }
        var microphoneRoute by remember { mutableStateOf("Checking mic…") }
        var status by remember { mutableStateOf("Opening camera…") }
        var savedVideo by remember {
            mutableStateOf(preferences.getString("latest_recording_uri", null)?.let(Uri::parse))
        }
        var previewRefresh by remember { mutableIntStateOf(0) }
        var recordingFolder by remember {
            mutableStateOf(preferences.getString("recording_folder_uri", null)?.let {
                RecordingFolder(Uri.parse(it), preferences.getString("recording_folder_name", null) ?: "Selected folder")
            })
        }
        var folderMessage by remember { mutableStateOf<String?>(null) }
        var importing by remember { mutableStateOf(false) }
        var hasImportedModel by remember { mutableStateOf(importedWhisperModel(this).isFile) }
        var useImportedModel by remember {
            mutableStateOf(hasImportedModel && preferences.getBoolean("imported_model", false) &&
                !preferences.getBoolean("sherpa_model", false))
        }
        val useSherpaModel = !useImportedModel
        var importedModelRevision by remember { mutableIntStateOf(0) }
        var speechTake by remember { mutableIntStateOf(0) }
        val scope = rememberCoroutineScope()
        val chooseFolder = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            val tree = data?.data
            if (result.resultCode == RESULT_OK && tree != null) {
                importing = true
                scope.launch {
                    withContext(Dispatchers.IO) {
                        runCatching { RecordingFolder.select(contentResolver, tree, data.flags) }
                    }.onSuccess { selected ->
                        recordingFolder = selected
                        preferences.edit().putString("recording_folder_uri", selected.uri.toString())
                            .putString("recording_folder_name", selected.name).apply()
                        folderMessage = null
                    }.onFailure {
                        folderMessage = it.message ?: "Could not use the selected folder."
                    }
                    importing = false
                }
            }
        }
        val importModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) {
                importing = true
                scope.launch {
                    val result = withContext(Dispatchers.IO) { runCatching { copyModel(uri) } }
                    result.onSuccess {
                        hasImportedModel = true
                        useImportedModel = true
                        continuedWithoutSpeech = false
                        importedModelRevision++
                        preferences.edit().putBoolean("imported_model", true).putBoolean("sherpa_model", false).apply()
                        status = "Imported Whisper model."
                    }.onFailure {
                        status = it.message ?: "Could not import the model."
                    }
                    importing = false
                }
            }
        }
        val idle = phase == TakePhase.IDLE && !importing
        val markdown = library.selected?.markdown.orEmpty()
        val script = remember(markdown) { renderPrompterMarkdown(markdown) }
        val canFollow = !continuedWithoutSpeech && script.spoken.isNotBlank()
        val speechModel = remember(useSherpaModel, importedModelRevision) {
            SpeechModelLoader(applicationContext, useSherpaModel)
        }
        val speechModelLabel = if (useSherpaModel) "Sherpa-ONNX · Zipformer" else "Whisper (imported)"
        var preparingSpeech by remember(speechModel, canFollow) { mutableStateOf(canFollow) }
        var speechPreparationFailed by remember(speechModel, canFollow) { mutableStateOf(false) }
        var speechPreparationAttempt by remember(speechModel) { mutableIntStateOf(0) }
        var startupComplete by remember { mutableStateOf(false) }
        DisposableEffect(speechModel) {
            onDispose { speechModel.close() }
        }
        DisposableEffect(speechModel, canFollow, speechPreparationAttempt) {
            var preparation = 0
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> {
                        if (canFollow) {
                            val request = ++preparation
                            preparingSpeech = true
                            speechPreparationFailed = false
                            speechModel.preload { loaded ->
                                runOnUiThread {
                                    if (preparation == request) {
                                        preparingSpeech = false
                                        speechPreparationFailed = !loaded
                                        if (loaded) startupComplete = true
                                    }
                                }
                            }
                        } else {
                            startupComplete = true
                        }
                    }
                    Lifecycle.Event.ON_STOP -> {
                        preparation++
                        preparingSpeech = canFollow
                        speechPreparationFailed = false
                        speechModel.clear()
                    }
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            onDispose {
                preparation++
                lifecycle.removeObserver(observer)
                speechModel.clear()
            }
        }
        val showStartup = !startupComplete && canFollow
        val lightNavigation = if (showScripts) !isSystemInDarkTheme() else showStartup
        LaunchedEffect(showScripts, lightNavigation) {
            updateSystemBars(showNavigationBar = showScripts)
            WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightNavigationBars = lightNavigation
        }
        if (showScripts) {
            ScriptsScreen(
                library,
                onChange = { updated ->
                    updated.save(preferences)
                    library = updated
                    spokenOffset = 0
                    manualOffset = null
                    speechTake++
                },
                onBack = { showScripts = false },
            )
            return
        }
        if (showStartup) {
            CueFlowTheme(darkTheme = false) {
                SpeechStartupScreen(
                    modelLabel = speechModelLabel,
                    failed = speechPreparationFailed,
                    onRetry = { speechPreparationAttempt++ },
                    onContinueWithoutSpeech = { continuedWithoutSpeech = true },
                )
            }
            return
        }
        val previewUri = savedVideo
        val latestPreview = produceState<RecordingPreview?>(null, previewUri, previewRefresh) {
            value = withContext(Dispatchers.IO) {
                loadRecordingPreview(this@MainActivity, previewUri) ?: if (previewUri != null) {
                    // The media provider can still be publishing a just-saved clip.
                    delay(250)
                    loadRecordingPreview(this@MainActivity, previewUri)
                } else null
            }
        }.value
        val scrollCommands = remember(script) { Channel<PrompterScroll>(Channel.UNLIMITED) }
        DisposableEffect(scrollCommands) {
            onDispose { scrollCommands.close() }
        }
        val seekScript: (Int, Boolean) -> Unit = { offset, fromSpeech ->
            manualOffset = offset
            spokenOffset = null
            val follower = activeFollower
            if (follower == null) speechTake++ else follower.seekTo(offset, preserveAudio = fromSpeech)
        }
        var zoom by remember(front, retry) { mutableFloatStateOf(1f) }
        var zoomRange by remember(front, retry) { mutableStateOf(Range(1f, 1f)) }
        var temperature by remember(front, retry) { mutableStateOf(CameraTemperature()) }
        var cameraFailure by remember(front, retry) { mutableStateOf<String?>(null) }
        val camera = remember(front, retry) {
            CameraPreview(
                this, front,
                onModes = { available ->
                    modes = available
                    val preferredHeight = preferences.getInt("video_height", 2160)
                    val quality = VideoQuality.entries.firstOrNull { it.height <= preferredHeight } ?: VideoQuality.HD
                    val preferredFps = preferences.getInt("video_fps", 30)
                    mode = available.firstOrNull { it.quality == quality && it.fps == preferredFps }
                        ?: available.firstOrNull { it.quality == quality && it.fps == 30 }
                        ?: available.firstOrNull { it.quality == quality }
                        ?: available.firstOrNull { it.fps == preferredFps }
                        ?: available.firstOrNull { it.fps == 30 }
                        ?: available.firstOrNull()
                },
                onReady = {
                    ready = it
                    if (it && mode != null) cameraFailure = null
                },
                onError = { cameraFailure = it },
                onAdjustmentError = { status = it },
                onLensChanged = { isFront, canSwitch ->
                    actualFront = isFront
                    canSwitchLens = canSwitch
                },
                onZoomChanged = { value, range ->
                    zoom = value
                    zoomRange = range
                },
                onTemperatureChanged = { temperature = it },
            )
        }
        DisposableEffect(camera) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        camera.resume()
                        previewRefresh++
                    }
                    Lifecycle.Event.ON_PAUSE -> camera.suspendCamera()
                    else -> Unit
                }
            }
            lifecycle.addObserver(observer)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) camera.resume()
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            onDispose {
                lifecycle.removeObserver(observer)
                camera.suspendCamera()
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        val rotation = displayRotation()
        val rotationMotion = rememberStudioRotation(rotation, viewport, orientation, resumed, idle, settings)
        val recordEnabled = (idle && ready && mode != null && !preparingSpeech) || phase == TakePhase.RECORDING
        val recordAction: () -> Unit = {
            if (phase == TakePhase.RECORDING) {
                phase = TakePhase.FINALIZING
                status = "Saving video…"
                camera.stopRecording()
            } else if (!rotationMotion.turning) {
                mode?.let { selected ->
                    phase = TakePhase.STARTING
                    requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
                    spokenOffset = if (manualOffset == null) 0 else null
                    val takeGeneration = ++speechTake
                    speechStatus = if (canFollow) "Preparing speech…" else ""
                    microphoneRoute = "Checking mic…"
                    status = "Starting recording…"
                    val follower = if (canFollow) {
                        SpeechFollower(
                            applicationContext, script.spoken,
                            {
                                if (speechTake == takeGeneration) {
                                    spokenOffset = it
                                    manualOffset = null
                                }
                            },
                            { if (speechTake == takeGeneration) speechStatus = it },
                            { if (speechTake == takeGeneration) scrollCommands.trySend(PrompterScroll(it, fromSpeech = true)) },
                            useSherpa = useSherpaModel,
                            preparedModel = speechModel,
                        ).apply { manualOffset?.let(::seekTo) }
                    } else null
                    activeFollower = follower
                    var recordedMode = selected
                    camera.record(selected, follower, recordingFolder, microphoneInput, onStarted = {
                        recordedMode = it
                        mode = it
                        phase = TakePhase.RECORDING
                        status = if (it == selected) "Recording" else "Recording at $it"
                    }, onMicrophoneRoute = {
                        microphoneRoute = it
                    }, onMicrophoneInterrupted = {
                        phase = TakePhase.FINALIZING
                        status = "Mic interrupted. Saving video…"
                    }, onFinished = { result ->
                        phase = TakePhase.IDLE
                        activeFollower = null
                        result.onSuccess { take ->
                            preferences.edit().putString("latest_recording_uri", take.uri.toString()).apply()
                            savedVideo = take.uri
                            status = buildString {
                                if (take.microphoneInterrupted) append("Mic interrupted. ")
                                if (take.usedFallback) append("Folder unavailable. ")
                                append("Video saved")
                                if (recordedMode != selected) append(" · $recordedMode")
                            }
                        }.onFailure { status = it.message ?: "Recording failed." }
                        requestedOrientation = if (rotationMotion.managed) ActivityInfo.SCREEN_ORIENTATION_LOCKED else orientation
                    })
                }
            }
        }
        val liveStatus = cameraFailure ?: when {
            phase == TakePhase.RECORDING && speechStatus.isNotEmpty() -> speechStatus
            idle && speechPreparationFailed -> "Speech model unavailable. Recording will retry."
            ready && status == "Opening camera…" -> "Ready"
            else -> status
        }
        val playTake: () -> Unit = {
            latestPreview?.uri?.let { video ->
                runCatching {
                    startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(video, "video/mp4")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                }.onFailure { status = "Couldn't open the video. Check your recording folder." }
            }
        }
        val retryCamera: () -> Unit = {
            ready = false
            status = "Opening camera…"
            retry++
        }
        val liveCameraControls = (idle && ready) || phase == TakePhase.RECORDING
        val iconMotion = Modifier.graphicsLayer { rotationZ = (rotationMotion.iconAngle.value - rotation * 90f) % 360f }
        val overlayFade = Modifier.graphicsLayer { alpha = rotationMotion.opacity.value }
        var showTemperature by remember(camera) { mutableStateOf(false) }
        LaunchedEffect(camera, showTemperature, liveCameraControls) {
            if (showTemperature && liveCameraControls) camera.prepareTemperature()
        }
        var focusMarker by remember(camera, landscape) { mutableStateOf<Offset?>(null) }
        var focusTap by remember(camera, landscape) { mutableIntStateOf(0) }
        LaunchedEffect(focusTap) {
            if (focusMarker != null) {
                delay(1_400)
                focusMarker = null
            }
        }
        val switchLens: () -> Unit = {
            ready = false
            modes = emptyList()
            status = "Opening camera…"
            front = !front
            preferences.edit().putBoolean("front", front).apply()
        }
        val switchLabel = if (actualFront) "Switch to rear camera" else "Switch to front camera"
        val recording = phase == TakePhase.RECORDING
        val recordControl: @Composable () -> Unit = {
            RecordButton(recording, recordEnabled, recordAction, iconModifier = iconMotion)
        }
        val recordingStatus: @Composable () -> Unit = {
            val colors = MaterialTheme.colorScheme
            val statusBackground = if (recording) colors.inverseSurface else colors.surfaceContainerHigh
            val statusContent = if (recording) colors.inverseOnSurface else colors.onSurface
            Surface(
                shape = MaterialTheme.shapes.large,
                color = statusBackground.copy(alpha = CameraOverlayOpacity),
                contentColor = statusContent,
            ) {
                Row(
                    Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(Modifier.size(6.dp).background(statusContent, CircleShape))
                    Text(
                        if (recording) "$microphoneRoute\n$liveStatus" else liveStatus,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = if (landscape) 4 else 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        val density = LocalDensity.current
        val cutout = WindowInsets.displayCutout
        val leftInset = cutout.getLeft(density, LayoutDirection.Ltr)
        val rightInset = cutout.getRight(density, LayoutDirection.Ltr)
        val bottomInset = cutout.getBottom(density)
        val toolbarOnLeft = when (rotation) {
            Surface.ROTATION_90 -> false
            Surface.ROTATION_270 -> true
            else -> rightInset > leftInset
        }
        // The dock uses the portrait end spacing along the same physical axis in landscape.
        val axisInset = with(density) {
            val inset = if (landscape) maxOf(cutout.getTop(density), bottomInset) else maxOf(leftInset, rightInset)
            inset.toDp() + DockPadding
        }
        val edgeInset = with(density) {
            val inset = if (!landscape) bottomInset else if (toolbarOnLeft) leftInset else rightInset
            inset.toDp()
        }
        val controlStrip: @Composable (Boolean) -> Unit = { reverseOrder ->
            RecordingControlStrip(
                vertical = landscape,
                reverseOrder = reverseOrder,
                axisInset = axisInset,
                edgeInset = edgeInset,
                adjustments = {
                    CameraToolbarAdjustments(
                        temperature = temperature,
                        temperatureOpen = showTemperature,
                        vertical = landscape,
                        reversed = reverseOrder,
                        enabled = liveCameraControls,
                        onToggleTemperature = { showTemperature = !showTemperature },
                        onTemperature = camera::setTemperature,
                        iconModifier = iconMotion,
                    ) {
                        CameraZoomBar(
                            zoom = zoom,
                            range = zoomRange,
                            front = actualFront,
                            vertical = landscape,
                            reversed = reverseOrder,
                            enabled = liveCameraControls,
                            onZoom = camera::setZoom,
                        )
                    }
                },
                controls = listOf(
                    {
                        key(latestPreview?.uri) {
                            RecordingPreviewTile(latestPreview, idle, ToolbarActionSize, playTake, iconModifier = iconMotion)
                        }
                    },
                    {
                        OverlayControl(R.drawable.ic_settings, "Settings", idle, iconMotion, buttonSize = ToolbarActionSize) {
                            settings = true
                        }
                    },
                    { recordControl() },
                    {
                        OverlayControl(R.drawable.ic_scripts, "Scripts", idle, iconMotion, buttonSize = ToolbarActionSize) {
                            showScripts = true
                        }
                    },
                    {
                        OverlayControl(
                            R.drawable.ic_switch_camera, switchLabel, idle && canSwitchLens,
                            iconModifier = iconMotion, buttonSize = ToolbarActionSize, onClick = switchLens,
                        )
                    },
                ),
            )
        }
        val cameraStatusControls: @Composable () -> Unit = {
            Column(
                modifier = if (landscape) Modifier.width(112.dp) else Modifier,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                recordingStatus()
                if (idle && (cameraFailure != null || (ready && mode == null))) {
                    FilledTonalButton(onClick = retryCamera) { Text("Retry") }
                }
            }
        }

        MaterialTheme(colorScheme = StudioColors) {
            Box(modifier.background(Color.Black)) {
                Box(Modifier.fillMaxSize().drawWithContent {
                    drawContent()
                    // The preview sits on black, so dim it without a full-screen offscreen layer.
                    val dim = 0.4f * (1f - rotationMotion.opacity.value)
                    if (dim > 0f) drawRect(Color.Black, alpha = dim)
                }.pointerInput(camera, liveCameraControls, landscape) {
                    if (liveCameraControls) detectTapGestures { tap ->
                        if (camera.focusAt(tap)) {
                            focusMarker = tap
                            focusTap++
                        }
                    }
                }) {
                    key(camera) {
                        AndroidView(factory = { camera }, modifier = Modifier.fillMaxSize())
                    }
                    focusMarker?.let { point ->
                        Canvas(Modifier.fillMaxSize()) {
                            drawCircle(Color.Black.copy(alpha = 0.55f), 26.dp.toPx(), point, style = Stroke(3.dp.toPx()))
                            drawCircle(StudioColors.primary, 26.dp.toPx(), point, style = Stroke(1.5.dp.toPx()))
                        }
                    }
                }
                Box(
                    Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    val prompterAlignment = when {
                        !landscape -> Alignment.TopCenter
                        toolbarOnLeft -> AbsoluteAlignment.CenterRight
                        else -> AbsoluteAlignment.CenterLeft
                    }
                    Prompter(
                        script.display, spokenOffset, fontSize, landscape, opacity,
                        Modifier.align(prompterAlignment).widthIn(max = 800.dp)
                            .fillMaxWidth(if (landscape) 0.5f else 1f).then(overlayFade),
                        scrollCommands, seekScript, iconModifier = iconMotion,
                        title = library.selected?.title,
                    )
                }
                val dockAlignment = when {
                    !landscape -> Alignment.BottomCenter
                    toolbarOnLeft -> AbsoluteAlignment.CenterLeft
                    else -> AbsoluteAlignment.CenterRight
                }
                // Keep the dock attached to the physical edge while its surface and contents fade together.
                Box(Modifier.align(dockAlignment).then(overlayFade)) {
                    ToolbarAxisLayout(
                        vertical = !landscape,
                        modifier = if (landscape) Modifier else Modifier.fillMaxWidth(),
                        reverseOrder = landscape && toolbarOnLeft,
                        spacing = 8.dp,
                    ) {
                        cameraStatusControls()
                        controlStrip(if (landscape) !toolbarOnLeft else rotation == Surface.ROTATION_180)
                    }
                }
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    if (idle && preparingSpeech && !settings) {
                        Surface(
                            modifier = Modifier.align(Alignment.Center).padding(16.dp),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = CameraOverlayOpacity),
                            contentColor = MaterialTheme.colorScheme.onSurface,
                        ) {
                            Row(
                                Modifier.padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                                Column {
                                    Text("Loading speech model…", style = MaterialTheme.typography.titleSmall)
                                    Text(speechModelLabel, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }

        if (settings) {
            val audioManager = remember { getSystemService(AudioManager::class.java) }
            var usbMicrophone by remember { mutableStateOf(audioManager.usbMicrophone()?.microphoneLabel()) }
            DisposableEffect(audioManager) {
                val callback = audioManager.watchAudioInputs {
                    usbMicrophone = audioManager.usbMicrophone()?.microphoneLabel()
                }
                onDispose { audioManager.unregisterAudioDeviceCallback(callback) }
            }
            val orientations = listOf(
                ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED to "Auto",
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT to "Portrait",
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE to "Landscape",
            )
            RecordingSettingsDialog(onDismiss = { settings = false }) {
                SettingsGroup("Capture") {
                    SettingPickerRow(
                        title = "Video quality",
                        detail = if (actualFront) "Front camera" else "Rear camera",
                        value = mode?.toString() ?: if (ready) "Unavailable" else "Checking…",
                        enabled = modes.isNotEmpty(),
                    ) { dismiss ->
                        modes.forEach { available ->
                            DropdownMenuItem(text = { Text(available.toString()) }, onClick = {
                                mode = available
                                preferences.edit().putInt("video_width", available.width)
                                    .putInt("video_height", available.height)
                                    .putInt("video_fps", available.fps).apply()
                                dismiss()
                            })
                        }
                    }
                    SettingPickerRow(
                        title = "Microphone",
                        value = microphoneInput.label,
                        detail = if (microphoneInput == MicrophoneInput.PHONE) "Built-in input"
                            else usbMicrophone ?: "Prefers USB when connected",
                    ) { dismiss ->
                        MicrophoneInput.entries.forEach { input ->
                            DropdownMenuItem(text = { Text(input.label) }, onClick = {
                                microphoneInput = input
                                preferences.edit().putString("microphone_input", input.name).apply()
                                dismiss()
                            })
                        }
                    }
                    SettingRow(
                        title = "Recording folder",
                        detail = recordingFolder?.name ?: DEFAULT_RECORDING_FOLDER,
                        modifier = Modifier.clickable(enabled = !importing, role = Role.Button, onClickLabel = "Choose folder") {
                            folderMessage = null
                            chooseFolder.launch(Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                                recordingFolder?.let { putExtra(DocumentsContract.EXTRA_INITIAL_URI, it.uri) }
                            })
                        },
                    ) {
                        SettingValue("Choose", enabled = !importing)
                    }
                    if (recordingFolder != null) {
                        TextButton(
                            modifier = Modifier.padding(horizontal = 8.dp),
                            enabled = !importing,
                            onClick = {
                                preferences.edit().remove("recording_folder_uri").remove("recording_folder_name").apply()
                                recordingFolder = null
                                folderMessage = null
                            },
                        ) { Text("Use default folder") }
                    }
                    folderMessage?.let {
                        Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
                    }
                    SettingPickerRow(
                        title = "Orientation",
                        value = orientations.firstOrNull { it.first == orientation }?.second ?: "Auto",
                    ) { dismiss ->
                        orientations.forEach { (value, label) ->
                            DropdownMenuItem(text = { Text(label) }, onClick = {
                                dismiss()
                                settings = false
                                orientation = value
                                preferences.edit().putInt("orientation", value).apply()
                                rotationMotion.request(value)
                            })
                        }
                    }
                }
                SettingsGroup("Teleprompter") {
                    SettingRow("Font size") {
                        FilledTonalButton(onClick = {
                            fontSize = (fontSize - 2).coerceAtLeast(14)
                            preferences.edit().putInt("font", fontSize).apply()
                        }) { Text("A−") }
                        Text(fontSize.toString(), Modifier.padding(horizontal = 6.dp))
                        FilledTonalButton(onClick = {
                            fontSize = (fontSize + 2).coerceAtMost(56)
                            preferences.edit().putInt("font", fontSize).apply()
                        }) { Text("A+") }
                    }
                    SettingRow("Background opacity", "$opacity%") {
                        FilledTonalButton(enabled = opacityOverride != null, onClick = {
                            opacityOverride = null
                            preferences.edit().remove("prompter_opacity").apply()
                        }) { Text("Reset opacity") }
                    }
                    Slider(
                        value = opacity.toFloat(),
                        onValueChange = { opacityOverride = it.roundToInt() },
                        onValueChangeFinished = {
                            opacityOverride?.let { preferences.edit().putInt("prompter_opacity", it).apply() }
                        },
                        valueRange = 0f..100f,
                        modifier = Modifier.padding(horizontal = 16.dp)
                            .semantics { contentDescription = "Script background opacity" },
                    )
                }
                SettingsGroup("Speech following") {
                    SettingPickerRow(title = "Speech model", value = speechModelLabel) { dismiss ->
                        DropdownMenuItem(text = { Text("Sherpa-ONNX · English Zipformer") }, onClick = {
                            useImportedModel = false
                            continuedWithoutSpeech = false
                            preferences.edit().putBoolean("sherpa_model", true).apply()
                            dismiss()
                        })
                        if (hasImportedModel) {
                            DropdownMenuItem(text = { Text("Whisper (imported)") }, onClick = {
                                useImportedModel = true
                                continuedWithoutSpeech = false
                                preferences.edit().putBoolean("imported_model", true).putBoolean("sherpa_model", false).apply()
                                dismiss()
                            })
                        }
                        DropdownMenuItem(text = { Text("Import Whisper model…") }, onClick = {
                            dismiss()
                            settings = false
                            importModel.launch(arrayOf("*/*"))
                        })
                    }
                    if (preparingSpeech) {
                        Row(
                            Modifier.padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                            Text("Loading speech model…", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }

    private fun copyModel(uri: Uri) {
        val temporary = File.createTempFile("import-", ".bin", filesDir)
        try {
            checkNotNull(contentResolver.openInputStream(uri)) { "Could not open the model file." }.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            check(temporary.length() >= 1024) { "The model file is too small." }
            Files.move(
                temporary.toPath(), importedWhisperModel(this).toPath(),
                StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
            )
        } finally {
            temporary.delete()
        }
    }

    private enum class TakePhase { IDLE, STARTING, RECORDING, FINALIZING }

    @Composable
    private fun displayRotation(): Int {
        val decor = window.decorView
        var rotation by remember { mutableIntStateOf(decor.display?.rotation ?: Surface.ROTATION_0) }
        DisposableEffect(Unit) {
            val displays = getSystemService(DisplayManager::class.java)
            val listener = object : DisplayManager.DisplayListener {
                override fun onDisplayAdded(displayId: Int) = Unit
                override fun onDisplayRemoved(displayId: Int) = Unit
                override fun onDisplayChanged(displayId: Int) {
                    decor.display?.takeIf { it.displayId == displayId }?.let { rotation = it.rotation }
                }
            }
            displays.registerDisplayListener(listener, null)
            onDispose { displays.unregisterDisplayListener(listener) }
        }
        val observedRotation = rotation
        return decor.display?.rotation ?: observedRotation
    }

    private companion object {
        const val DEFAULT_SCRIPT = "Welcome to CueFlow. This is a test of the built-in camera and live teleprompter. " +
            "The script should move as you read aloud. Take a short pause before continuing. " +
            "When you are ready, stop recording and play back the saved video."
    }
}

@Composable
private fun RecordingControlStrip(
    vertical: Boolean,
    reverseOrder: Boolean,
    axisInset: Dp,
    edgeInset: Dp,
    adjustments: @Composable () -> Unit,
    controls: List<@Composable () -> Unit>,
) {
    val colors = MaterialTheme.colorScheme
    // The outer edge is the same physical edge in both orientations.
    val outerPadding = edgeInset + DockGestureClearance - DockPadding
    val chrome = Modifier.background(colors.surfaceContainer.copy(alpha = 0.60f))
        .border(1.dp, colors.outlineVariant.copy(alpha = 0.65f), RectangleShape)
        .padding(DockPadding)
        .padding(
            horizontal = if (vertical) 0.dp else axisInset,
            vertical = if (vertical) axisInset else 0.dp,
        )
        .absolutePadding(
            left = if (vertical && !reverseOrder) outerPadding else 0.dp,
            right = if (vertical && reverseOrder) outerPadding else 0.dp,
            bottom = if (!vertical) outerPadding else 0.dp,
        )
    val recordIndex = controls.size / 2
    ToolbarAxisLayout(
        vertical = !vertical,
        modifier = (if (vertical) Modifier.fillMaxHeight() else Modifier.fillMaxWidth()).then(chrome),
        reverseOrder = vertical && !reverseOrder,
        spacing = 4.dp,
    ) {
        adjustments()
        ToolbarAxisLayout(
            vertical = vertical,
            modifier = if (vertical) Modifier.fillMaxHeight() else Modifier.fillMaxWidth(),
            reverseOrder = reverseOrder,
            spacing = DockControlSpacing,
            weights = controls.indices.map { if (it == recordIndex) 0f else 1f },
        ) {
            for (index in controls.indices) key(index) {
                Box(
                    modifier = if (index != recordIndex) Modifier else if (vertical) {
                        Modifier.height(RecordButtonSize)
                    } else {
                        Modifier.width(RecordButtonSize)
                    },
                    contentAlignment = Alignment.Center,
                    content = { controls[index]() },
                )
            }
        }
    }
}

@Composable
private fun RecordButton(
    recording: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    iconModifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    val size by animateDpAsState(
        targetValue = if (recording) 28.dp else 46.dp,
        animationSpec = spring(),
        label = "Record glyph size",
    )
    val corner by animateDpAsState(
        targetValue = if (recording) 7.dp else 23.dp,
        animationSpec = spring(),
        label = "Record glyph corner",
    )
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(RecordButtonSize)
            .background(if (recording) colors.primary else colors.surfaceContainerHighest, CircleShape)
            .border(2.dp, if (enabled) colors.primary else colors.outlineVariant, CircleShape)
            .semantics { contentDescription = if (recording) "Stop recording" else "Start recording" },
    ) {
        Box(
            iconModifier.size(size).background(
                when {
                    !enabled -> colors.onSurface.copy(alpha = 0.22f)
                    recording -> colors.onPrimary
                    else -> colors.primary
                },
                RoundedCornerShape(corner),
            ),
        )
    }
}

@Composable
private fun CueFlowMark() {
    Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
        Icon(
            painterResource(R.drawable.ic_cueflow_round),
            contentDescription = null,
            tint = Color.Unspecified,
            // The vector reserves a third of its width for the adaptive launcher mask.
            modifier = Modifier.requiredSize(132.dp),
        )
    }
}

@Composable
private fun SpeechStartupScreen(
    modelLabel: String,
    failed: Boolean,
    onRetry: () -> Unit,
    onContinueWithoutSpeech: () -> Unit,
) {
    Scaffold { insets ->
        Column(
            Modifier.fillMaxSize().padding(insets).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            CueFlowMark()
            Text("CueFlow", style = MaterialTheme.typography.headlineLarge)
            if (!failed) CircularProgressIndicator(Modifier.size(36.dp))
            Text(
                if (failed) "Couldn’t load the speech model" else "Loading speech model…",
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Text(modelLabel, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (failed) {
                Button(onClick = onRetry) { Text("Retry") }
                TextButton(onClick = onContinueWithoutSpeech) { Text("Continue without speech following") }
            }
        }
    }
}

@Composable
private fun OverlayControl(
    icon: Int, label: String, enabled: Boolean,
    iconModifier: Modifier = Modifier,
    buttonSize: Dp = 48.dp,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(buttonSize),
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = StudioColors.onSurface,
            disabledContentColor = StudioColors.onSurface.copy(alpha = 0.38f),
        ),
    ) {
        Icon(painterResource(icon), contentDescription = label, modifier = iconModifier.size(buttonSize - 24.dp))
    }
}

@Composable
internal fun Prompter(
    script: AnnotatedString,
    spoken: Int?,
    fontSize: Int,
    landscape: Boolean,
    opacity: Int,
    modifier: Modifier,
    scrollCommands: Channel<PrompterScroll>,
    onSeek: (Int, Boolean) -> Unit,
    scroll: ScrollState = rememberScrollState(),
    iconModifier: Modifier = Modifier,
    title: String? = null,
) {
    if (script.isEmpty()) return
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    var reflowAnchor by remember(script) { mutableStateOf<Int?>(null) }
    var preservedReadingPosition by remember(script) { mutableStateOf<ReadingPosition?>(null) }
    val seek by rememberUpdatedState(onSeek)
    val offset = spoken?.coerceIn(0, script.length) ?: 0
    val highlightedStart = remember(script, offset) {
        var start = offset
        while (start > 0 && (script[start - 1].isLetterOrDigit() || script[start - 1] in "'’")) start--
        start
    }
    val target = layout?.takeIf { it.layoutInput.text.length == script.length }?.let {
        it.getLineTop(it.getLineForOffset(highlightedStart)).toInt()
    } ?: 0
    LaunchedEffect(script, spoken != null, target, scroll.maxValue) {
        if (spoken != null) scroll.animateScrollTo(target, tween(180))
    }
    LaunchedEffect(reflowAnchor, layout, spoken != null) {
        val anchor = reflowAnchor ?: return@LaunchedEffect
        if (spoken == null) {
            layout?.let {
                scroll.scrollTo(it.getLineTop(it.getLineForOffset(anchor)).roundToInt())
                preservedReadingPosition = ReadingPosition(anchor, scroll.value)
            }
        }
        reflowAnchor = null
    }
    LaunchedEffect(script, scrollCommands) {
        for (command in scrollCommands) {
            val current = layout ?: continue
            // Scroll positions use integer pixels while line tops can be fractional.
            val currentLine = current.getLineForVerticalPosition(scroll.value + 1f)
            val targetLine = (currentLine + command.lines).coerceIn(0, current.lineCount - 1)
            val top = current.getLineTop(targetLine).coerceIn(0f, scroll.maxValue.toFloat())
            seek(current.getLineStart(current.getLineForVerticalPosition(top)), command.fromSpeech)
            scroll.animateScrollTo(top.roundToInt())
        }
    }
    val shape = MaterialTheme.shapes.large
    Column(
        modifier.fillMaxWidth().clip(shape)
            .border(1.dp, StudioColors.outlineVariant.copy(alpha = 0.85f), shape),
    ) {
        BoxWithConstraints(
            Modifier.fillMaxWidth().height(if (landscape) 132.dp else 260.dp)
                .background(StudioColors.surfaceContainerLowest.copy(alpha = opacity / 100f)),
        ) {
            val density = LocalDensity.current
            val lastLineHeight = layout?.let {
                it.getLineBottom(it.lineCount - 1) - it.getLineTop(it.lineCount - 1)
            } ?: with(density) { (fontSize * 1.26f).sp.toPx() }
            // A larger viewport must still let the final line reach the camera-side reading edge.
            val tail = (maxHeight - with(density) { lastLineHeight.toDp() } - 12.dp).coerceAtLeast(0.dp)
            Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(
                    buildAnnotatedString {
                        append(script)
                        if (highlightedStart > 0) {
                            addStyle(SpanStyle(color = StudioColors.onSurfaceVariant), 0, highlightedStart)
                        }
                        if (offset > highlightedStart) {
                            addStyle(
                                SpanStyle(color = StudioColors.inverseOnSurface, background = StudioColors.inverseSurface),
                                highlightedStart, offset,
                            )
                        }
                    },
                    Modifier.fillMaxWidth(),
                    color = StudioColors.onSurface,
                    style = MaterialTheme.typography.bodyLarge.copy(
                        shadow = Shadow(Color.Black.copy(alpha = 0.85f), Offset(0f, 1f), 3f),
                    ),
                    fontSize = fontSize.sp,
                    lineHeight = (fontSize * 1.26f).sp,
                    onTextLayout = { next ->
                        val previous = layout
                        if (spoken == null && previous != null && previous.size.width != next.size.width &&
                            previous.layoutInput.text.text == next.layoutInput.text.text
                        ) {
                            val line = previous.getLineForVerticalPosition(scroll.value + 1f)
                            reflowAnchor = preservedReadingPosition?.takeIf { it.scrollY == scroll.value }?.offset
                                ?: previous.getLineStart(line)
                        }
                        layout = next
                    },
                )
                Spacer(Modifier.height(tail))
            }
        }
        Row(
            Modifier.fillMaxWidth().background(StudioColors.surfaceContainer.copy(alpha = CameraOverlayOpacity))
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (title != null) {
                    Icon(painterResource(R.drawable.ic_scripts), null, Modifier.size(16.dp), tint = StudioColors.primary)
                    Text(
                        title,
                        color = StudioColors.onSurface,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            OverlayControl(R.drawable.ic_scroll_up, "Scroll up two lines", scroll.canScrollBackward, iconModifier) {
                scrollCommands.trySend(PrompterScroll(-2))
            }
            OverlayControl(R.drawable.ic_scroll_down, "Scroll down two lines", scroll.canScrollForward, iconModifier) {
                scrollCommands.trySend(PrompterScroll(2))
            }
        }
    }
}

internal data class PrompterScroll(val lines: Int, val fromSpeech: Boolean = false)
private data class ReadingPosition(val offset: Int, val scrollY: Int)
