package com.novaflow.cueflow

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingPolicyTest {
    @Test
    fun uhdIsAlwaysCappedAtThirtyEvenOnFasterHardware() {
        assertTrue(VideoQuality.UHD.supportsFrameRate(24))
        assertTrue(VideoQuality.UHD.supportsFrameRate(30))
        assertFalse(VideoQuality.UHD.supportsFrameRate(60))
        assertFalse(VideoQuality.UHD.supportsFrameRate(120))
    }

    @Test
    fun lowerResolutionsAllowSixtyButNeverHighSpeedRates() {
        for (quality in listOf(VideoQuality.QHD, VideoQuality.FHD, VideoQuality.HD)) {
            assertTrue(quality.supportsFrameRate(24))
            assertTrue(quality.supportsFrameRate(30))
            assertTrue(quality.supportsFrameRate(60))
            assertFalse(quality.supportsFrameRate(120))
            assertFalse(quality.supportsFrameRate(0))
        }
    }

    @Test
    fun aSixtyFpsEncoderFedThirtyFpsIsNotSixtyFpsSupport() {
        val thirty = (0..30).map { it * 33_350L }
        assertTrue(deliversFrameRate(thirty, 30))
        assertFalse(deliversFrameRate(thirty, 60))
        assertTrue(deliversFrameRate((0..60).map { it * 16_683L }, 60))
    }

    @Test
    fun rejectsEmptyShortOrInvalidTimestampSequences() {
        assertFalse(deliversFrameRate(emptyList(), 30))
        assertFalse(deliversFrameRate((0..8).map { it * 33_333L }, 30))
        assertFalse(deliversFrameRate(List(40) { 0L }, 30))
        assertFalse(deliversFrameRate((30 downTo 0).map { it * 33_333L }, 30))
    }
}
