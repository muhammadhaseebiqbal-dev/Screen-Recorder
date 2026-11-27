package com.haseeb.recorder.shizuku

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.ParcelFileDescriptor
import android.os.Process
import androidx.annotation.Keep
import java.io.IOException

/**
 * Shizuku UserService running in the privileged server UID process to attempt loopback AudioPolicy
 * capture for ALLOW_CAPTURE_BY_SYSTEM streams when CAPTURE_MEDIA_OUTPUT / CAPTURE_AUDIO_OUTPUT
 * is available in that UID.
 */
@Keep
class EnhancedAudioUserService() : IEnhancedAudioService.Stub() {
    private var serviceContext: Context? = null

    @Keep
    constructor(context: Context) : this() {
        serviceContext = context
    }

    @Volatile private var running = false
    @Volatile private var activeSampleRate = 16_000
    @Volatile private var errorMessage = ""

    private var audioManager: AudioManager? = null
    private var audioPolicy: Any? = null
    private var audioRecord: AudioRecord? = null
    private var pipeStream: ParcelFileDescriptor.AutoCloseOutputStream? = null
    private var pumpThread: Thread? = null

    @SuppressLint("PrivateApi")
    private fun resolveContext(): Context? {
        serviceContext?.let { return it }
        return try {
            val atClass = Class.forName("android.app.ActivityThread")
            val current = atClass.getMethod("currentActivityThread").invoke(null)
            (atClass.getMethod("getSystemContext").invoke(current) as? Context)
                ?: (atClass.getMethod("currentApplication").invoke(null) as? Context)
        } catch (_: Throwable) {
            null
        }
    }

    override fun startPrivilegedCapture(writePipe: ParcelFileDescriptor?, excludeUid: Int): Int {
        stopPrivilegedCapture()
        if (writePipe == null) {
            errorMessage = "Null write pipe"
            return -1
        }
        val ctx = resolveContext()
        if (ctx == null) {
            runCatching { writePipe.close() }
            errorMessage = "Privileged Context unavailable"
            return -1
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (am == null) {
            runCatching { writePipe.close() }
            errorMessage = "AudioManager unavailable"
            return -1
        }

        return try {
            val mixingRuleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
            val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            val audioMixClass = Class.forName("android.media.audiopolicy.AudioMix")
            val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
            val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
            val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")

            val ruleMatchUsage = mixingRuleClass.getField("RULE_MATCH_ATTRIBUTE_USAGE").getInt(null)
            val ruleExcludeUid = mixingRuleClass.getField("RULE_EXCLUDE_UID").getInt(null)
            val routeLoopBack = audioMixClass.getField("ROUTE_FLAG_LOOP_BACK").getInt(null)
            val routeRender = audioMixClass.getField("ROUTE_FLAG_RENDER").getInt(null)

            val ruleBuilder = ruleBuilderClass.getConstructor().newInstance()
            val addMixRule = ruleBuilderClass.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
            for (usage in intArrayOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN)) {
                addMixRule.invoke(ruleBuilder, ruleMatchUsage, AudioAttributes.Builder().setUsage(usage).build())
            }
            if (excludeUid > 0) {
                ruleBuilderClass.getMethod("excludeMixRule", Int::class.javaPrimitiveType, Any::class.java)
                    .invoke(ruleBuilder, ruleExcludeUid, Integer.valueOf(excludeUid))
            }
            ruleBuilderClass.getMethod("allowPrivilegedPlaybackCapture", Boolean::class.javaPrimitiveType)
                .invoke(ruleBuilder, true)
            val mixingRule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder)

            // Privileged capture framework limits AudioMix format to 16 kHz mono PCM16.
            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(16_000)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
            val mixBuilder = mixBuilderClass.getConstructor(mixingRuleClass).newInstance(mixingRule)
            mixBuilderClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
            mixBuilderClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType)
                .invoke(mixBuilder, routeLoopBack or routeRender)
            val audioMix = mixBuilderClass.getMethod("build").invoke(mixBuilder)

            val policyBuilder = policyBuilderClass.getConstructor(Context::class.java).newInstance(ctx)
            policyBuilderClass.getMethod("addMix", audioMixClass).invoke(policyBuilder, audioMix)
            val policy = policyBuilderClass.getMethod("build").invoke(policyBuilder)
                ?: error("AudioPolicy.build() returned null")

            val registerRes = AudioManager::class.java
                .getMethod("registerAudioPolicy", policyClass)
                .invoke(am, policy) as Int
            if (registerRes != 0) {
                runCatching { writePipe.close() }
                errorMessage = "registerAudioPolicy returned $registerRes (uid=${Process.myUid()})"
                return -1
            }

            val record = policyClass.getMethod("createAudioRecordSink", audioMixClass)
                .invoke(policy, audioMix) as? AudioRecord
            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { record?.release() }
                unregisterPolicyQuietly(am, policy)
                runCatching { writePipe.close() }
                errorMessage = "createAudioRecordSink failed or uninitialized"
                return -1
            }

            audioManager = am
            audioPolicy = policy
            audioRecord = record
            activeSampleRate = 16_000
            errorMessage = ""
            running = true

            val out = ParcelFileDescriptor.AutoCloseOutputStream(writePipe)
            pipeStream = out
            record.startRecording()
            pumpThread = Thread({
                val shortBuf = ShortArray(640)
                val byteBuf = ByteArray(shortBuf.size * 2)
                try {
                    while (running) {
                        val read = record.read(shortBuf, 0, shortBuf.size)
                        if (read > 0) {
                            var bi = 0
                            for (i in 0 until read) {
                                val s = shortBuf[i].toInt()
                                byteBuf[bi++] = (s and 0xFF).toByte()
                                byteBuf[bi++] = ((s ushr 8) and 0xFF).toByte()
                            }
                            out.write(byteBuf, 0, read * 2)
                        } else if (read < 0) {
                            errorMessage = "AudioRecord read error $read"
                            break
                        }
                    }
                } catch (_: IOException) {
                } catch (t: Throwable) {
                    errorMessage = t.message ?: t.javaClass.simpleName
                } finally {
                    runCatching { out.close() }
                }
            }, "ShizukuEnhancedAudioPump").apply {
                isDaemon = true
                start()
            }
            activeSampleRate
        } catch (t: Throwable) {
            val root = t.cause ?: t
            errorMessage = "${root.javaClass.simpleName}: ${root.message}"
            runCatching { writePipe.close() }
            stopPrivilegedCapture()
            -1
        }
    }

    override fun stopPrivilegedCapture() {
        running = false
        runCatching { audioRecord?.stop() }
        runCatching { pipeStream?.close() }
        pipeStream = null
        runCatching { pumpThread?.join(400) }
        pumpThread = null
        runCatching { audioRecord?.release() }
        audioRecord = null
        val am = audioManager
        val policy = audioPolicy
        if (am != null && policy != null) {
            unregisterPolicyQuietly(am, policy)
        }
        audioManager = null
        audioPolicy = null
    }

    override fun getSampleRate(): Int = activeSampleRate

    override fun getLastError(): String = errorMessage

    override fun destroy() {
        stopPrivilegedCapture()
    }

    private fun unregisterPolicyQuietly(am: AudioManager, policy: Any) {
        try {
            val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
            AudioManager::class.java.getMethod("unregisterAudioPolicyAsync", policyClass).invoke(am, policy)
        } catch (_: Throwable) {
        }
    }
}
