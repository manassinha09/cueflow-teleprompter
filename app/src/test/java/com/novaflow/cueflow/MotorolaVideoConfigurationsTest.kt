package com.novaflow.cueflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotorolaVideoConfigurationsTest {
    @Test
    fun readsEdge60FusionFrontCameraMetadata() {
        assertEquals(listOf(
            AdditionalVideoConfiguration(3840, 2160, 30),
            AdditionalVideoConfiguration(3840, 1728, 30),
        ), parseMotorolaVideoConfigurations(intArrayOf(3840, 2160, 30000, 3840, 1728, 30000)))
    }

    @Test
    fun missingOrTruncatedMetadataAddsNoModes() {
        assertTrue(parseMotorolaVideoConfigurations(null).isEmpty())
        assertTrue(parseMotorolaVideoConfigurations(intArrayOf(3840, 2160)).isEmpty())
    }

    @Test
    fun rejectsInvalidEntriesAndDoesNotTreatWholeFpsAsMillifps() {
        assertEquals(listOf(AdditionalVideoConfiguration(3840, 2160, 30)),
            parseMotorolaVideoConfigurations(intArrayOf(
                3840, 2160, 30, 0, 2160, 30000, 3840, 2160, -30000,
                3840, 2160, 30000, 3840, 2160, 30000,
            )))
    }
}
