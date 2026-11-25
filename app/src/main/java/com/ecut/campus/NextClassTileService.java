package com.ecut.campus;

import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import com.ecut.campus.data.NextClassText;

/**
 * 下拉快捷设置磁贴「下一节课」：
 * 直接在通知栏磁贴展示当前/下一节课名与时间地点，点击直达课表页。
 */
public final class NextClassTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile == null) return;
        try {
            long now = System.currentTimeMillis();
            NextClassText.Notice n = NextClassNotice.notice(this, now);
            if (n.kind == NextClassText.NONE) {
                tile.setLabel("下一节课");
                if (Build.VERSION.SDK_INT >= 29) tile.setSubtitle("今日与明日无课");
                tile.setState(Tile.STATE_INACTIVE);
            } else {
                String label = n.courseName.isEmpty() ? n.title : n.courseName;
                tile.setLabel(label);
                if (Build.VERSION.SDK_INT >= 29) {
                    String cd = NextClassText.countdownLabel(n, ClassPlans.nowMinutes(now));
                    String sub = cd.isEmpty() ? n.text : cd + (n.location.isEmpty() ? "" : " · " + n.location);
                    tile.setSubtitle(sub);
                }
                tile.setState(n.kind == NextClassText.CURRENT || n.kind == NextClassText.NEXT
                        ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            }
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
                .putExtra(MainActivity.EXTRA_ACTION, "open")
                .putExtra(MainActivity.EXTRA_VALUE, "timetable");
        startActivityAndCollapse(i);
    }
}
