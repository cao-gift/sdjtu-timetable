package com.ecut.campus;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.CalendarContract;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.ecut.campus.data.ExamCountdown;
import com.ecut.campus.data.IcsExporter;
import com.ecut.campus.data.Timetable;
import com.ecut.campus.data.TimetableStore;

import java.util.List;

/**
 * 系统日历直写与自动更新：
 * 使用独立本地日历账户「驴驴课表」（ACCOUNT_TYPE_LOCAL），每次更新只替换本应用日历下的日程，
 * 绝不碰用户的其他个人日历事件。同时保留 .ics 文件导出作为兜底。
 */
final class CalendarSyncManager {

    static final int REQ_CALENDAR_PERM = 42;
    private static final String ACCOUNT_NAME = "lvlv_timetable";
    private static final String CALENDAR_NAME = "驴驴课表";
    private static final String TZ = "Asia/Shanghai";

    private static boolean pendingAutoEnable;

    private CalendarSyncManager() {}

    static boolean hasPermission(Context c) {
        if (Build.VERSION.SDK_INT < 23) return true;
        return ContextCompat.checkSelfPermission(c, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(c, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED;
    }

    static void showMenu(MainActivity a, Runnable onUpdated) {
        boolean auto = Prefs.calendarAutoSync(a);
        String[] items = {
                "一键写入系统日历（课表 + 考试）",
                "课表更新时自动同步日历：" + (auto ? "已开启" : "已关闭"),
                "导出 .ics 日历文件"
        };
        new AlertDialog.Builder(a)
                .setTitle("手机日历")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        pendingAutoEnable = false;
                        requestOrSync(a, onUpdated);
                    } else if (which == 1) {
                        if (auto) {
                            Prefs.setCalendarAutoSync(a, false);
                            Toast.makeText(a, "已关闭自动同步系统日历", Toast.LENGTH_SHORT).show();
                            if (onUpdated != null) onUpdated.run();
                        } else {
                            pendingAutoEnable = true;
                            requestOrSync(a, onUpdated);
                        }
                    } else {
                        a.exportIcs();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private static void requestOrSync(Activity a, Runnable onUpdated) {
        if (!hasPermission(a)) {
            if (Build.VERSION.SDK_INT >= 23) {
                a.requestPermissions(new String[]{
                        Manifest.permission.READ_CALENDAR,
                        Manifest.permission.WRITE_CALENDAR
                }, REQ_CALENDAR_PERM);
            }
            return;
        }
        if (pendingAutoEnable) {
            Prefs.setCalendarAutoSync(a, true);
            pendingAutoEnable = false;
        }
        runSyncWithFeedback(a, onUpdated);
    }

    static void onPermissionResult(MainActivity a, int[] grantResults, Runnable onUpdated) {
        boolean ok = grantResults != null && grantResults.length > 0;
        if (ok) {
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) { ok = false; break; }
            }
        }
        if (!ok) {
            pendingAutoEnable = false;
            Toast.makeText(a, "未授予日历权限，可改用「导出 .ics 日历文件」", Toast.LENGTH_LONG).show();
            return;
        }
        if (pendingAutoEnable) {
            Prefs.setCalendarAutoSync(a, true);
            pendingAutoEnable = false;
        }
        runSyncWithFeedback(a, onUpdated);
    }

    private static void runSyncWithFeedback(Activity a, Runnable onUpdated) {
        Toast.makeText(a, "正在写入系统日历…", Toast.LENGTH_SHORT).show();
        Context app = a.getApplicationContext();
        new Thread(() -> {
            Result res = syncNow(app);
            a.runOnUiThread(() -> {
                if (a.isFinishing()) return;
                if (res.error != null) {
                    Toast.makeText(a, res.error, Toast.LENGTH_LONG).show();
                } else {
                    String msg = "已写入系统日历：" + res.classCount + " 节课程"
                            + (res.examCount > 0 ? "、" + res.examCount + " 场考试" : "");
                    Toast.makeText(a, msg, Toast.LENGTH_LONG).show();
                }
                if (onUpdated != null) onUpdated.run();
            });
        }, "cal-sync").start();
    }

    /** 开启自动更新且有权限时，在后台静默刷新「驴驴课表」系统日历。 */
    static void syncIfEnabledAsync(Context ctx) {
        if (ctx == null || !Prefs.calendarAutoSync(ctx) || !hasPermission(ctx)) return;
        Context app = ctx.getApplicationContext();
        new Thread(() -> syncNow(app), "cal-auto-sync").start();
    }

    static final class Result {
        int classCount;
        int examCount;
        String error;
    }

    static Result syncNow(Context ctx) {
        Result res = new Result();
        if (!hasPermission(ctx)) {
            res.error = "缺少系统日历权限";
            return res;
        }
        Timetable t = TimetableStore.load(ctx);
        if (t == null || t.courses.isEmpty()) {
            res.error = "当前还没有本地课表可写入";
            return res;
        }
        String start = WeekMath.normalizeToMonday(Prefs.semesterStartDate(ctx));
        if (start.isEmpty()) {
            res.error = "请先在「课表设置」中设置第 1 周开学日期";
            return res;
        }
        t.semester.startDate = start;
        t.semester.totalWeeks = Prefs.storedTotalWeeks(ctx);
        ScheduleSettings sched = ScheduleSettingsStore.load(ctx);
        List<IcsExporter.CalendarEvent> events = IcsExporter.collectEvents(t, sched);
        List<ExamCountdown.Entry> exams = ExamReminder.loadEntries(ctx);

        try {
            ContentResolver cr = ctx.getContentResolver();
            long calId = ensureLocalCalendar(cr);
            if (calId < 0) {
                res.error = "无法创建或访问系统本地日历，可改用导出 .ics";
                return res;
            }
            // 只清空「驴驴课表」专属本地日历里的旧事件
            cr.delete(CalendarContract.Events.CONTENT_URI,
                    CalendarContract.Events.CALENDAR_ID + "=?",
                    new String[]{String.valueOf(calId)});

            int remindMin = Prefs.classRemind(ctx) ? Prefs.classRemindLead(ctx) : 10;
            for (IcsExporter.CalendarEvent ev : events) {
                ContentValues v = new ContentValues();
                v.put(CalendarContract.Events.CALENDAR_ID, calId);
                v.put(CalendarContract.Events.TITLE, ev.summary);
                v.put(CalendarContract.Events.EVENT_LOCATION, ev.location);
                v.put(CalendarContract.Events.DESCRIPTION, ev.description);
                v.put(CalendarContract.Events.DTSTART, ev.startMillis);
                v.put(CalendarContract.Events.DTEND, ev.endMillis);
                v.put(CalendarContract.Events.EVENT_TIMEZONE, TZ);
                Uri inserted = cr.insert(CalendarContract.Events.CONTENT_URI, v);
                if (inserted != null) {
                    res.classCount++;
                    long eventId = ContentUris.parseId(inserted);
                    addReminder(cr, eventId, remindMin);
                }
            }

            for (ExamCountdown.Entry ex : exams) {
                if (!ex.hasDate()) continue;
                ContentValues v = new ContentValues();
                v.put(CalendarContract.Events.CALENDAR_ID, calId);
                v.put(CalendarContract.Events.TITLE, "[考试] " + ex.name);
                v.put(CalendarContract.Events.EVENT_LOCATION, ex.room);
                StringBuilder d = new StringBuilder();
                if (!ex.timeRaw.isEmpty()) d.append(ex.timeRaw);
                if (!ex.seat.isEmpty()) d.append(d.length() > 0 ? " · " : "").append("座位 ").append(ex.seat);
                v.put(CalendarContract.Events.DESCRIPTION, d.toString());
                v.put(CalendarContract.Events.DTSTART, ex.startMillis);
                v.put(CalendarContract.Events.DTEND, Math.max(ex.endMillis, ex.startMillis + 3600_000L));
                v.put(CalendarContract.Events.EVENT_TIMEZONE, TZ);
                Uri inserted = cr.insert(CalendarContract.Events.CONTENT_URI, v);
                if (inserted != null) {
                    res.examCount++;
                    long eventId = ContentUris.parseId(inserted);
                    addReminder(cr, eventId, 60);
                    addReminder(cr, eventId, 24 * 60);
                }
            }
            Prefs.setCalendarSyncedAt(ctx, System.currentTimeMillis());
            DiagLog.log(ctx, "calendar", "系统日历已更新: 课=" + res.classCount + " 考=" + res.examCount);
        } catch (Throwable e) {
            DiagLog.error(ctx, "calendar", "写入系统日历失败", e);
            res.error = "写入系统日历失败：" + e.getMessage();
        }
        return res;
    }

    private static void addReminder(ContentResolver cr, long eventId, int minutes) {
        try {
            ContentValues r = new ContentValues();
            r.put(CalendarContract.Reminders.EVENT_ID, eventId);
            r.put(CalendarContract.Reminders.MINUTES, minutes);
            r.put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT);
            cr.insert(CalendarContract.Reminders.CONTENT_URI, r);
        } catch (Throwable ignored) {
        }
    }

