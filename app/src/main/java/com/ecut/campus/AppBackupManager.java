package com.ecut.campus;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.ecut.campus.data.AppBackup;
import com.ecut.campus.data.CourseTodo;
import com.ecut.campus.data.SemesterArchive;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * 全量本地数据打包备份与一键恢复：
 * 打包课表归档、作息、外观/显示偏好、地点隐藏词组、同学分享码、作业待办与小玩意偏好；
 * 不导出任何门户账号密码或 Cookie。
 */
final class AppBackupManager {

    static final int REQ_RESTORE = 2002;
    private static final String ARCHIVE_FILE = "timetable_semesters.json";
    private static final String ARCHIVE_TMP = "timetable_semesters.json.tmp";

    private AppBackupManager() {}

    static void exportBackup(Activity a) {
        try {
            JSONObject archive = readJsonObject(new File(a.getFilesDir(), ARCHIVE_FILE));
            JSONObject schedule = ScheduleSettingsStore.load(a).toJson();
            JSONObject prefs = exportPrefs(a);
            JSONArray friends = new JSONArray(
                    a.getSharedPreferences("friends", Context.MODE_PRIVATE).getString("list", "[]"));
            JSONObject todos = CourseTodoStore.load(a).toJson();
            JSONObject fun = exportFun(a);

            String now = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(new Date());
            JSONObject bundle = AppBackup.build(BuildConfig.VERSION_NAME, now,
                    archive, schedule, prefs, friends, todos, fun);

            String stamp = new SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(new Date());
            File out = ShareFiles.exportFile(a, "lvlv_backup_" + stamp + ".json");
            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(bundle.toString(2).getBytes(StandardCharsets.UTF_8));
                fos.flush();
            }
            ShareFiles.send(a, out, "application/json",
                    "驴驴课表全量备份 v" + BuildConfig.VERSION_NAME, "导出全量备份");
        } catch (Exception e) {
            DiagLog.error(a, "backup", "导出全量备份失败", e);
            Toast.makeText(a, "备份失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    static void pickRestore(Activity a) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        try {
            a.startActivityForResult(intent, REQ_RESTORE);
        } catch (Exception e) {
            Toast.makeText(a, "无法打开文件选择器", Toast.LENGTH_SHORT).show();
        }
    }

    static void handleRestoreUri(Activity a, Uri uri, Runnable onRestored) {
        if (uri == null) return;
        try {
            String raw = readUri(a, uri);
            JSONObject backup = AppBackup.parseAndValidate(raw);
            new AlertDialog.Builder(a)
                    .setTitle("确认恢复全量备份")
                    .setMessage(AppBackup.summarize(backup))
                    .setNegativeButton("取消", null)
                    .setPositiveButton("一键恢复", (d, w) -> {
                        if (applyRestore(a, backup)) {
                            Toast.makeText(a, "备份已恢复", Toast.LENGTH_SHORT).show();
                            if (onRestored != null) onRestored.run();
                        } else {
                            Toast.makeText(a, "部分数据恢复失败，请重试", Toast.LENGTH_LONG).show();
                        }
                    })
                    .show();
        } catch (Exception e) {
            DiagLog.error(a, "backup", "恢复备份解析失败", e);
            DiagLog.failDialog(a, "备份文件无法恢复", "原因：" + e.getMessage());
        }
    }

    private static boolean applyRestore(Context c, JSONObject backup) {
        try {
            JSONObject arch = backup.optJSONObject("timetableArchive");
            if (arch != null) {
                File dir = c.getFilesDir();
                File tmp = new File(dir, ARCHIVE_TMP);
                File dst = new File(dir, ARCHIVE_FILE);
                try (FileOutputStream fos = new FileOutputStream(tmp)) {
                    fos.write(arch.toString().getBytes(StandardCharsets.UTF_8));
                    fos.flush();
                    fos.getFD().sync();
                }
                if (dst.exists()) dst.delete();
                tmp.renameTo(dst);
                SemesterArchive.ensureActiveMirror(c);
            }
            JSONObject sched = backup.optJSONObject("schedule");
            if (sched != null) {
                ScheduleSettingsStore.save(c, ScheduleSettings.fromJson(sched.toString()));
            }
            JSONObject prefs = backup.optJSONObject("prefs");
            if (prefs != null) restorePrefs(c, prefs);
            JSONArray friends = backup.optJSONArray("friends");
            if (friends != null) {
                c.getSharedPreferences("friends", Context.MODE_PRIVATE)
                        .edit().putString("list", friends.toString()).apply();
            }
            JSONObject todos = backup.optJSONObject("todos");
            if (todos != null) {
                CourseTodoStore.save(c, CourseTodo.fromJson(todos));
            }
            JSONObject fun = backup.optJSONObject("fun");
            if (fun != null) restoreFun(c, fun);

            ClassReminder.reschedule(c);
            ExamReminder.reschedule(c);
            ClassWidget.updateAll(c.getApplicationContext());
            return true;
        } catch (Throwable t) {
            DiagLog.error(c, "backup", "应用全量备份失败", t);
            return false;
        }
    }

