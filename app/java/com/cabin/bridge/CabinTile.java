package com.cabin.bridge;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile: tap to turn Cabin on or off. Long-press opens the app. */
public class CabinTile extends TileService {
    @Override
    public void onStartListening() {
        render(null);
    }

    @Override
    public void onClick() {
        String next = Prefs.isOn(this) ? Prefs.MODE_OFF : Prefs.lastOn(this);
        if (RootQueue.set(this, "MODE", next)) render(next);
    }

    private void render(String pendingMode) {
        Tile tile = getQsTile();
        if (tile == null) return;
        String mode = pendingMode != null ? pendingMode : Prefs.mode(this);
        boolean on = !Prefs.MODE_OFF.equals(mode);
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.setLabel(getString(R.string.tile_label));
        String sub = pendingMode != null ? "Switching…"
                : Prefs.MODE_SAP.equals(mode) ? "SIM access" : on ? "Hands-free" : "Off";
        if (android.os.Build.VERSION.SDK_INT >= 29) tile.setSubtitle(sub);
        tile.updateTile();
    }
}
