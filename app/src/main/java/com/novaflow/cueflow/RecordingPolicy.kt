package com.novaflow.cueflow

internal enum class VideoQuality(val label: String, val width: Int, val height: Int, val bitrate: Int, val maxFps: Int) {
    UHD("4K", 3840, 2160, 42_000_000, 30),
    QHD("2K", 2560, 1440, 24_000_000, 60),
    FHD("1080p", 1920, 1080, 14_000_000, 60),
    HD("720p", 1280, 720, 8_000_000, 60);

    fun supportsFrameRate(fps: Int): Boolean = fps in listOf(24, 30, 60) && fps <= maxFps
}

/** Measure delivered frames, not the frame-rate value written into an encoder format. */
internal fun deliversFrameRate(timestampsUs: List<Long>, fps: Int): Boolean {
    if (timestampsUs.size < fps / 2 + 1 || timestampsUs.zipWithNext().any { (a, b) -> b <= a }) return false
    val duration = timestampsUs.last() - timestampsUs.first()
    if (duration < 500_000) return false
    val measured = (timestampsUs.size - 1) * 1_000_000.0 / duration
    return measured in fps * 0.90..fps * 1.10
}
