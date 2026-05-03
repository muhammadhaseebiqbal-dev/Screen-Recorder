package com.haseeb.recorder

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButtonToggleGroup
import com.haseeb.recorder.databinding.LayoutSettingsSheetBinding

/*
 * Manages the settings bottom sheet UI.
 * Dynamically hides unsupported resolutions and handles user preferences.
 */
class SettingsBottomSheet : BottomSheetDialogFragment() {

    private var _binding: LayoutSettingsSheetBinding? = null
    private val binding get() = _binding!!

    private lateinit var configManager: ConfigManager

    /*
     * Inflates the layout and initializes the configuration manager.
     */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = LayoutSettingsSheetBinding.inflate(inflater, container, false)
        configManager = ConfigManager(requireContext())
        return binding.root
    }

    /*
     * Sets up UI logic, applies current settings, and filters available options dynamically.
     */
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        loadCurrentSettings()
        setupListeners()
        setupDynamicQualities()
    }

    /*
     * Restores the saved user preferences and checks the corresponding UI elements.
     */
    private fun loadCurrentSettings() {
        binding.switchMic.isChecked = configManager.isMicEnabled
        binding.switchSystemAudio.isChecked = configManager.isSystemAudioEnabled
        binding.switchShowTouches.isChecked = configManager.showTouches

        val buttonIdToCheck = when (configManager.videoQuality) {
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

        binding.buttonGroup.check(buttonIdToCheck)
    }

    /*
     * Attaches click listeners to all interactive components in the settings layout.
     */
    private fun setupListeners() {
        binding.cardMic.setOnClickListener {
            binding.switchMic.toggle()
        }
        binding.switchMic.setOnCheckedChangeListener { _, isChecked ->
            configManager.isMicEnabled = isChecked
        }

        binding.cardSystemAudio.setOnClickListener {
            binding.switchSystemAudio.toggle()
        }
        binding.switchSystemAudio.setOnCheckedChangeListener { _, isChecked ->
            configManager.isSystemAudioEnabled = isChecked
        }

        binding.cardShowTouches.setOnClickListener {
            binding.switchShowTouches.toggle()
        }
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

        binding.btnAbout.setOnClickListener {
            startActivity(Intent(requireContext(), AboutActivity::class.java))
            dismiss()
        }
    }

    /*
     * Hides quality buttons that exceed the device's maximum screen resolution.
     * Updates the text of the Maximum button to reflect the exact resolution.
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
