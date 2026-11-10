package com.novaflow.cueflow

import android.graphics.Rect
import android.os.Bundle
import android.os.SystemClock
import android.util.Range
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.novaflow.cueflow.ui.theme.StudioColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CameraZoomBarTest {
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val zoom = mutableFloatStateOf(1f)
    private val sliderDescription = "Adjust camera zoom"

    @Test
    fun pressingCurrentPresetOpensZoomAndSwipingLeftZoomsIn() = showZoom {
        val preset = boundsOf("Camera zoom 1×")
        val x = preset.exactCenterX()
        val y = preset.exactCenterY()
        val start = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, x, y, start)
        node(sliderDescription)
        assertEquals(1f, zoomValue(), 0f)

        touch(MotionEvent.ACTION_MOVE, x - preset.width() / 2f, y, start)
        touch(MotionEvent.ACTION_MOVE, x - preset.width(), y, start)
        waitUntil { zoomValue() > 1f }

        touch(MotionEvent.ACTION_UP, x - preset.width(), y, start)

        assertCollapsed()
    }

    @Test
    fun tappingRightOnExpandedSliderSelectsHigherZoomAndClosesIt() = showZoom {
        tap("Camera zoom 1×")
        tap(sliderDescription, horizontalFraction = 0.66f)

        assertCollapsed()
        assertTrue("The right side of the slider should select a higher zoom.", zoomValue() > 2f)

        tap("Set camera zoom to 5×")
        assertCollapsed()
        assertEquals(5f, zoomValue(), 0f)

        tap("Set camera zoom to 0.5×")
        assertCollapsed()
        assertEquals(0.5f, zoomValue(), 0f)
    }

    @Test
    fun settingVerticalSliderProgressUsesZoomRatioAndClosesIt() = showZoom(vertical = true) {
        tap("Camera zoom 1×")
        // Compose can expose the content description as a child of the actionable slider.
        val slider = generateSequence(node(sliderDescription)) { it.parent }.first { it.rangeInfo != null }
        val progress = checkNotNull(slider.rangeInfo)
        assertEquals(1f, progress.min, 0f)
        assertEquals(1f, progress.current, 0f)

        val args = Bundle().apply {
            putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE, 3f)
        }
        assertTrue(slider.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id, args))

        assertCollapsed()
        assertEquals(3f, zoomValue(), 0f)
    }

    private fun showZoom(vertical: Boolean = false, test: () -> Unit) {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    MaterialTheme(colorScheme = StudioColors) {
                        Box(Modifier.size(288.dp), contentAlignment = Alignment.Center) {
                            CameraZoomBar(
                                zoom = zoom.floatValue,
                                range = Range(0.5f, 5f),
                                front = false,
                                vertical = vertical,
                                reversed = vertical,
                                enabled = true,
                                onZoom = { zoom.floatValue = it },
                            )
                        }
                    }
                }
            }
            test()
        }
    }

    private fun tap(description: String, horizontalFraction: Float = 0.5f) {
        val bounds = boundsOf(description)
        val x = bounds.left + bounds.width() * horizontalFraction
        val start = SystemClock.uptimeMillis()
        touch(MotionEvent.ACTION_DOWN, x, bounds.exactCenterY(), start)
        touch(MotionEvent.ACTION_UP, x, bounds.exactCenterY(), start)
    }

    private fun assertCollapsed() {
        waitUntil(timeout = 1_500) { findNode(sliderDescription) == null }
    }

    private fun boundsOf(description: String): Rect = Rect().apply {
        node(description).getBoundsInScreen(this)
    }

    private fun node(description: String): AccessibilityNodeInfo {
        waitUntil { findNode(description) != null }
        return checkNotNull(findNode(description))
    }

    private fun findNode(description: String): AccessibilityNodeInfo? =
        instrument.uiAutomation.rootInActiveWindow?.findByDescription(description)

    private fun AccessibilityNodeInfo.findByDescription(description: String): AccessibilityNodeInfo? {
        if (contentDescription == description) return this
        for (index in 0 until childCount) {
            getChild(index)?.findByDescription(description)?.let { return it }
        }
        return null
    }

    private fun touch(action: Int, x: Float, y: Float, start: Long) {
        val event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0)
        try {
            instrument.sendPointerSync(event)
        } finally {
            event.recycle()
        }
    }

    private fun zoomValue(): Float {
        var value = 0f
        instrument.runOnMainSync { value = zoom.floatValue }
        return value
    }

    private fun waitUntil(timeout: Long = 5_000, check: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + timeout
        while (SystemClock.uptimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(25)
        }
        error("Zoom toolbar did not reach the expected state.")
    }
}
