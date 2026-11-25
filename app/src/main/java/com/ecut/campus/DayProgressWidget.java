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

import com.ecut.campus.data.WidgetProgress;

/** 4×1 桌面小组件「今日进度条」：今日已上/总课数进度条 + 当前或下一节课状态。 */
public final class DayProgressWidget extends AppWidgetProvider {

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

    static void render(Context c, AppWidgetManager m, int[] ids) {
        if (m == null || ids == null || ids.length == 0) return;
        long now = System.currentTimeMillis();
        int theme = ClassWidget.theme(c);
        boolean night = ClassWidget.isNight(c, theme);
        Context tc = ClassWidget.themed(c, theme);
        int ink = ContextCompat.getColor(tc, R.color.w_ink);
        int sub = ContextCompat.getColor(tc, R.color.w_sub);
        int accent = ContextCompat.getColor(tc, R.color.w_accent);
        WidgetProgress.Snapshot snap = CountdownWidget.buildSnapshot(c, now);
        PendingIntent open = ClassWidget.openTimetable(c, 76, false);

        for (int id : ids) {
            try {
                RemoteViews v = new RemoteViews(c.getPackageName(), R.layout.widget_day_progress);
                if (theme != ClassWidget.THEME_SYSTEM) {
                    v.setImageViewResource(R.id.wp_bg, night ? R.drawable.w_card_night : R.drawable.w_card_day);
                    v.setTextColor(R.id.wp_title, ink);
                    v.setTextColor(R.id.wp_detail, sub);
                    v.setTextColor(R.id.wp_ratio, accent);
                    v.setTextColor(R.id.wp_chrono, accent);
                }
                String prefix = snap.mode == WidgetProgress.MODE_LIVE ? "正在上 · "
                        : (snap.mode == WidgetProgress.MODE_NEXT ? "下一节 · " : "");
                v.setTextViewText(R.id.wp_title, prefix + snap.title);
                v.setTextViewText(R.id.wp_ratio, snap.totalCount > 0 ? snap.ratioLabel + " 门" : "今日无课");
                v.setProgressBar(R.id.wp_bar, 100, snap.dayPercent, false);
                v.setTextViewText(R.id.wp_detail, snap.detail);

                boolean showChrono = false;
                if (snap.countdownMin >= 0 && Build.VERSION.SDK_INT >= 24) {
                    long targetMs = ClassPlans.at(ClassPlans.midnight(now), snap.countdownMin);
                    long remMs = targetMs - now;
                    if (remMs > 0 && remMs <= 3 * 3600_000L) {
                        long base = SystemClock.elapsedRealtime() + remMs;
                        String fmt = snap.mode == WidgetProgress.MODE_LIVE ? "剩 %s" : "%s 后";
                        v.setChronometer(R.id.wp_chrono, base, fmt, true);
                        v.setChronometerCountDown(R.id.wp_chrono, true);
                        v.setViewVisibility(R.id.wp_chrono, View.VISIBLE);
                        showChrono = true;
                    }
                }
                if (!showChrono) {
                    v.setViewVisibility(R.id.wp_chrono, View.GONE);
                }

                v.setOnClickPendingIntent(android.R.id.background, open);
                v.setOnClickPendingIntent(R.id.wp_content, open);
                v.setOnClickPendingIntent(R.id.wp_bg, open);
                m.updateAppWidget(id, v);
            } catch (Throwable ignored) {
            }
        }
    }
}
