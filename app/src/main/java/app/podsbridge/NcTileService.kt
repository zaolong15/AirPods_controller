package app.podsbridge

import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class NcTileService : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        PodsController.cycleNoise()
        Handler(Looper.getMainLooper()).postDelayed({ refresh() }, 400)
    }

    private fun refresh() {
        val t = qsTile ?: return
        val ui = PodsStore.state.value
        t.label = "AirPods"
        t.subtitle = if (ui.connected) (ui.noise?.label ?: "已连接") else "未连接"
        t.state = if (ui.connected) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.updateTile()
    }
}
