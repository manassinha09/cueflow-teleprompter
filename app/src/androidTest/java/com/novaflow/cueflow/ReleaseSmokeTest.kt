package com.novaflow.cueflow

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Black-box check for the official APK; sign the test APK with the same release certificate. */
@RunWith(AndroidJUnit4::class)
class ReleaseSmokeTest {
    @Test
    fun recordsWithBundledSpeechAndDecodesTheSavedVideo() {
        assumeTrue(InstrumentationRegistry.getArguments().getString("releaseSmoke") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var recording: Uri? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                waitFor("Start recording", 120_000)
                click("Start recording")
                waitFor("Listening · Zipformer", 90_000, partial = true)
                waitFor("Stop recording")
                Thread.sleep(3_000)
                click("Stop recording")
                waitFor("Video saved", partial = true)
                waitFor("Start recording")
                val preferences = context.getSharedPreferences("prompter", 0)
                recording = Uri.parse(checkNotNull(preferences.getString("latest_recording_uri", null)))
                waitFor("Open latest recording")
                scenario.recreate()
                waitFor("Start recording", 90_000)
                waitFor("Open latest recording")
            }
            val extractor = MediaExtractor()
            try {
                extractor.setDataSource(context, checkNotNull(recording), null)
                val mimes = (0 until extractor.trackCount).map { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty() }
                assertTrue("The release must save video.", mimes.any { it.startsWith("video/") })
                assertTrue("The release must save microphone audio.", mimes.any { it.startsWith("audio/") })
            } finally { extractor.release() }
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, checkNotNull(recording))
                checkNotNull(retriever.getFrameAtTime(1_000_000)) { "The release recording must decode." }.recycle()
            } finally { retriever.release() }
        } finally {
            recording?.let { context.contentResolver.delete(it, null, null) }
        }
    }

    private fun waitFor(label: String, timeout: Long = 30_000, partial: Boolean = false): AccessibilityNodeInfo {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        val packageName = InstrumentationRegistry.getInstrumentation().targetContext.packageName
        val deadline = System.nanoTime() + timeout * 1_000_000
        while (System.nanoTime() < deadline) {
            val nodes = java.util.ArrayDeque<AccessibilityNodeInfo>()
            automation.rootInActiveWindow?.let(nodes::add)
            while (nodes.isNotEmpty()) {
                val node = nodes.removeFirst()
                val text = node.text?.toString()
                if (node.packageName?.toString() == packageName && node.isEnabled &&
                    (text == label || node.contentDescription?.toString() == label || (partial && text?.contains(label) == true))) return node
                for (index in 0 until node.childCount) node.getChild(index)?.let(nodes::add)
            }
            Thread.sleep(100)
        }
        error("Release control '$label' was not available.")
    }

    private fun click(label: String) {
        var node: AccessibilityNodeInfo? = waitFor(label)
        while (node != null && !node.isClickable) node = node.parent
        assertTrue("Could not click $label.", node?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true)
    }
}
