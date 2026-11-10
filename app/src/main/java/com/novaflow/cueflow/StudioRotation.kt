package com.novaflow.cueflow

import android.content.pm.ActivityInfo
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.OrientationEventListener
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

internal class StudioRotation(initialRotation: Int) {
    val opacity = Animatable(1f)
    val iconAngle = Animatable(initialRotation * 90f)
    var managed = false
    var sensorTarget by mutableStateOf<Int?>(null)
    var turn by mutableStateOf<StudioTurn?>(null)
        private set
    val turning get() = turn != null

    fun request(orientation: Int, targetRotation: Int? = null) {
        turn = StudioTurn(orientation, targetRotation)
    }

    fun finish() {
        turn = null
    }
}

internal data class StudioTurn(val orientation: Int, val targetRotation: Int?)

@Composable
internal fun ComponentActivity.rememberStudioRotation(
    rotation: Int,
    viewport: IntSize,
    orientation: Int,
    resumed: Boolean,
    idle: Boolean,
    settingsOpen: Boolean,
): StudioRotation {
    val motion = remember { StudioRotation(rotation) }
    val autoSetting = Settings.System.ACCELEROMETER_ROTATION
    fun isAutoRotateEnabled() = Settings.System.getInt(contentResolver, autoSetting, 0) == 1
    var autoRotate by remember { mutableStateOf(isAutoRotateEnabled()) }
    DisposableEffect(Unit) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) { autoRotate = isAutoRotateEnabled() }
        }
        contentResolver.registerContentObserver(Settings.System.getUriFor(autoSetting), false, observer)
        autoRotate = isAutoRotateEnabled()
        onDispose { contentResolver.unregisterContentObserver(observer) }
    }

    val landscape = viewport.width > viewport.height
    val portraitNatural = remember { landscape == (rotation % 2 == 1) }
    val hasSensor = remember { getSystemService(SensorManager::class.java).getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null }
    val phone = hasSensor && portraitNatural && LocalConfiguration.current.smallestScreenWidthDp < 600 && !isInMultiWindowMode
    val managed = resumed && phone && (autoRotate || orientation != ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED)
    val currentOrientation by rememberUpdatedState(orientation)
    val currentRotation by rememberUpdatedState(rotation)
    val currentLandscape by rememberUpdatedState(landscape)
    val currentIdle by rememberUpdatedState(idle)
    val currentManaged by rememberUpdatedState(managed)

    DisposableEffect(Unit) {
        onDispose {
            if (motion.managed && currentIdle) requestedOrientation = currentOrientation
        }
    }
    LaunchedEffect(managed, idle) {
        if (idle && !motion.turning) {
            if (managed) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
            else if (motion.managed) requestedOrientation = currentOrientation
        }
        motion.managed = managed
    }
    DisposableEffect(managed) {
        val sensor = object : OrientationEventListener(this@rememberStudioRotation, SensorManager.SENSOR_DELAY_UI) {
            override fun onOrientationChanged(degrees: Int) {
                val snapped = ((degrees + 45) / 90) * 90
                // Leave a dead band between orientations so a tilted phone does not chatter.
                motion.sensorTarget = if (degrees == ORIENTATION_UNKNOWN || abs(degrees - snapped) > 28) null
                    else ((360 - snapped) / 90) % 4
            }
        }
        if (managed && sensor.canDetectOrientation()) sensor.enable()
        onDispose { sensor.disable() }
    }
    val target = motion.sensorTarget
    var lastSensorTurn by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    LaunchedEffect(target, managed, idle, settingsOpen, motion.turn, orientation, rotation) {
        if (target == null || target == rotation) {
            lastSensorTurn = null
            return@LaunchedEffect
        }
        val direction = rotation to target
        if (!managed || !idle || settingsOpen || motion.turning || direction == lastSensorTurn ||
            requestedOrientation != ActivityInfo.SCREEN_ORIENTATION_LOCKED) return@LaunchedEffect
        val permitted = when (orientation) {
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT -> target % 2 == 0
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE -> target % 2 == 1
            else -> target != Surface.ROTATION_180
        }
        if (permitted) {
            lastSensorTurn = direction
            motion.request(fixedOrientation(target), target)
        }
    }

    var previousRotation by remember { mutableIntStateOf(rotation) }
    var targetAngle by remember { mutableFloatStateOf(rotation * 90f) }
    LaunchedEffect(rotation) {
        if (rotation != previousRotation) {
            val turn = (rotation - previousRotation + 4) % 4
            previousRotation = rotation
            targetAngle += (if (turn == 3) -1 else turn) * 90f
            motion.iconAngle.animateTo(targetAngle, tween(360, delayMillis = 55, easing = FastOutSlowInEasing))
        }
    }

    val turn = motion.turn
    LaunchedEffect(turn, resumed) {
        if (turn == null) return@LaunchedEffect
        if (!resumed || !currentIdle || (turn.targetRotation != null && !currentManaged)) {
            if (currentIdle) requestedOrientation = currentOrientation
            motion.opacity.snapTo(1f)
            motion.finish()
            return@LaunchedEffect
        }
        val startRotation = currentRotation
        val targetLandscape = when (turn.orientation) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE -> true
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT,
            ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT -> false
            else -> null
        }
        val changesScreen = turn.targetRotation != null || (targetLandscape != null && targetLandscape != currentLandscape)
        if (changesScreen) {
            motion.opacity.animateTo(0.3f, tween(180, easing = FastOutSlowInEasing))
            // Let the dimmed frame draw before asking WindowManager to turn the display.
            repeat(2) { withFrameNanos { } }
        }
        if (turn.targetRotation == null || (currentManaged && motion.sensorTarget == turn.targetRotation)) {
            if (changesScreen || !currentManaged) requestedOrientation = turn.orientation
            if (changesScreen) withTimeoutOrNull(750) {
                snapshotFlow { currentRotation to currentLandscape }.first { (display, wide) ->
                    display != startRotation && (turn.targetRotation == null || display == turn.targetRotation) &&
                        (targetLandscape == null || wide == targetLandscape)
                }
                repeat(2) { withFrameNanos { } }
            }
            if (currentManaged) requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else if (!currentManaged) {
            requestedOrientation = currentOrientation
        }
        motion.opacity.animateTo(1f, tween(360, easing = FastOutSlowInEasing))
        motion.finish()
    }
    return motion
}

private fun fixedOrientation(rotation: Int) = when (rotation) {
    Surface.ROTATION_90 -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
    Surface.ROTATION_180 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
    Surface.ROTATION_270 -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
    else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
}
