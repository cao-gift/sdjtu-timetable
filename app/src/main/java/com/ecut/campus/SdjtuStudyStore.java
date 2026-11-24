package com.ecut.campus;

import android.content.Context;
import android.util.AtomicFile;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** 上一次成功读取的交院教务结果；一个功能一个快照，包含其实际查询条件。 */
final class SdjtuStudyStore {
    private SdjtuStudyStore() {}

    private static AtomicFile file(Context ctx, SdjtuAcademicPages.Feature feature) {
        return new AtomicFile(new File(ctx.getNoBackupFilesDir(), feature.target + ".json"));
    }

    static boolean valid(SdjtuAcademicPages.Feature feature, JSONObject data) {
        if (feature == SdjtuAcademicPages.Feature.PORTAL || data == null) return false;
        if (feature == SdjtuAcademicPages.Feature.EXAMS) return SdjtuExamSchedule.valid(data);
        boolean rooms = feature == SdjtuAcademicPages.Feature.ROOMS;
        JSONArray rows = data.optJSONArray(rooms ? "rooms" : "courses");
        if (rows == null) return false;
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null || !(row.opt("name") instanceof String)
                    || row.optString("name").trim().isEmpty()) return false;
            if (rooms) {
                String state = row.optString("state");
                if (!"free".equals(state) && !"busy".equals(state) && !"unknown".equals(state)) return false;
            }
        }
        return true;
    }

    static synchronized JSONObject load(Context ctx, SdjtuAcademicPages.Feature feature) {
        if (feature == SdjtuAcademicPages.Feature.PORTAL) return null;
        try {
            JSONObject snapshot = new JSONObject(new String(file(ctx, feature).readFully(), StandardCharsets.UTF_8));
            if (snapshot.optLong("syncedAt") > 0 && valid(feature, snapshot.optJSONObject("data"))) return snapshot;
        } catch (FileNotFoundException ignored) {
            // 首次使用。
        } catch (IOException | JSONException e) {
            Log.w("SdjtuStudy", "无法读取本地同步结果");
        }
        return null;
    }

    static synchronized boolean save(Context ctx, SdjtuAcademicPages.Feature feature, JSONObject data, long syncedAt) {
        if (syncedAt <= 0 || !valid(feature, data)) return false;
        AtomicFile dst = file(ctx, feature);
        FileOutputStream out = null;
        try {
            JSONObject snapshot = new JSONObject().put("syncedAt", syncedAt).put("data", data);
            byte[] bytes = snapshot.toString().getBytes(StandardCharsets.UTF_8);
            out = dst.startWrite();
            out.write(bytes);
            dst.finishWrite(out);
            return true;
        } catch (IOException | JSONException e) {
            if (out != null) dst.failWrite(out);
            Log.w("SdjtuStudy", "无法保存同步结果");
            return false;
        }
    }

    static synchronized boolean hasAnySnapshot(Context ctx) {
        return file(ctx, SdjtuAcademicPages.Feature.ROOMS).getBaseFile().exists()
                || file(ctx, SdjtuAcademicPages.Feature.PLAN).getBaseFile().exists()
                || file(ctx, SdjtuAcademicPages.Feature.EXAMS).getBaseFile().exists();
    }

    static synchronized void clear(Context ctx) {
        SdjtuStudyRoute.clear(ctx);
        file(ctx, SdjtuAcademicPages.Feature.ROOMS).delete();
        file(ctx, SdjtuAcademicPages.Feature.PLAN).delete();
        file(ctx, SdjtuAcademicPages.Feature.EXAMS).delete();
    }
}
