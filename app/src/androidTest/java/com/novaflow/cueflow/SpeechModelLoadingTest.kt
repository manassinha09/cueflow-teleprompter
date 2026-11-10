package com.novaflow.cueflow

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SpeechModelLoadingTest {
    @Test
    fun availableEnginesReuseTheirLoadedModelAcrossTakes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val availableEngines = if (importedWhisperModel(context).isFile) listOf(true, false) else listOf(true)
        for (useSherpa in availableEngines) {
            SpeechModelLoader(context, useSherpa).use { loader ->
                val cold = measureTake(loader, useSherpa, expectLoading = true)
                val warm = measureTake(loader, useSherpa, expectLoading = false)
                loader.clear()
                measureTake(loader, useSherpa, expectLoading = true)
                val engine = if (useSherpa) "Zipformer" else "Whisper"
                Log.i("CueFlowBenchmark", "$engine listeningReadyMs cold=$cold reused=$warm")
            }
        }
    }

    private fun measureTake(loader: SpeechModelLoader, useSherpa: Boolean, expectLoading: Boolean): Long {
        val idle = CountDownLatch(1)
        loader.execute { idle.countDown() }
        assertTrue("The speech worker should be idle before measuring.", idle.await(10, TimeUnit.SECONDS))

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val ready = CountDownLatch(1)
        val loadedDuringTake = AtomicBoolean()
        val readyMs = AtomicLong()
        val started = System.nanoTime()
        val follower = SpeechFollower(context, "Welcome to CueFlow.", {}, { status ->
            if (status.startsWith("Loading")) loadedDuringTake.set(true)
            if (status.startsWith("Listening")) {
                readyMs.set((System.nanoTime() - started) / 1_000_000)
                ready.countDown()
            }
        }, useSherpa = useSherpa, preparedModel = loader)
        follower.start()
        try {
            assertTrue("The selected engine should start listening.", ready.await(15, TimeUnit.SECONDS))
            assertEquals("Only a cold take should open a model.", expectLoading, loadedDuringTake.get())
            return readyMs.get()
        } finally {
            follower.stop()
        }
    }
}
