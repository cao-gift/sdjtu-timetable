package com.ecut.campus;

import android.content.Intent;
import android.os.Build;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 下拉快捷设置磁贴「今日空教室」：
 * 显示上次同步的空闲教室数，点击直达交院查空教室页面。
 */
public final class FreeRoomTileService extends TileService {

    @Override
    public void onStartListening() {
        super.onStartListening();
        Tile tile = getQsTile();
        if (tile == null) return;
        try {
            tile.setLabel("今日空教室");
            int freeCount = countCachedFreeRooms();
            if (Build.VERSION.SDK_INT >= 29) {
                tile.setSubtitle(freeCount > 0 ? "缓存 " + freeCount + " 间空闲" : "点击查询空教室");
            }
            tile.setState(freeCount > 0 ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
            tile.updateTile();
        } catch (Throwable ignored) {
        }
    }

    private int countCachedFreeRooms() {
        JSONObject snap = SdjtuStudyStore.load(this, SdjtuAcademicPages.Feature.ROOMS);
        if (snap == null) return 0;
        JSONObject data = snap.optJSONObject("data");
        JSONArray rooms = data == null ? null : data.optJSONArray("rooms");
        if (rooms == null) return 0;
        int n = 0;
        for (int i = 0; i < rooms.length(); i++) {
            JSONObject r = rooms.optJSONObject(i);
            if (r != null && "free".equals(r.optString("state"))) n++;
        }
        return n;
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onClick() {
        super.onClick();
        Intent i = new Intent(this, SdjtuStudyActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(WebShellActivity.EXTRA_TARGET_KEY, SdjtuAcademicPages.Feature.ROOMS.target);
        startActivityAndCollapse(i);
    }
}