    private static long ensureLocalCalendar(ContentResolver cr) {
        String[] proj = {CalendarContract.Calendars._ID};
        String sel = CalendarContract.Calendars.ACCOUNT_NAME + "=? AND "
                + CalendarContract.Calendars.ACCOUNT_TYPE + "=?";
        String[] args = {ACCOUNT_NAME, CalendarContract.ACCOUNT_TYPE_LOCAL};
        try (Cursor c = cr.query(CalendarContract.Calendars.CONTENT_URI, proj, sel, args, null)) {
            if (c != null && c.moveToFirst()) return c.getLong(0);
        }
        Uri syncUri = CalendarContract.Calendars.CONTENT_URI.buildUpon()
                .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                .build();
        ContentValues v = new ContentValues();
        v.put(CalendarContract.Calendars.ACCOUNT_NAME, ACCOUNT_NAME);
        v.put(CalendarContract.Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL);
        v.put(CalendarContract.Calendars.NAME, ACCOUNT_NAME);
        v.put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CALENDAR_NAME);
        v.put(CalendarContract.Calendars.CALENDAR_COLOR, 0xFF111111);
        v.put(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.CAL_ACCESS_OWNER);
        v.put(CalendarContract.Calendars.OWNER_ACCOUNT, ACCOUNT_NAME);
        v.put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, TZ);
        v.put(CalendarContract.Calendars.SYNC_EVENTS, 1);
        v.put(CalendarContract.Calendars.VISIBLE, 1);
        Uri created = cr.insert(syncUri, v);
        return created == null ? -1L : ContentUris.parseId(created);
    }
}
