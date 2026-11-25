package com.ecut.campus;

import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/**
 * 下拉快捷设置磁贴「同步课表」：
 * 显示上次同步时间，点击直接拉起课表静默同步。
 */
public final class SyncTimetableTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile == null) return;
        try {
            tile.setLabel("同步课表");
            String last = Prefs.lastSyncTime(this);
            if (Build.VERSION.SDK_INT >= 29) {
                tile.setSubtitle(last == null || last.isEmpty() ? "点击同步教务课表" : "上次 " + last);
            }
            tile.setState(Tile.STATE_INACTIVE);
            tile.updateTile();
        } catch (Throwable ignored) {
        }
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onClick() {
        super.onClick();
        Intent i = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_ACTION, "sync")
                .putExtra(MainActivity.EXTRA_VALUE, "timetable");
        startActivityAndCollapse(i);
    }
}
