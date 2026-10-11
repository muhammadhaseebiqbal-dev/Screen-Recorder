package com.haseeb.recorder.recording

import org.junit.Assert.*
import org.junit.Test

class AudioFramePumpTest {
    private class Source(var value: Short = 100, var available: Int = Int.MAX_VALUE) {
        var reads = 0
        var totalSamples = 0
        fun read(buffer: ShortArray, offset: Int, count: Int): Int {
            reads++
            if (available < 0) return available
            val size = minOf(count, available)
            buffer.fill(value, offset, offset + size)
            totalSamples += size
            return size
        }
    }

    private class Harness(val mic: Source = Source(), val system: Source = Source()) {
        val frames = mutableListOf<ByteArray>()
        val timestamps = mutableListOf<Long>()
        var ready = true
        var now = 1_000_000L
        val pump = AudioFramePump(mic::read, system::read, { pcm, pts ->
            if (ready) {
                frames.add(pcm.copyOf())
                timestamps.add(pts)
            }
            ready
        })
        fun step(paused: Boolean = false, muted: Boolean = false) {
            pump.step(now, paused, muted)
            now += 2_000
        }
        fun run(milliseconds: Int, paused: Boolean = false, muted: Boolean = false) {
            repeat(milliseconds / 2) { step(paused, muted) }
        }
        fun samples(frame: ByteArray = frames.last()): List<Int> = frame.asList().chunked(2).map {
            ((it[0].toInt() and 0xff) or (it[1].toInt() shl 8)).toShort().toInt()
        }
    }

    @Test fun microphoneContinuesWhenSystemSourceStopsAfterSeveralSeconds() {
        val h = Harness()
        h.run(5_000)
        val before = h.frames.size
        h.system.available = 0
        h.run(5_000)
        assertTrue(h.frames.size - before > 200)
        assertTrue(h.samples().all { it == 100 })
        h.system.available = Int.MAX_VALUE
        h.run(100)
        assertTrue(h.samples().all { it == 200 })
    }

    @Test fun systemAudioContinuesWhenMicrophoneReturnsDeadObject() {
        val h = Harness()
        h.run(3_000)
        h.mic.available = -6 // AudioRecord.ERROR_DEAD_OBJECT
        val before = h.frames.size
        h.run(3_000)
        assertTrue(h.frames.size - before > 100)
        assertTrue(h.samples().all { it == 100 })
    }

    @Test fun silenceStillFeedsEncoderWhenBothSourcesTemporarilyHaveNoData() {
        val h = Harness(Source(available = 0), Source(available = 0))
        h.run(1_000)
        assertTrue(h.frames.size in 45..47)
        assertTrue(h.samples().all { it == 0 })
    }

    @Test fun partialReadsAccumulateInsteadOfReusingStaleSamples() {
        val h = Harness(Source(available = 128), Source(available = 0))
        h.run(100)
        assertTrue(h.samples().all { it == 100 })
        h.mic.available = 0
        h.run(100)
        assertTrue(h.samples().all { it == 0 })
    }

    @Test fun partialFramePadsOnlyMissingSamplesWithSilence() {
        var readOnce = false
        val frames = mutableListOf<ByteArray>()
        val pump = AudioFramePump({ buffer, offset, _ ->
            if (readOnce) 0 else {
                readOnce = true
                buffer.fill(123, offset, offset + 128)
                128
            }
        }, { _, _, _ -> 0 }, { pcm, _ -> frames.add(pcm.copyOf()); true })
        pump.step(1_000_000, false, false)
        pump.step(1_022_000, false, false)
        assertEquals(1, frames.size)
        assertTrue(frames.single().take(256).chunked(2).all { it[0] == 123.toByte() && it[1] == 0.toByte() })
        assertTrue(frames.single().drop(256).all { it == 0.toByte() })
    }

    @Test fun pauseDrainsSourcesAndResumeExcludesPausedAudio() {
        val h = Harness()
        h.run(100)
        val frameCount = h.frames.size
        val readsBefore = h.mic.reads
        h.mic.value = 1_000
        h.system.value = 2_000
        h.run(5_000, paused = true)
        assertEquals(frameCount, h.frames.size)
        assertTrue(h.mic.reads > readsBefore)
        h.mic.value = 111
        h.system.value = 222
        h.run(100)
        assertTrue(h.samples().all { it == 333 })
    }

    @Test fun muteKeepsCaptureRunningAndUnmuteRestoresAudio() {
        val h = Harness()
        h.run(100, muted = true)
        assertTrue(h.samples().all { it == 0 })
        assertTrue(h.mic.totalSamples > 0 && h.system.totalSamples > 0)
        h.run(100)
        assertTrue(h.samples().all { it == 200 })
    }

    @Test fun encoderBackpressureRetainsFrameUntilInputIsAvailable() {
        val h = Harness()
        h.ready = false
        h.run(100)
        assertTrue(h.frames.isEmpty())
        h.mic.value = 1_000
        h.system.value = 2_000
        h.ready = true
        h.step()
        assertTrue(h.samples().all { it == 200 })
        h.run(100)
        assertTrue(h.samples().all { it == 3_000 })
    }

    @Test fun pauseDiscardsFrameWaitingOnEncoder() {
        val h = Harness()
        h.ready = false
        h.run(100)
        h.run(100, paused = true)
        h.mic.value = 111
        h.system.value = 222
        h.ready = true
        h.run(100)
        assertTrue(h.frames.all { frame -> h.samples(frame).all { it == 333 } })
    }

    @Test fun mixingLimitsPositiveAndNegativeOverflow() {
        val h = Harness(Source(30_000), Source(30_000))
        h.run(100)
        assertTrue(h.samples().all { it == Short.MAX_VALUE.toInt() })
        h.mic.value = -30_000
        h.system.value = -30_000
        h.run(100)
        assertTrue(h.samples().all { it == -Short.MAX_VALUE.toInt() })
    }

    @Test fun eitherSourceCanRecordAlone() {
        for (h in listOf(Harness(Source(available = 0)), Harness(system = Source(available = 0)))) {
            h.run(100)
            assertTrue(h.samples().all { it == 100 })
        }
    }

    @Test fun fiveMinuteCaptureStaysPacedWithMonotonicTimestamps() {
        val h = Harness()
        h.run(300_000)
        assertTrue(h.frames.size in 14_060..14_070)
        assertTrue(h.timestamps.zipWithNext().all { (a, b) -> b > a })
        assertTrue(h.frames.all { it.size == 2048 })
        assertTrue(h.samples().all { it == 200 })
    }
}
