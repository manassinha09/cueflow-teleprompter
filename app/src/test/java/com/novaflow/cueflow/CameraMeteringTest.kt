package com.novaflow.cueflow

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CameraMeteringTest {
    @Test
    fun portraitTapAccountsForFitAndFrontMirror() {
        val point = previewMeteringPoint(Offset(225f, 500f), 900, 1800, 1280, 720, 90, true, false)
        assertEquals(Offset(0.75f, 0.75f), point)
    }

    @Test
    fun tapsInTheBlackBarsAreIgnored() {
        assertNull(previewMeteringPoint(Offset(225f, 99f), 900, 1800, 1280, 720, 90, true, false))
    }

    @Test
    fun reverseLandscapeMapsToTheOppositeSensorCorner() {
        val point = previewMeteringPoint(Offset(400f, 675f), 1600, 900, 1280, 720, 180, false, false)
        assertEquals(Offset(0.75f, 0.25f), point)
    }
}
