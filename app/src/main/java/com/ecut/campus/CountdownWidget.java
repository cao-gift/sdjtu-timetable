package com.ecut.campus;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;
import android.widget.RemoteViews;

import androidx.core.content.ContextCompat;

import com.ecut.campus.data.Dates;
import com.ecut.campus.data.DayPlan;
import com.ecut.campus.data.WidgetProgress;

import java.util.List;

/** 2×2 桌面小组件「下一节课倒计时」：大号等宽倒计时 + 课名地点 + 正在上课进度条。 */
public final class CountdownWidget extends AppWidgetProvider {

    @Override
    public void onUpdate(Context c, AppWidgetManager m, int[] ids) {
        render(c, m, ids);
        ClassWidget.scheduleTick(c);
        WidgetRefreshWorker.schedule(c);
    }

    @Override
    public void onEnabled(Context c) {
        WidgetRefreshWorker.schedule(c);
    }

    @Override
    public void onDisabled(Context c) {
        ClassWidget.cancelTickIfIdle(c);
    }

    static WidgetProgress.Snapshot buildSnapshot(Context c, long now) {
        String ymd = ClassPlans.ymd(now);
        int nowMin = ClassPlans.nowMinutes(now);
        ClassPlans.Ctx x = ClassPlans.load(c);
        int week = Dates.valid(x.start) ? Dates.weekOf(x.start, ymd) : 0;
        int semState = ClassWidget.state(x, week);
        List<DayPlan.Item> today = semState == com.ecut.campus.data.WidgetDay.OK ? ClassPlans.day(x, ymd) : null;
        List<DayPlan.Item> tomorrow = semState == com.ecut.campus.data.WidgetDay.OK
                ? ClassPlans.day(x, Dates.addDays(ymd, 1)) : null;
        String examHint = ExamReminder.upcomingBanner(c, now);
        String todoHint = TodoReminder.upcomingWidgetHint(c, now);
        com.ecut.campus.data.Holidays.BreakInfo holidayBreak =
                semState == com.ecut.campus.data.WidgetDay.OK
                        ? com.ecut.campus.data.Holidays.activeBreak(x.t, ymd) : null;
        return WidgetProgress.build(today, tomorrow, nowMin, examHint, todoHint,
                semState, ymd, x.start, holidayBreak);
    }

    static void render(Context c, AppWidgetManager m, int[] ids) {
        if (m == null || ids == null || ids.length == 0) return;
        long now = System.currentTimeMillis();
        int theme = ClassWidget.theme(c);
        boolean night = ClassWidget.isNight(c, theme);
        Context tc = ClassWidget.themed(c, theme);
        int ink = ContextCompat.getColor(tc, R.color.w_ink);
        int sub = ContextCompat.getColor(tc, R.color.w_sub);
        int accent = ContextCompat.getColor(tc, R.color.w_accent);
        WidgetProgress.Snapshot snap = buildSnapshot(c, now);
        PendingIntent open = ClassWidget.openTimetable(c, 75, false);

        for (int id : ids) {
            try {
                RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_countdown);
                v.setImageViewResource(R.id.wc_bg, ClassWidget.cardDrawable(c, theme, night));
                v.setTextColor(R.id.wc_title, ink);
                v.setTextColor(R.id.wc_detail, sub);
                boolean urgent = snap.mode == WidgetProgress.MODE_LIVE;
                v.setTextColor(R.id.wc_badge, urgent ? accent : sub);
                v.setTextColor(R.id.wc_readout, urgent ? accent : ink);
                v.setTextColor(R.id.wc_chrono, urgent ? accent : ink);
                v.setTextViewText(R.id.wc_badge, snap.badge);
                v.setTextViewText(R.id.wc_title, snap.title);
                v.setTextViewText(R.id.wc_detail, snap.detail);

                boolean useChrono = false;
                if (snap.countdownMin >= 0 && Build.VERSION.SDK_INT >= 24) {
                    long targetMs = ClassPlans.at(ClassPlans.midnight(now), snap.countdownMin);
                    long remMs = targetMs - now;
                    if (remMs > 0 && remMs <= 3 * 3600_000L) {
                        long base = SystemClock.elapsedRealtime() + remMs;
                        v.setChronometer(R.id.wc_chrono, base, "%s", true);
                        v.setChronometerCountDown(R.id.wc_chrono, true);
                        v.setViewVisibility(R.id.wc_chrono, View.VISIBLE);
                        v.setViewVisibility(R.id.wc_readout, View.GONE);
                        useChrono = true;
                    }
                }
                if (!useChrono) {
                    v.setViewVisibility(R.id.wc_chrono, View.GONE);
                    v.setViewVisibility(R.id.wc_readout, View.VISIBLE);
                    v.setTextViewText(R.id.wc_readout, snap.primaryReadout);
                }

                if (snap.livePercent >= 0) {
                    v.setViewVisibility(R.id.wc_progress, View.VISIBLE);
                    v.setProgressBar(R.id.wc_progress, 100, snap.livePercent, false);
                } else {
                    v.setViewVisibility(R.id.wc_progress, View.GONE);
                }

                v.setOnClickPendingIntent(android.R.id.background, open);
                v.setOnClickPendingIntent(R.id.wc_content, open);
                v.setOnClickPendingIntent(R.id.wc_bg, open);
                m.updateAppWidget(id, v);
            } catch (Throwable ignored) {
            }
        }
    }
}
