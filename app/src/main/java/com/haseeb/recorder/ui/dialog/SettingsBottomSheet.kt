package com.haseeb.recorder.ui.dialog

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.DynamicColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.shape.ShapeAppearanceModel
import com.haseeb.recorder.R
import com.haseeb.recorder.data.ConfigManager
import com.haseeb.recorder.databinding.SheetSettingsBinding
import com.haseeb.recorder.overlay.RecordingOverlayService
import com.haseeb.recorder.recording.ScreenRecordService
import com.haseeb.recorder.shizuku.ShizukuManager
import com.haseeb.recorder.ui.activity.AboutActivity
import com.haseeb.recorder.util.LocaleHelper

/*
 * Manages the settings bottom sheet dialog.
 * Handles audio, video quality, encoder, orientation, storage, lock screen, Shizuku, and theme configurations.
 */
class SettingsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: SheetSettingsBinding? = null
    private val binding get() = _binding!!

    private lateinit var configManager: ConfigManager
    private var isProgrammaticShizukuUpdate = false

    private val shizukuPermissionListener: (Boolean) -> Unit = { granted ->
        activity?.runOnUiThread {
            if (granted) {
                if (configManager.isShizukuDirectRecordingEnabled) {
                    context?.let { ShizukuManager.grantProjectMediaPermission(it) }
                }
                if (configManager.isShizukuEnhancedAudioEnabled) {
                    context?.let { ShizukuManager.grantEnhancedAudioPermission(it) }
                }
            }
            updateShizukuUI()
            setupDynamicFps()
        }
    }

    private val shizukuBinderListener: () -> Unit = {
        activity?.runOnUiThread {
            updateShizukuUI()
            setupDynamicFps()
        }
    }

    private val storagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let { treeUri ->
            val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            try {
                requireContext().contentResolver.takePersistableUriPermission(treeUri, flags)
                configManager.customStorageUri = treeUri.toString()
                updateStorageUI()
            } catch (e: Exception) {
                configManager.customStorageUri = treeUri.toString()
                updateStorageUI()
            }
        }
    }

    /*
     * Inflates the bottom sheet layout and initializes the configuration manager.
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetSettingsBinding.inflate(inflater, container, false)
        configManager = ConfigManager(requireContext())
        return binding.root
    }

    /*
     * Sets up UI components, loads saved settings, and binds change listeners.
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requireActivity().window.isNavigationBarContrastEnforced = false
        }

        ShizukuManager.addPermissionListener(shizukuPermissionListener)
        ShizukuManager.addBinderListener(shizukuBinderListener)

        setupDynamicQualities()
        setupDynamicFps()
        setupDynamicBitrates()
        loadCurrentSettings()
        setupAppearanceSection()
        setupListeners()
    }

    /*
     * Restores all saved preferences and synchronizes the corresponding UI toggles.
     */
    private fun loadCurrentSettings() {
        binding.switchMic.isChecked = configManager.isMicEnabled
        binding.switchSystemAudio.isChecked = configManager.isSystemAudioEnabled
        binding.switchRecordingOverlay.isChecked = configManager.isRecordingOverlayEnabled
        binding.switchOverlayShowPause.isChecked = configManager.isOverlayShowPauseEnabled
        binding.switchOverlayShowStop.isChecked = configManager.isOverlayShowStopEnabled
        binding.switchOverlayShowMute.isChecked = configManager.isOverlayShowMuteEnabled
        binding.switchOverlayShowCamera.isChecked = configManager.isOverlayShowCameraEnabled
        binding.switchOverlayShowDraw.isChecked = configManager.isOverlayShowDrawEnabled
        updateOverlaySubOptionsUI(configManager.isRecordingOverlayEnabled)

        val overlayStyleBtnId = if (configManager.overlayStyle == ConfigManager.OVERLAY_STYLE_RADIAL) {
            R.id.btnStyleRadial
        } else {
            R.id.btnStyleHorizontal
        }
        binding.buttonGroupOverlayStyle.check(overlayStyleBtnId)
        validateRadialStyleAvailability()

        binding.switchDynamicColors.isChecked = configManager.isDynamicColorsEnabled
        binding.switchPauseOnScreenOff.isChecked = configManager.isPauseOnScreenOff
        updatePauseOnScreenOffUI(configManager.isPauseOnScreenOff)

        val countdownButtonId = when (configManager.countdownSeconds) {
            0 -> R.id.btnCountdownOff
            5 -> R.id.btnCountdown5s
            10 -> R.id.btnCountdown10s
            else -> R.id.btnCountdown3s
        }
        binding.buttonGroupCountdown.check(countdownButtonId)

        var qualityButtonId = when (configManager.videoQuality) {
            ConfigManager.QUALITY_MAX -> R.id.btnQualityMax
            ConfigManager.QUALITY_4K -> R.id.btnQuality4K
            ConfigManager.QUALITY_2K -> R.id.btnQuality2K
            ConfigManager.QUALITY_1080P -> R.id.btnQuality1080
            ConfigManager.QUALITY_720P -> R.id.btnQuality720
            ConfigManager.QUALITY_480P -> R.id.btnQuality480
            ConfigManager.QUALITY_360P -> R.id.btnQuality360
            ConfigManager.QUALITY_240P -> R.id.btnQuality240
            else -> R.id.btnQualityMax
        }
        val qualityBtn = binding.buttonGroupQuality.findViewById<View>(qualityButtonId)
        if (qualityBtn == null || qualityBtn.visibility == View.GONE) {
            qualityButtonId = R.id.btnQualityMax
            configManager.videoQuality = ConfigManager.QUALITY_MAX
        }
        binding.buttonGroupQuality.check(qualityButtonId)

        var fpsButtonId = when (configManager.videoFps) {
            120 -> R.id.btnFps120
            90 -> R.id.btnFps90
            60 -> R.id.btnFps60
            30 -> R.id.btnFps30
            24 -> R.id.btnFps24
            15 -> R.id.btnFps15
            else -> R.id.btnFps60
        }
        val fpsBtn = binding.buttonGroupFps.findViewById<View>(fpsButtonId)
        if (fpsBtn == null || fpsBtn.visibility == View.GONE) {
            fpsButtonId = R.id.btnFps60
            configManager.videoFps = 60
        }
        binding.buttonGroupFps.check(fpsButtonId)

        var bitrateButtonId = when (configManager.videoBitrate) {
            24_000_000 -> R.id.btnBitrate24
            16_000_000 -> R.id.btnBitrate16
            12_000_000 -> R.id.btnBitrate12
            8_000_000 -> R.id.btnBitrate8
            5_000_000 -> R.id.btnBitrate5
            2_000_000 -> R.id.btnBitrate2
            1_000_000 -> R.id.btnBitrate1
            else -> R.id.btnBitrateAuto
        }
        val bitrateBtn = binding.buttonGroupBitrate.findViewById<View>(bitrateButtonId)
        if (bitrateBtn == null || bitrateBtn.visibility == View.GONE) {
            bitrateButtonId = R.id.btnBitrateAuto
            configManager.videoBitrate = 0
        }
        binding.buttonGroupBitrate.check(bitrateButtonId)

        val encoderButtonId = when (configManager.videoEncoder) {
            ConfigManager.ENCODER_HEVC -> R.id.btnEncoderHevc
            else -> R.id.btnEncoderH264
        }
        binding.buttonGroupEncoder.check(encoderButtonId)
        if (!configManager.isHevcSupported()) {
            binding.btnEncoderHevc.isEnabled = false
            binding.btnEncoderHevc.alpha = 0.5f
        }

        val orientationButtonId = when (configManager.videoOrientation) {
            ConfigManager.ORIENTATION_PORTRAIT -> R.id.btnOrientationPortrait
            ConfigManager.ORIENTATION_LANDSCAPE -> R.id.btnOrientationLandscape
            else -> R.id.btnOrientationAuto
        }
        binding.buttonGroupOrientation.check(orientationButtonId)

        updateStorageUI()

        val themeButtonId = when (configManager.themeMode) {
            ConfigManager.THEME_LIGHT -> R.id.btnThemeLight
            ConfigManager.THEME_DARK -> R.id.btnThemeDark
            else -> R.id.btnThemeSystem
        }
        binding.buttonGroupTheme.check(themeButtonId)

        updateLanguageUI()
        updateShizukuUI()
    }

    /*
     * Updates the status and accessibility of Shizuku-related features based on server connection and permissions.
     */
    private fun updateShizukuUI() {
        val hasPermission = ShizukuManager.isPermissionGranted()
        val isRunning = ShizukuManager.isShizukuRunning()

        isProgrammaticShizukuUpdate = true
        try {
            binding.switchShizukuShowTouches.isChecked = configManager.isShizukuShowTouchesEnabled
            binding.switchShizukuDirectRecording.isChecked = configManager.isShizukuDirectRecordingEnabled
            binding.switchShizukuHideSystemBars.isChecked = configManager.isShizukuHideSystemBarsEnabled
            binding.switchShizukuEnhancedAudio.isChecked = configManager.isShizukuEnhancedAudioEnabled

            if (hasPermission) {
                binding.tvShizukuStatus.text = getString(R.string.SettingsBottomSheet_shizuku_status_granted)

                binding.cardShizukuShowTouches.isEnabled = true
                binding.cardShizukuShowTouches.alpha = 1.0f
                binding.switchShizukuShowTouches.isEnabled = true

                binding.cardShizukuDirectRecording.isEnabled = true
                binding.cardShizukuDirectRecording.alpha = 1.0f
                binding.switchShizukuDirectRecording.isEnabled = true

                binding.cardShizukuHideSystemBars.isEnabled = true
                binding.cardShizukuHideSystemBars.alpha = 1.0f
                binding.switchShizukuHideSystemBars.isEnabled = true

                binding.cardShizukuEnhancedAudio.isEnabled = true
                binding.cardShizukuEnhancedAudio.alpha = 1.0f
                binding.switchShizukuEnhancedAudio.isEnabled = true
            } else {
                if (isRunning) {
                    binding.tvShizukuStatus.text = getString(R.string.SettingsBottomSheet_shizuku_status_waiting)
                } else {
                    binding.tvShizukuStatus.text = getString(R.string.SettingsBottomSheet_shizuku_status_unavailable)
                }

                binding.cardShizukuShowTouches.isEnabled = false
                binding.cardShizukuShowTouches.alpha = 0.5f
                binding.switchShizukuShowTouches.isEnabled = false

                binding.cardShizukuDirectRecording.isEnabled = false
                binding.cardShizukuDirectRecording.alpha = 0.5f
                binding.switchShizukuDirectRecording.isEnabled = false

                binding.cardShizukuHideSystemBars.isEnabled = false
                binding.cardShizukuHideSystemBars.alpha = 0.5f
                binding.switchShizukuHideSystemBars.isEnabled = false

                binding.cardShizukuEnhancedAudio.isEnabled = false
                binding.cardShizukuEnhancedAudio.alpha = 0.5f
                binding.switchShizukuEnhancedAudio.isEnabled = false
            }
        } finally {
            isProgrammaticShizukuUpdate = false
        }
    }

    /*
     * Updates the storage section card label and toggles the reset button visibility.
     */
    private fun updateStorageUI() {
        binding.tvStoragePath.text = configManager.getStorageLocationDisplayPath()
        binding.btnResetStorage.visibility = if (configManager.customStorageUri != null) View.VISIBLE else View.GONE
    }

    /*
     * Adjusts appearance section styling based on dynamic color availability.
     */
    private fun setupAppearanceSection() {
        val isDynamicAvailable = DynamicColors.isDynamicColorAvailable()
        if (!isDynamicAvailable) {
            binding.cardDynamicColors.visibility = View.GONE
            binding.cardThemeMode.shapeAppearanceModel = ShapeAppearanceModel.builder(
                requireContext(),
                com.google.android.material.R.style.ShapeAppearance_Material3_Corner_ExtraLarge,
                0
            ).build()
        }
    }

    /*
     * Attaches click and change listeners to all interactive user interface controls.
     */
    private fun setupListeners() {
        binding.cardMic.setOnClickListener { binding.switchMic.toggle() }
        binding.switchMic.setOnCheckedChangeListener { _, isChecked ->
            configManager.isMicEnabled = isChecked
        }

        binding.cardSystemAudio.setOnClickListener { binding.switchSystemAudio.toggle() }
        binding.switchSystemAudio.setOnCheckedChangeListener { _, isChecked ->
            configManager.isSystemAudioEnabled = isChecked
        }

        binding.cardRecordingOverlay.setOnClickListener { binding.switchRecordingOverlay.toggle() }
        binding.switchRecordingOverlay.setOnCheckedChangeListener { _, isChecked ->
            configManager.isRecordingOverlayEnabled = isChecked
            updateOverlaySubOptionsUI(isChecked)
            notifyOverlayConfigChanged()
        }

        binding.cardOverlayShowPause.setOnClickListener {
            if (configManager.isRecordingOverlayEnabled) binding.switchOverlayShowPause.toggle()
        }
        binding.switchOverlayShowPause.setOnCheckedChangeListener { _, isChecked ->
            configManager.isOverlayShowPauseEnabled = isChecked
            validateRadialStyleAvailability()
            notifyOverlayConfigChanged()
        }

        binding.cardOverlayShowStop.setOnClickListener {
            if (configManager.isRecordingOverlayEnabled) binding.switchOverlayShowStop.toggle()
        }
        binding.switchOverlayShowStop.setOnCheckedChangeListener { _, isChecked ->
            configManager.isOverlayShowStopEnabled = isChecked
            validateRadialStyleAvailability()
            notifyOverlayConfigChanged()
        }

        binding.cardOverlayShowMute.setOnClickListener {
            if (configManager.isRecordingOverlayEnabled) binding.switchOverlayShowMute.toggle()
        }
        binding.switchOverlayShowMute.setOnCheckedChangeListener { _, isChecked ->
            configManager.isOverlayShowMuteEnabled = isChecked
            validateRadialStyleAvailability()
            notifyOverlayConfigChanged()
        }

        binding.cardOverlayShowCamera.setOnClickListener {
            if (configManager.isRecordingOverlayEnabled) binding.switchOverlayShowCamera.toggle()
        }
        binding.switchOverlayShowCamera.setOnCheckedChangeListener { _, isChecked ->
            configManager.isOverlayShowCameraEnabled = isChecked
            validateRadialStyleAvailability()
            notifyOverlayConfigChanged()
        }

        binding.cardOverlayShowDraw.setOnClickListener {
            if (configManager.isRecordingOverlayEnabled) binding.switchOverlayShowDraw.toggle()
        }
        binding.switchOverlayShowDraw.setOnCheckedChangeListener { _, isChecked ->
            configManager.isOverlayShowDrawEnabled = isChecked
            validateRadialStyleAvailability()
            notifyOverlayConfigChanged()
        }

        binding.buttonGroupOverlayStyle.addOnButtonCheckedListener { _: MaterialButtonToggleGroup, checkedId: Int, isChecked: Boolean ->
            if (isChecked) {
                if (checkedId == R.id.btnStyleRadial) {
                    val count = getEnabledOverlaySubOptionsCount()
                    if (count < 3) {
                        binding.buttonGroupOverlayStyle.check(R.id.btnStyleHorizontal)
                        return@addOnButtonCheckedListener
                    }
                    configManager.overlayStyle = ConfigManager.OVERLAY_STYLE_RADIAL
                } else {
                    configManager.overlayStyle = ConfigManager.OVERLAY_STYLE_HORIZONTAL
                }
                notifyOverlayConfigChanged()
            }
        }

        binding.buttonGroupCountdown.addOnButtonCheckedListener { _: MaterialButtonToggleGroup, checkedId: Int, isChecked: Boolean ->
            if (isChecked) {
                configManager.countdownSeconds = when (checkedId) {
                    R.id.btnCountdownOff -> 0
                    R.id.btnCountdown5s -> 5
                    R.id.btnCountdown10s -> 10
                    else -> 3
                }
            }
        }

        binding.buttonGroupQuality.addOnButtonCheckedListener { _: MaterialButtonToggleGroup, checkedId: Int, isChecked: Boolean ->
            if (isChecked) {
                val selectedQuality = when (checkedId) {
                    R.id.btnQualityMax -> ConfigManager.QUALITY_MAX
                    R.id.btnQuality4K -> ConfigManager.QUALITY_4K
                    R.id.btnQuality2K -> ConfigManager.QUALITY_2K
                    R.id.btnQuality1080 -> ConfigManager.QUALITY_1080P
                    R.id.btnQuality720 -> ConfigManager.QUALITY_720P
                    R.id.btnQuality480 -> ConfigManager.QUALITY_480P
                    R.id.btnQuality360 -> ConfigManager.QUALITY_360P
                    R.id.btnQuality240 -> ConfigManager.QUALITY_240P
                    else -> ConfigManager.QUALITY_MAX
                }
                configManager.videoQuality = selectedQuality
                applyQualityPreset(selectedQuality)
            }
        }

        binding.buttonGroupFps.addOnButtonCheckedListener { _: MaterialButtonToggleGroup, checkedId: Int, isChecked: Boolean ->
            if (isChecked) {
                configManager.videoFps = when (checkedId) {
                    R.id.btnFps120 -> 120
                    R.id.btnFps90 -> 90
                    R.id.btnFps60 -> 60
                    R.id.btnFps30 -> 30
                    R.id.btnFps24 -> 24
                    R.id.btnFps15 -> 15
                    else -> 60
                }
                setupDynamicBitrates()
            }
        }

        binding.buttonGroupBitrate.addOnButtonCheckedListener { _: MaterialButtonToggleGroup, checkedId: Int, isChecked: Boolean ->
            if (isChecked) {
                configManager.videoBitrate = when (checkedId) {
                    R.id.btnBitrate24 -> 24_000_000
                    R.id.btnBitrate16 -> 16_000_000
                    R.id.btnBitrate12 -> 12_000_000
                    R.id.btnBitrate8 -> 8_000_000
                    R.id.btnBitrate5 -> 5_000_000
                    R.id.btnBitrate2 -> 2_000_000
                    R.id.btnBitrate1 -> 1_000_000
                    else -> 0
                }
            }
        }

        binding.buttonGroupEncoder.addOnButtonCheckedListener { _: MaterialButtonToggleGroup, checkedId: Int, isChecked: Boolean ->
            if (isChecked) {
                configManager.videoEncoder = when (checkedId) {
                    R.id.btnEncoderHevc -> ConfigManager.ENCODER_HEVC
                    else -> ConfigManager.ENCODER_H264
                }
            }
        }

        binding.buttonGroupOrientation.addOnButtonCheckedListener { _: MaterialButtonToggleGroup, checkedId: Int, isChecked: Boolean ->
            if (isChecked) {
                configManager.videoOrientation = when (checkedId) {
                    R.id.btnOrientationPortrait -> ConfigManager.ORIENTATION_PORTRAIT
                    R.id.btnOrientationLandscape -> ConfigManager.ORIENTATION_LANDSCAPE
                    else -> ConfigManager.ORIENTATION_AUTO
                }
            }
        }

        binding.cardStorageLocation.setOnClickListener {
            storagePickerLauncher.launch(null)
        }
        binding.btnChangeStorage.setOnClickListener {
            storagePickerLauncher.launch(null)
        }
        binding.btnResetStorage.setOnClickListener {
            configManager.customStorageUri = null
            updateStorageUI()
        }

        binding.cardDynamicColors.setOnClickListener { binding.switchDynamicColors.toggle() }
        binding.switchDynamicColors.setOnCheckedChangeListener { _, isChecked ->
            if (configManager.isDynamicColorsEnabled != isChecked) {
                configManager.isDynamicColorsEnabled = isChecked
                requireActivity().recreate()
            }
        }

        binding.buttonGroupTheme.addOnButtonCheckedListener { _: MaterialButtonToggleGroup, checkedId: Int, isChecked: Boolean ->
            if (isChecked) {
                val newMode = when (checkedId) {
                    R.id.btnThemeLight -> ConfigManager.THEME_LIGHT
                    R.id.btnThemeDark -> ConfigManager.THEME_DARK
                    else -> ConfigManager.THEME_SYSTEM
                }
                configManager.themeMode = newMode
                AppCompatDelegate.setDefaultNightMode(configManager.getThemeModeValue())
            }
        }

        binding.cardLanguage.setOnClickListener {
            showLanguageSelectionDialog()
        }

        binding.cardPauseOnScreenOff.setOnClickListener { binding.switchPauseOnScreenOff.toggle() }
        binding.switchPauseOnScreenOff.setOnCheckedChangeListener { _, isChecked ->
            configManager.isPauseOnScreenOff = isChecked
            updatePauseOnScreenOffUI(isChecked)
        }

        binding.cardShizukuStatus.setOnClickListener {
            if (!ShizukuManager.isPermissionGranted()) {
                ShizukuManager.requestPermissionExplicit()
            }
        }

        binding.cardShizukuShowTouches.setOnClickListener {
            if (ShizukuManager.isPermissionGranted()) {
                binding.switchShizukuShowTouches.toggle()
            }
        }
        binding.switchShizukuShowTouches.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticShizukuUpdate) return@setOnCheckedChangeListener
            configManager.isShizukuShowTouchesEnabled = isChecked
            if (ScreenRecordService.isRecording && ShizukuManager.isPermissionGranted()) {
                ShizukuManager.setShowTouches(isChecked)
            }
        }

        binding.cardShizukuDirectRecording.setOnClickListener {
            if (ShizukuManager.isPermissionGranted()) {
                binding.switchShizukuDirectRecording.toggle()
            }
        }
        binding.switchShizukuDirectRecording.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticShizukuUpdate) return@setOnCheckedChangeListener
            configManager.isShizukuDirectRecordingEnabled = isChecked
            if (isChecked && ShizukuManager.isPermissionGranted()) {
                ShizukuManager.grantProjectMediaPermission(requireContext())
            }
        }

        binding.cardShizukuHideSystemBars.setOnClickListener {
            if (ShizukuManager.isPermissionGranted()) {
                binding.switchShizukuHideSystemBars.toggle()
            }
        }
        binding.switchShizukuHideSystemBars.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticShizukuUpdate) return@setOnCheckedChangeListener
            configManager.isShizukuHideSystemBarsEnabled = isChecked
            if (ScreenRecordService.isRecording && ShizukuManager.isPermissionGranted()) {
                ShizukuManager.setImmersiveMode(isChecked)
            }
        }

        binding.cardShizukuEnhancedAudio.setOnClickListener {
            if (ShizukuManager.isPermissionGranted()) {
                binding.switchShizukuEnhancedAudio.toggle()
            }
        }
        binding.switchShizukuEnhancedAudio.setOnCheckedChangeListener { _, isChecked ->
            if (isProgrammaticShizukuUpdate) return@setOnCheckedChangeListener
            configManager.isShizukuEnhancedAudioEnabled = isChecked
            if (isChecked && ShizukuManager.isPermissionGranted()) {
                ShizukuManager.grantEnhancedAudioPermission(requireContext())
            }
        }

        binding.btnAbout.setOnClickListener {
            startActivity(Intent(requireContext(), AboutActivity::class.java))
            dismiss()
        }
    }

    /*
     * Filters and displays supported quality buttons according to device screen resolution.
     */
    private fun setupDynamicQualities() {
        binding.btnQualityMax.text = configManager.getMaxQualityLabel()
        val supported = configManager.getAvailableQualityOptions()
        binding.btnQuality4K.visibility = if (supported.contains(ConfigManager.QUALITY_4K)) View.VISIBLE else View.GONE
        binding.btnQuality2K.visibility = if (supported.contains(ConfigManager.QUALITY_2K)) View.VISIBLE else View.GONE
        binding.btnQuality1080.visibility = if (supported.contains(ConfigManager.QUALITY_1080P)) View.VISIBLE else View.GONE
        binding.btnQuality720.visibility = if (supported.contains(ConfigManager.QUALITY_720P)) View.VISIBLE else View.GONE
        binding.btnQuality480.visibility = if (supported.contains(ConfigManager.QUALITY_480P)) View.VISIBLE else View.GONE
        binding.btnQuality360.visibility = if (supported.contains(ConfigManager.QUALITY_360P)) View.VISIBLE else View.GONE
        binding.btnQuality240.visibility = if (supported.contains(ConfigManager.QUALITY_240P)) View.VISIBLE else View.GONE
    }

    /*
     * Filters and displays available FPS buttons dynamically based on Shizuku privileges and display refresh rate.
     */
    private fun setupDynamicFps() {
        val isShizuku = ShizukuManager.isPermissionGranted()
        val options = configManager.getAvailableFpsOptions(isShizuku)

        binding.btnFps120.visibility = if (options.contains(120)) View.VISIBLE else View.GONE
        binding.btnFps90.visibility = if (options.contains(90)) View.VISIBLE else View.GONE
        binding.btnFps60.visibility = if (options.contains(60)) View.VISIBLE else View.GONE
        binding.btnFps30.visibility = if (options.contains(30)) View.VISIBLE else View.GONE
        binding.btnFps24.visibility = if (options.contains(24)) View.VISIBLE else View.GONE
        binding.btnFps15.visibility = if (options.contains(15)) View.VISIBLE else View.GONE

        if (!options.contains(configManager.videoFps)) {
            configManager.videoFps = 60
            binding.buttonGroupFps.check(R.id.btnFps60)
        }
    }

    /*
     * Automatically selects recommended default FPS and Bitrate based on the selected resolution.
     */
    private fun applyQualityPreset(quality: String) {
        val isShizuku = ShizukuManager.isPermissionGranted()
        val options = configManager.getAvailableFpsOptions(isShizuku)
        val targetFpsButton = when (quality) {
            ConfigManager.QUALITY_MAX, ConfigManager.QUALITY_4K, ConfigManager.QUALITY_2K, ConfigManager.QUALITY_1080P -> {
                if (options.contains(120)) R.id.btnFps120 else if (options.contains(90)) R.id.btnFps90 else R.id.btnFps60
            }
            ConfigManager.QUALITY_720P -> R.id.btnFps60
            ConfigManager.QUALITY_480P -> R.id.btnFps30
            ConfigManager.QUALITY_360P -> R.id.btnFps24
            ConfigManager.QUALITY_240P -> R.id.btnFps15
            else -> R.id.btnFps60
        }

        binding.buttonGroupFps.check(targetFpsButton)
        setupDynamicBitrates()

        configManager.videoBitrate = 0
        binding.buttonGroupBitrate.check(R.id.btnBitrateAuto)
    }

    /*
     * Configures bitrate buttons dynamically based on target resolution auto bitrate.
     */
    private fun setupDynamicBitrates() {
        val autoBps = configManager.getAutoVideoBitrate()
        val autoMbps = (autoBps / 1_000_000).coerceAtLeast(1)

        binding.btnBitrateAuto.text = getString(R.string.SettingsBottomSheet_bitrate_auto_val, autoMbps)

        binding.btnBitrate24.visibility = if (24_000_000 < autoBps) View.VISIBLE else View.GONE
        binding.btnBitrate16.visibility = if (16_000_000 < autoBps) View.VISIBLE else View.GONE
        binding.btnBitrate12.visibility = if (12_000_000 < autoBps) View.VISIBLE else View.GONE
        binding.btnBitrate8.visibility = if (8_000_000 < autoBps) View.VISIBLE else View.GONE
        binding.btnBitrate5.visibility = if (5_000_000 < autoBps) View.VISIBLE else View.GONE
        binding.btnBitrate2.visibility = if (2_000_000 < autoBps) View.VISIBLE else View.GONE
        binding.btnBitrate1.visibility = if (1_000_000 < autoBps) View.VISIBLE else View.GONE

        if (configManager.videoBitrate >= autoBps) {
            configManager.videoBitrate = 0
            binding.buttonGroupBitrate.check(R.id.btnBitrateAuto)
        }
    }

    /*
     * Updates the summary text for the Pause on Screen Off setting according to current enabled state.
     */
    private fun updatePauseOnScreenOffUI(isEnabled: Boolean) {
        val summaryRes = if (isEnabled) {
            R.string.SettingsBottomSheet_pause_on_screen_off_summary_enabled
        } else {
            R.string.SettingsBottomSheet_pause_on_screen_off_summary_disabled
        }
        binding.tvPauseOnScreenOffSummary.setText(summaryRes)
    }

    /*
     * Calculates the total number of currently active floating overlay button switches.
     */
    private fun getEnabledOverlaySubOptionsCount(): Int {
        var count = 0
        if (binding.switchOverlayShowPause.isChecked) count++
        if (binding.switchOverlayShowStop.isChecked) count++
        if (binding.switchOverlayShowMute.isChecked) count++
        if (binding.switchOverlayShowCamera.isChecked) count++
        if (binding.switchOverlayShowDraw.isChecked) count++
        return count
    }

    /*
     * Validates if radial style has sufficient buttons enabled and manages fallback.
     */
    private fun validateRadialStyleAvailability() {
        val count = getEnabledOverlaySubOptionsCount()
        val isRadialAllowed = count >= 3
        val isOverlayOn = configManager.isRecordingOverlayEnabled

        binding.btnStyleRadial.isEnabled = isRadialAllowed && isOverlayOn
        binding.btnStyleRadial.alpha = if (isRadialAllowed && isOverlayOn) 1.0f else 0.4f

        if (!isRadialAllowed) {
            binding.tvOverlayStyleSummary.setText(R.string.SettingsBottomSheet_overlay_radial_min_hint)
            if (configManager.overlayStyle == ConfigManager.OVERLAY_STYLE_RADIAL) {
                configManager.overlayStyle = ConfigManager.OVERLAY_STYLE_HORIZONTAL
                binding.buttonGroupOverlayStyle.check(R.id.btnStyleHorizontal)
            }
        } else {
            binding.tvOverlayStyleSummary.setText(R.string.SettingsBottomSheet_overlay_style_summary)
        }
    }

    /*
     * Synchronizes the interactive enabled state and visual alpha of floating bar sub-options.
     */
    private fun updateOverlaySubOptionsUI(isEnabled: Boolean) {
        val alphaVal = if (isEnabled) 1.0f else 0.4f

        binding.cardOverlayStyle.isEnabled = isEnabled
        binding.cardOverlayStyle.alpha = alphaVal
        binding.buttonGroupOverlayStyle.isEnabled = isEnabled
        binding.btnStyleHorizontal.isEnabled = isEnabled
        binding.btnStyleHorizontal.alpha = alphaVal

        binding.cardOverlayShowPause.isEnabled = isEnabled
        binding.cardOverlayShowPause.alpha = alphaVal
        binding.switchOverlayShowPause.isEnabled = isEnabled

        binding.cardOverlayShowStop.isEnabled = isEnabled
        binding.cardOverlayShowStop.alpha = alphaVal
        binding.switchOverlayShowStop.isEnabled = isEnabled

        binding.cardOverlayShowMute.isEnabled = isEnabled
        binding.cardOverlayShowMute.alpha = alphaVal
        binding.switchOverlayShowMute.isEnabled = isEnabled

        binding.cardOverlayShowCamera.isEnabled = isEnabled
        binding.cardOverlayShowCamera.alpha = alphaVal
        binding.switchOverlayShowCamera.isEnabled = isEnabled

        binding.cardOverlayShowDraw.isEnabled = isEnabled
        binding.cardOverlayShowDraw.alpha = alphaVal
        binding.switchOverlayShowDraw.isEnabled = isEnabled

        validateRadialStyleAvailability()
    }

    /*
     * Broadcasts overlay settings modification to ensure live active overlays update dynamically.
     */
    private fun notifyOverlayConfigChanged() {
        val ctx = context ?: return
        if (ScreenRecordService.isRecording) {
            if (configManager.isRecordingOverlayEnabled) {
                ctx.startService(Intent(ctx, RecordingOverlayService::class.java))
            } else {
                ctx.stopService(Intent(ctx, RecordingOverlayService::class.java))
            }
        }
        ctx.sendBroadcast(Intent(RecordingOverlayService.ACTION_OVERLAY_CONFIG_CHANGED))
    }

    /*
     * Updates the language summary subtitle based on currently configured or auto-detected locale.
     */
    private fun updateLanguageUI() {
        val currentLang = configManager.appLanguage
        binding.tvLanguageSummary.text = LocaleHelper.getCurrentLanguageDisplayName(requireContext(), currentLang)
    }

    /*
     * Displays a dialog with available languages and system language mode to switch application language.
     */
    private fun showLanguageSelectionDialog() {
        val ctx = context ?: return
        val availableLanguages = LocaleHelper.getSupportedLanguages(ctx)
        val systemTitle = getString(R.string.SettingsBottomSheet_language_system)

        val displayNames = ArrayList<String>()
        val codeList = ArrayList<String>()

        displayNames.add(systemTitle)
        codeList.add("auto")

        for (lang in availableLanguages) {
            displayNames.add(lang.displayName)
            codeList.add(lang.code)
        }

        val currentCode = configManager.appLanguage
        val initialSelection = codeList.indexOf(currentCode).let { if (it >= 0) it else 0 }
        var selectedIndex = initialSelection

        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.SettingsBottomSheet_language_dialog_title)
            .setSingleChoiceItems(displayNames.toTypedArray<CharSequence>(), initialSelection) { _, which ->
                selectedIndex = which
            }
            .setPositiveButton(R.string.SettingsBottomSheet_language_btn_ok) { _, _ ->
                val newLang = codeList[selectedIndex]
                if (newLang != configManager.appLanguage) {
                    configManager.appLanguage = newLang
                    LocaleHelper.applyLanguage(newLang)
                    dismissAllowingStateLoss()
                }
            }
            .setNegativeButton(R.string.SettingsBottomSheet_language_btn_cancel, null)
            .show()
    }

    /*
     * Clears view bindings and unregisters Shizuku observers when the view is destroyed to avoid leaks.
     */
    override fun onDestroyView() {
        ShizukuManager.removePermissionListener(shizukuPermissionListener)
        ShizukuManager.removeBinderListener(shizukuBinderListener)
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /*
         * Creates a new instance of the settings bottom sheet dialog.
         */
        fun newInstance() = SettingsBottomSheet()
    }
}
