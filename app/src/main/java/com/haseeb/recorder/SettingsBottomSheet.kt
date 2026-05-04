package com.haseeb.recorder

import android.content.Intent
import android.os.Bundle
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.DynamicColors
import com.google.android.material.shape.ShapeAppearanceModel
import com.haseeb.recorder.databinding.LayoutSettingsSheetBinding

/*
 * Manages the settings bottom sheet UI.
 * Handles audio, video quality, appearance, and advanced settings.
 */
class SettingsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: LayoutSettingsSheetBinding? = null
    private val binding get() = _binding!!

    private lateinit var configManager: ConfigManager

    /*
     * Inflates the layout and initializes the configuration manager.
     */
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = LayoutSettingsSheetBinding.inflate(inflater, container, false)
        configManager = ConfigManager(requireContext())
        return binding.root
    }

    /*
     * Sets up all UI sections, applies current settings, and filters options by device capability.
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            requireActivity().window.isNavigationBarContrastEnforced = false
        }

        loadCurrentSettings()
        setupListeners()
        setupDynamicQualities()
        setupAppearanceSection()
    }

    /*
     * Restores all saved preferences and checks the corresponding UI elements.
     */
    private fun loadCurrentSettings() {
        binding.switchMic.isChecked = configManager.isMicEnabled
        binding.switchSystemAudio.isChecked = configManager.isSystemAudioEnabled
        binding.switchShowTouches.isChecked = configManager.showTouches
        binding.switchDynamicColors.isChecked = configManager.isDynamicColorsEnabled

        val qualityButtonId = when (configManager.videoQuality) {
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
        binding.buttonGroup.check(qualityButtonId)

        val themeButtonId = when (configManager.themeMode) {
            ConfigManager.THEME_LIGHT -> R.id.btnThemeLight
            ConfigManager.THEME_DARK -> R.id.btnThemeDark
            else -> R.id.btnThemeSystem
        }
        binding.buttonGroupTheme.check(themeButtonId)
    }

    /*
     * Checks if dynamic colors are supported on this device.
     * If not supported, hides the dynamic colors card and applies the Single
     * shape to the theme card so rounded corners remain correct.
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
     * Attaches click and change listeners to all interactive components.
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

        binding.cardShowTouches.setOnClickListener { binding.switchShowTouches.toggle() }
        binding.switchShowTouches.setOnCheckedChangeListener { _, isChecked ->
            configManager.showTouches = isChecked
        }

        binding.buttonGroup.addOnButtonCheckedListener { _: MaterialButtonToggleGroup, checkedId: Int, isChecked: Boolean ->
            if (isChecked) {
                configManager.videoQuality = when (checkedId) {
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
            }
        }

        /*
         * Saves the dynamic colors preference and recreates the activity
         * so the new color scheme takes effect immediately.
         */
        binding.cardDynamicColors.setOnClickListener { binding.switchDynamicColors.toggle() }
        binding.switchDynamicColors.setOnCheckedChangeListener { _, isChecked ->
        if (configManager.isDynamicColorsEnabled != isChecked) {
        configManager.isDynamicColorsEnabled = isChecked
        
        requireActivity().recreate()
        }
    }

        /*
         * Saves the theme mode and applies it immediately via AppCompatDelegate.
         */
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

        binding.btnAbout.setOnClickListener {
            startActivity(Intent(requireContext(), AboutActivity::class.java))
            dismiss()
        }
    }

    /*
     * Hides quality buttons that exceed the device's maximum screen resolution.
     * Updates the Maximum button label to show the actual device resolution.
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
     * Clears view bindings to prevent memory leaks.
     */
    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        fun newInstance() = SettingsBottomSheet()
    }
}