    private static JSONObject exportPrefs(Context c) throws Exception {
        JSONObject o = new JSONObject();
        o.put("theme", Prefs.theme(c));
        o.put("nightMode", Prefs.nightMode(c));
        o.put("displayDays", Prefs.displayDays(c));
        o.put("showInactiveCourses", Prefs.showInactiveCourses(c));
        o.put("showCourseCode", Prefs.showCourseCode(c));
        o.put("hiddenLocationPhrases", Prefs.hiddenLocationPhrases(c));
        o.put("fitAllSections", Prefs.fitAllSections(c));
        o.put("rainHint", Prefs.rainHint(c));
        o.put("classRemind", Prefs.classRemind(c));
        o.put("classRemindLead", Prefs.classRemindLead(c));
        o.put("nextClassNotice", Prefs.nextClassNotice(c));
        o.put("gradeNotify", Prefs.gradeNotify(c));
        o.put("examRemind", Prefs.examRemind(c));
        o.put("examInTimetable", Prefs.examInTimetable(c));
        o.put("shareName", Prefs.shareName(c));
        o.put("updateAutoCheck", Prefs.updateAutoCheck(c));
        o.put("updateWifiBackground", Prefs.updateWifiBackground(c));
        return o;
    }

    private static void restorePrefs(Context c, JSONObject o) {
        if (o.has("theme")) Prefs.setTheme(c, o.optInt("theme", G.THEME_GLASS));
        if (o.has("nightMode")) {
            int nm = o.optInt("nightMode", 0);
            Prefs.setNightMode(c, nm);
            App.applyNightMode(nm);
        }
        if (o.has("displayDays")) Prefs.setDisplayDays(c, o.optInt("displayDays", 7));
        if (o.has("showInactiveCourses")) Prefs.setShowInactiveCourses(c, o.optBoolean("showInactiveCourses", false));
        if (o.has("showCourseCode")) Prefs.setShowCourseCode(c, o.optBoolean("showCourseCode", false));
        if (o.has("hiddenLocationPhrases")) Prefs.setHiddenLocationPhrases(c, o.optString("hiddenLocationPhrases", ""));
        if (o.has("fitAllSections")) Prefs.setFitAllSections(c, o.optBoolean("fitAllSections", false));
        if (o.has("rainHint")) Prefs.setRainHint(c, o.optBoolean("rainHint", true));
        if (o.has("classRemind")) Prefs.setClassRemind(c, o.optBoolean("classRemind", false));
        if (o.has("classRemindLead")) Prefs.setClassRemindLead(c, o.optInt("classRemindLead", 10));
        if (o.has("nextClassNotice")) Prefs.setNextClassNotice(c, o.optBoolean("nextClassNotice", false));
        if (o.has("gradeNotify")) Prefs.setGradeNotify(c, o.optBoolean("gradeNotify", true));
        if (o.has("examRemind")) Prefs.setExamRemind(c, o.optBoolean("examRemind", true));
        if (o.has("examInTimetable")) Prefs.setExamInTimetable(c, o.optBoolean("examInTimetable", true));
        if (o.has("shareName")) Prefs.setShareName(c, o.optString("shareName", ""));
        if (o.has("updateAutoCheck")) Prefs.setUpdateAutoCheck(c, o.optBoolean("updateAutoCheck", true));
        if (o.has("updateWifiBackground")) Prefs.setUpdateWifiBackground(c, o.optBoolean("updateWifiBackground", false));
    }

    private static JSONObject exportFun(Context c) throws Exception {
        SharedPreferences sp = c.getSharedPreferences("fun", Context.MODE_PRIVATE);
        JSONObject o = new JSONObject();
        o.put("grade_verdict", sp.getBoolean("grade_verdict", true));
        o.put("tuition", sp.getString("tuition", ""));
        JSONObject absences = new JSONObject();
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            if (e.getKey() != null && e.getKey().startsWith("absence_") && e.getValue() instanceof String) {
                absences.put(e.getKey(), e.getValue());
            }
        }
        o.put("absences", absences);
        return o;
    }

    private static void restoreFun(Context c, JSONObject o) {
        SharedPreferences.Editor ed = c.getSharedPreferences("fun", Context.MODE_PRIVATE).edit();
        if (o.has("grade_verdict")) ed.putBoolean("grade_verdict", o.optBoolean("grade_verdict", true));
        if (o.has("tuition")) ed.putString("tuition", o.optString("tuition", ""));
        JSONObject absences = o.optJSONObject("absences");
        if (absences != null) {
            Iterator<String> keys = absences.keys();
            while (keys.hasNext()) {
                String k = keys.next();
                if (k.startsWith("absence_")) ed.putString(k, absences.optString(k, ""));
            }
        }
        ed.apply();
    }

    private static JSONObject readJsonObject(File f) {
        if (!f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new JSONObject(bos.toString("UTF-8"));
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String readUri(Context c, Uri uri) throws Exception {
        try (InputStream is = c.getContentResolver().openInputStream(uri);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            if (is != null) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        }
    }
}
