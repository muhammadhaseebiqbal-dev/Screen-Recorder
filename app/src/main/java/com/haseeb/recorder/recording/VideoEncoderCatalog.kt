package com.haseeb.recorder.recording

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.os.Build

/** Selects supported MP4 video encoders with hardware preference and safe H.264 fallback. */
internal object VideoEncoderCatalog {
    const val ENCODER_H264 = "h264"
    const val ENCODER_HEVC = "hevc"
    const val ENCODER_AV1 = "av1"

    const val MIME_AVC = "video/avc"
    const val MIME_HEVC = "video/hevc"
    const val MIME_AV1 = "video/av01"

    data class EncoderCandidate(
        val name: String,
        val mimeType: String,
        val supportsSurface: Boolean,
        val hardwareAccelerated: Boolean,
        val supportsVbr: Boolean
    )

    fun mimeForKey(encoderKey: String): String = when (encoderKey) {
        ENCODER_HEVC -> MIME_HEVC
        ENCODER_AV1 -> MIME_AV1
        else -> MIME_AVC
    }

    fun isMuxingSupported(mimeType: String, sdkInt: Int): Boolean = when {
        mimeType.equals(MIME_AV1, ignoreCase = true) -> sdkInt >= Build.VERSION_CODES.S
        else -> true
    }

    fun isCodecSupported(
        encoderKey: String,
        sdkInt: Int,
        encoders: List<EncoderCandidate>
    ): Boolean = selectBestForMime(mimeForKey(encoderKey), sdkInt, encoders) != null

    fun selectBestForMime(
        mimeType: String,
        sdkInt: Int,
        encoders: List<EncoderCandidate>
    ): EncoderCandidate? {
        if (!isMuxingSupported(mimeType, sdkInt)) return null
        return encoders
            .filter { it.mimeType.equals(mimeType, ignoreCase = true) && it.supportsSurface }
            .sortedWith(compareByDescending<EncoderCandidate> { it.hardwareAccelerated }.thenBy { it.name })
            .firstOrNull()
    }

    fun fallbackCandidates(
        requestedKey: String,
        sdkInt: Int,
        encoders: List<EncoderCandidate>
    ): List<EncoderCandidate> {
        val primary = selectBestForMime(mimeForKey(requestedKey), sdkInt, encoders)
        val avc = selectBestForMime(MIME_AVC, sdkInt, encoders)
        val result = mutableListOf<EncoderCandidate>()
        if (primary != null) result += primary
        if (avc != null && result.none { it.name == avc.name && it.mimeType.equals(avc.mimeType, ignoreCase = true) }) {
            result += avc
        }
        return result
    }

    fun deviceEncoders(): List<EncoderCandidate> {
        val list = MediaCodecList(MediaCodecList.REGULAR_CODECS)
        val candidates = mutableListOf<EncoderCandidate>()
        for (info in list.codecInfos) {
            if (!info.isEncoder) continue
            val hw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                info.isHardwareAccelerated
            } else {
                !info.name.startsWith("OMX.google.", ignoreCase = true) &&
                    !info.name.startsWith("c2.android.", ignoreCase = true)
            }
            for (type in info.supportedTypes) {
                val caps = try {
                    info.getCapabilitiesForType(type)
                } catch (_: Exception) {
                    null
                } ?: continue
                val supportsSurface = caps.colorFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                val supportsVbr = caps.encoderCapabilities?.isBitrateModeSupported(
                    MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR
                ) == true
                candidates += EncoderCandidate(
                    name = info.name,
                    mimeType = type,
                    supportsSurface = supportsSurface,
                    hardwareAccelerated = hw,
                    supportsVbr = supportsVbr
                )
            }
        }
        return candidates
    }
}
