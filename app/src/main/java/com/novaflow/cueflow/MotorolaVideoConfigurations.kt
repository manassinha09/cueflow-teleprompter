package com.novaflow.cueflow

internal data class AdditionalVideoConfiguration(val width: Int, val height: Int, val fps: Int)

/** Motorola's quadra_cfa.video_config contains width, height, and fps multiplied by 1000. */
internal fun parseMotorolaVideoConfigurations(values: IntArray?): List<AdditionalVideoConfiguration> {
    if (values == null || values.size % 3 != 0) return emptyList()
    return values.toList().chunked(3).mapNotNull { (width, height, rate) ->
        if (width <= 0 || height <= 0 || rate <= 0 || rate % 1000 != 0) return@mapNotNull null
        AdditionalVideoConfiguration(width, height, rate / 1000)
    }.distinct()
}
