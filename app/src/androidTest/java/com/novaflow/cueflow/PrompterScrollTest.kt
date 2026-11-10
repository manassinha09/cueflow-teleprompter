package com.novaflow.cueflow

import androidx.activity.compose.setContent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.novaflow.cueflow.ui.theme.CueFlowTheme
import kotlinx.coroutines.channels.Channel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** Uses native instrumentation, avoiding Espresso input injection on newer Android releases. */
@RunWith(AndroidJUnit4::class)
class PrompterScrollTest {
    @Test
    fun reflowPreservesTheManuallySelectedReadingLine() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val script = renderPrompterMarkdown((1..50).joinToString(" ") { "Section $it has a few words." })
        val width = mutableStateOf(300.dp)
        val scroll = ScrollState(0)
        val commands = Channel<PrompterScroll>(Channel.UNLIMITED)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    CueFlowTheme {
                        Prompter(script.display, null, 24, false, 68, Modifier.width(width.value),
                            commands, { _, _ -> }, scroll)
                    }
                }
            }
            waitUntil { scroll.maxValue in 1 until Int.MAX_VALUE }
            commands.trySend(PrompterScroll(10))
            waitUntil { scroll.value > 0 && !scroll.isScrollInProgress }
            val initial = scroll.value
            instrument.runOnMainSync { width.value = 180.dp }
            waitUntil { scroll.value > initial && !scroll.isScrollInProgress }
            instrument.runOnMainSync { width.value = 300.dp }
            waitUntil { abs(scroll.value - initial) <= 2 && !scroll.isScrollInProgress }
        }
        commands.close()
    }

    @Test
    fun finalLineReachesReadingEdgeInBothViewportSizesAndManualRewindStillWorks() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        for (landscape in listOf(false, true)) {
            val script = renderPrompterMarkdown("First line.\n\nSecond line.\n\n**Final line.**")
            val spoken = mutableStateOf<Int?>(0)
            val scroll = ScrollState(0)
            val commands = Channel<PrompterScroll>(Channel.UNLIMITED)
            var sought = -1
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.setContent {
                        CueFlowTheme {
                            Prompter(script.display, spoken.value, 24, landscape, 68, Modifier,
                                commands, { offset, _ -> sought = offset; spoken.value = null }, scroll)
                        }
                    }
                }
                waitUntil { scroll.maxValue in 1 until Int.MAX_VALUE }
                instrument.runOnMainSync { spoken.value = script.spoken.trimEnd('.').length }
                waitUntil { !scroll.isScrollInProgress && abs(scroll.maxValue - scroll.value) <= 2 }
                val end = scroll.value
                assertTrue("Final line should scroll up even when the script fits the enlarged viewport.", end > 0)
                commands.trySend(PrompterScroll(-2))
                waitUntil { sought >= 0 && !scroll.isScrollInProgress && scroll.value < end }
                val manual = scroll.value
                Thread.sleep(300)
                assertEquals("Following must not undo a manual rewind.", manual, scroll.value)
                assertTrue(sought < script.spoken.lastIndexOf("Final"))
                instrument.runOnMainSync { spoken.value = script.spoken.trimEnd('.').length }
                waitUntil { !scroll.isScrollInProgress && abs(scroll.maxValue - scroll.value) <= 2 }
            }
            commands.close()
        }
    }

    private fun waitUntil(check: () -> Boolean) {
        val instrument = InstrumentationRegistry.getInstrumentation()
        val deadline = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < deadline) {
            var ready = false
            instrument.runOnMainSync { ready = check() }
            if (ready) return
            Thread.sleep(30)
        }
        error("Prompter did not reach the expected reading position.")
    }
}
