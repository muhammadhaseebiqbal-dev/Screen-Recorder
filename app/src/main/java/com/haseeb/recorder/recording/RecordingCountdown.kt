package com.haseeb.recorder.recording

/** Counts only time for which the permission gateway is resumed and visible. */
internal class RecordingCountdown(remainingMillis: Long) {
    private var remaining = remainingMillis.coerceAtLeast(0)
    private var resumedAt: Long? = null

    fun resume(nowMillis: Long) {
        if (resumedAt == null) resumedAt = nowMillis
    }

    fun remainingMillis(nowMillis: Long): Long =
        (remaining - (resumedAt?.let { (nowMillis - it).coerceAtLeast(0) } ?: 0)).coerceAtLeast(0)

    fun seconds(nowMillis: Long): Int = ((remainingMillis(nowMillis) + 999) / 1000).toInt()

    fun pause(nowMillis: Long) {
        remaining = remainingMillis(nowMillis)
        resumedAt = null
    }
}
