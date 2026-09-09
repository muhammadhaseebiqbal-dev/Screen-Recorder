package com.haseeb.recorder.recording

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log
import com.haseeb.recorder.R
import com.haseeb.recorder.data.ConfigManager
import com.haseeb.recorder.ui.activity.MediaProjectionPermissionActivity

/*
 * Quick Settings Tile service managing instant screen recording from the system panel.
 */
class ScreenRecordTileService : TileService() {

    private lateinit var configManager: ConfigManager
    private var isReceiverRegistered = false

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            updateTileState()
        }
    }

    /*
     * Initializes configuration manager when tile is created.
     */
    override fun onCreate() {
        super.onCreate()
        configManager = ConfigManager(this)
    }

    /*
     * Registers state change broadcasts and refreshes tile appearance upon opening Quick Settings.
     */
    override fun onStartListening() {
        super.onStartListening()
        if (!isReceiverRegistered) {
            val filter = IntentFilter(ScreenRecordService.ACTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(stateReceiver, filter)
            }
            isReceiverRegistered = true
        }
        updateTileState()
    }

    /*
     * Unregisters broadcast receivers safely when Quick Settings panel collapses.
     */
    override fun onStopListening() {
        super.onStopListening()
        if (isReceiverRegistered) {
            try {
                unregisterReceiver(stateReceiver)
            } catch (_: Exception) {}
            isReceiverRegistered = false
        }
    }

    /*
     * Toggles recording state or launches the permission activity on tile click.
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

    /*
     * Launches the permission activity passing current user audio configurations.
     */
    private fun launchPermissionActivity() {
        val intent = Intent(this, MediaProjectionPermissionActivity::class.java).apply {
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

    /*
     * Synchronizes current recording state with the tile presentation.
     */
    private fun updateTileState() {
        setTileState(ScreenRecordService.isRecording)
    }

    /*
     * Updates Quick Settings tile state, icon, label, and subtitle.
     */
    private fun setTileState(active: Boolean) {
        val tile = qsTile ?: return
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = if (active) 
            getString(R.string.ScreenRecordTileService_tile_stop) 
        else 
            getString(R.string.ScreenRecordTileService_tile_start)
            
        tile.icon = Icon.createWithResource(
            this,
            if (active) R.drawable.ic_stop else R.drawable.ic_screen_record
        )

        tile.updateTile()
    }
}
