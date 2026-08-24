package com.veyarc.feature.recorder

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** User-owned, non-touch start/stop control for a global recording session. */
class RecordingQuickSettingsTileService : TileService() {
    override fun onStartListening() {
        super.onStartListening()
        refreshTile()
    }

    override fun onClick() {
        super.onClick()
        val controller = CoordinateRecordingRuntime.controller(applicationContext)
        if (controller.isSessionActive()) {
            controller.stop()
        } else if (!controller.isSessionStopping()) {
            controller.start()
        }
        refreshTile()
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val controller = CoordinateRecordingRuntime.controller(applicationContext)
        val active = controller.isSessionActive()
        val stopping = controller.isSessionStopping()
        tile.state = if (active) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = when {
            active -> "Stop Veyarc recording"
            stopping -> "Stopping Veyarc recording"
            else -> "Start Veyarc recording"
        }
        tile.contentDescription = tile.label
        tile.updateTile()
    }

    companion object {
        fun requestRefresh(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                TileService.requestListeningState(
                    context,
                    ComponentName(context, RecordingQuickSettingsTileService::class.java),
                )
            }
        }
    }
}
