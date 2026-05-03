package com.haseeb.recorder

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/**
 * Quick Settings Tile service that respects synchronized user preferences.
 * Uses ConfigManager to ensure correct audio settings are applied when starting from the tile.
 */
class ScreenRecordTileService : TileService() {

    private lateinit var configManager: ConfigManager

    /**
     * Initializes ConfigManager when the tile is ready for interaction.
     */
    override fun onCreate() {
        super.onCreate()
        configManager = ConfigManager(this)
    }

    /**
     * Updates the tile appearance when the Quick Settings panel is expanded.
     */
    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    /**
     * Handles tile clicks and passes saved preferences to the recording session.
     */
    override fun onClick() {
        super.onClick()
        try {
            if (ScreenRecordService.isRecording) {
                val stopIntent = Intent(this, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_STOP
                }
                startService(stopIntent)
                setTileState(false)
            } else {
                launchPermissionActivity()
            }
        } catch (e: Exception) {
            Log.e("TileService", "Error handling click: ${e.message}")
            updateTileState()
        }
    }

    /**
     * Launches the permission activity while passing the latest synced configurations.
     */
    private fun launchPermissionActivity() {
        val intent = Intent(this, MediaProjectionPermissionActivity::class.java).apply {
            // Ensure tile uses the same synced settings as MainActivity
            putExtra("RECORD_MIC", configManager.isMicEnabled)
            putExtra("RECORD_SYSTEM_AUDIO", configManager.isSystemAudioEnabled)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                val pendingIntent = PendingIntent.getActivity(
                    this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                startActivityAndCollapse(pendingIntent)
            } else {
                @Suppress("DEPRECATION")
                startActivityAndCollapse(intent)
            }
        } catch (e: Exception) {
            Log.e("TileService", "Failed to collapse and start: ${e.message}")
        }
    }

    /**
     * Updates the tile visual state based on active recording.
     */
    private fun updateTileState() {
        setTileState(ScreenRecordService.isRecording)
    }

    /**
     * Modifies the Tile object state, label, and icon visibility.
     */
    private fun setTileState(active: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (active) 
            getString(R.string.ScreenRecordTileService_tile_stop) 
        else 
            getString(R.string.ScreenRecordTileService_tile_start)
            
        tile.updateTile()
    }
}
