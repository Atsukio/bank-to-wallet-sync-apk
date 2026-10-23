package app.banksync

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Quick Settings tile: pause or resume capture without opening the app. */
class PauseTileService : TileService() {
    override fun onStartListening() = update()

    override fun onClick() {
        val settings = Settings(this)
        settings.paused = !settings.paused
        update()
    }

    private fun update() {
        val tile = qsTile ?: return
        val paused = Settings(this).paused
        tile.state = if (paused) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.label = "Bank to Wallet"
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (paused) "Paused" else "Capturing"
        tile.updateTile()
    }
}
