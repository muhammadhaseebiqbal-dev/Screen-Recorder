package com.haseeb.recorder.recording

import com.haseeb.recorder.recording.VideoEncoderCatalog.EncoderCandidate
import org.junit.Assert.*
import org.junit.Test

class VideoEncoderCatalogTest {
    private val sampleEncoders = listOf(
        EncoderCandidate("c2.android.avc.encoder", VideoEncoderCatalog.MIME_AVC, supportsSurface = true, hardwareAccelerated = false, supportsVbr = true),
        EncoderCandidate("c2.qti.avc.encoder", VideoEncoderCatalog.MIME_AVC, supportsSurface = true, hardwareAccelerated = true, supportsVbr = true),
        EncoderCandidate("c2.qti.hevc.encoder", VideoEncoderCatalog.MIME_HEVC, supportsSurface = true, hardwareAccelerated = true, supportsVbr = true),
        EncoderCandidate("c2.android.av1.encoder", VideoEncoderCatalog.MIME_AV1, supportsSurface = true, hardwareAccelerated = false, supportsVbr = false),
        EncoderCandidate("c2.mtk.av1.encoder", VideoEncoderCatalog.MIME_AV1, supportsSurface = true, hardwareAccelerated = true, supportsVbr = true),
        EncoderCandidate("c2.broken.av1.nosurface", VideoEncoderCatalog.MIME_AV1, supportsSurface = false, hardwareAccelerated = true, supportsVbr = true)
    )

    @Test fun av1RequiresAndroid12ForMp4Muxing() {
        assertFalse(VideoEncoderCatalog.isCodecSupported(VideoEncoderCatalog.ENCODER_AV1, 30, sampleEncoders))
        assertTrue(VideoEncoderCatalog.isCodecSupported(VideoEncoderCatalog.ENCODER_AV1, 31, sampleEncoders))
    }

    @Test fun prefersHardwareAcceleratedSurfaceEncoderOverSoftware() {
        val selected = VideoEncoderCatalog.selectBestForMime(VideoEncoderCatalog.MIME_AV1, 34, sampleEncoders)
        assertNotNull(selected)
        assertEquals("c2.mtk.av1.encoder", selected!!.name)
        assertTrue(selected.supportsVbr)
    }

    @Test fun rejectsEncodersWithoutSurfaceInputSupport() {
        val onlyNoSurface = listOf(
            EncoderCandidate("c2.broken.av1.nosurface", VideoEncoderCatalog.MIME_AV1, supportsSurface = false, hardwareAccelerated = true, supportsVbr = true)
        )
        assertFalse(VideoEncoderCatalog.isCodecSupported(VideoEncoderCatalog.ENCODER_AV1, 34, onlyNoSurface))
    }

    @Test fun softwareAv1WorksWhenHardwareMissingAndPreservesVbrCapabilityFlag() {
        val swOnly = listOf(
            EncoderCandidate("c2.android.av1.encoder", VideoEncoderCatalog.MIME_AV1, supportsSurface = true, hardwareAccelerated = false, supportsVbr = false),
            EncoderCandidate("c2.qti.avc.encoder", VideoEncoderCatalog.MIME_AVC, supportsSurface = true, hardwareAccelerated = true, supportsVbr = true)
        )
        val candidates = VideoEncoderCatalog.fallbackCandidates(VideoEncoderCatalog.ENCODER_AV1, 34, swOnly)
        assertEquals(2, candidates.size)
        assertEquals("c2.android.av1.encoder", candidates[0].name)
        assertFalse(candidates[0].supportsVbr)
        assertEquals("c2.qti.avc.encoder", candidates[1].name)
    }

    @Test fun unsupportedAv1FallsBackDirectlyToAvc() {
        val candidates = VideoEncoderCatalog.fallbackCandidates(VideoEncoderCatalog.ENCODER_AV1, 29, sampleEncoders)
        assertEquals(1, candidates.size)
        assertEquals("c2.qti.avc.encoder", candidates[0].name)
    }
}
