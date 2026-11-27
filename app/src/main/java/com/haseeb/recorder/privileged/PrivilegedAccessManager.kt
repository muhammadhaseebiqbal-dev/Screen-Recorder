package com.haseeb.recorder.privileged

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.haseeb.recorder.data.ConfigManager
import com.haseeb.recorder.recording.EnhancedAudioDiagnostics
import com.haseeb.recorder.shizuku.ShizukuManager
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** Routes advanced controls through the backend explicitly selected in settings. */
object PrivilegedAccessManager {
    private val worker = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()
    private val runner = ShellCommandRunner()
    private val root = RootShell(runner::run)
    private val router = PrivilegedBackendRouter({ root.isGranted },
        ShizukuManager::isPermissionGranted, root::execute, ShizukuManager::executeShell)
    private val taskBackend = ThreadLocal<Boolean>()
    private lateinit var config: ConfigManager
    @Volatile var isRequestingPermission = false
        private set

    val usesRoot: Boolean get() = config.privilegedBackend == ConfigManager.BACKEND_ROOT

    fun init(context: Context) {
        config = ConfigManager(context.applicationContext)
        ShizukuManager.addPermissionListener { if (!usesRoot) notifyListeners() }
        ShizukuManager.addBinderListener { if (!usesRoot) notifyListeners() }
        ShizukuManager.init { !usesRoot }
        if (usesRoot) requestPermission()
    }

    fun isPermissionGranted(): Boolean =
        router.isGranted(taskBackend.get() ?: usesRoot)

    fun addPermissionListener(listener: (Boolean) -> Unit) { listeners.addIfAbsent(listener) }
    fun removePermissionListener(listener: (Boolean) -> Unit) { listeners.remove(listener) }

    fun selectBackend(backend: String) {
        config.privilegedBackend = backend
        notifyListeners()
        requestPermission()
    }

    fun requestPermission() {
        if (isRequestingPermission) return
        if (!usesRoot) {
            ShizukuManager.requestPermissionExplicit()
            notifyListeners()
            return
        }
        isRequestingPermission = true
        notifyListeners()
        runAsync {
            try { root.requestPermission() }
            finally {
                isRequestingPermission = false
                notifyListeners()
            }
        }
    }

    private fun notifyListeners() {
        mainHandler.post { listeners.forEach { it(isPermissionGranted()) } }
    }

    fun runAsync(rootSelected: Boolean = usesRoot, task: () -> Unit) {
        worker.execute {
            taskBackend.set(rootSelected)
            try { task() } finally { taskBackend.remove() }
        }
    }

    fun executeShell(command: String): Boolean {
        val rootSelected = taskBackend.get() ?: usesRoot
        val wasGranted = router.isGranted(rootSelected)
        val success = router.execute(rootSelected, command)
        if (wasGranted && !router.isGranted(rootSelected)) notifyListeners()
        return success
    }

    fun setShowTouches(enabled: Boolean): Boolean = executeShell("settings put system show_touches ${if (enabled) 1 else 0}")

    fun setImmersiveMode(enabled: Boolean): Boolean = executeShell(
        if (enabled) "settings put global policy_control immersive.full=*" else "settings delete global policy_control"
    )

    internal fun getEnhancedAudioCapabilities(
        context: Context,
        rootSelected: Boolean = taskBackend.get() ?: usesRoot
    ): EnhancedAudioDiagnostics.CapabilitySummary {
        val pm = android.content.pm.PackageManager.PERMISSION_GRANTED
        val appAudioOutput = context.checkSelfPermission("android.permission.CAPTURE_AUDIO_OUTPUT") == pm
        val appMediaOutput = context.checkSelfPermission("android.permission.CAPTURE_MEDIA_OUTPUT") == pm
        val remoteMedia = !rootSelected && ShizukuManager.hasRemotePermission("android.permission.CAPTURE_MEDIA_OUTPUT")
        val remoteAudio = !rootSelected && ShizukuManager.hasRemotePermission("android.permission.CAPTURE_AUDIO_OUTPUT")
        return EnhancedAudioDiagnostics.CapabilitySummary(
            appHasCaptureAudioOutput = appAudioOutput,
            appHasCaptureMediaOutput = appMediaOutput,
            shizukuRemoteHasCaptureMedia = remoteMedia,
            shizukuRemoteHasCaptureAudio = remoteAudio
        )
    }

    fun grantEnhancedAudioPermission(context: Context): Boolean {
        val app = context.packageName
        executeShell("pm grant $app android.permission.CAPTURE_AUDIO_OUTPUT")
        executeShell("pm grant $app android.permission.CAPTURE_MEDIA_OUTPUT")
        executeShell("cmd appops set $app CAPTURE_AUDIO_OUTPUT allow")
        executeShell("cmd appops set $app CAPTURE_MEDIA_OUTPUT allow")
        executeShell("cmd appops set $app PROJECT_MEDIA allow")
        executeShell("cmd appops set $app RECORD_AUDIO allow")
        return getEnhancedAudioCapabilities(context).hasAnyPrivilegedCapture
    }

    fun grantProjectMediaPermission(context: Context): Boolean {
        val app = context.packageName
        val permission = executeShell("pm grant $app android.permission.PROJECT_MEDIA")
        val project = executeShell("cmd appops set $app PROJECT_MEDIA allow")
        val legacy = executeShell("appops set $app 46 allow")
        return permission || project || legacy
    }
}
