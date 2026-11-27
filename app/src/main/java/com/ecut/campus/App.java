package com.ecut.campus;

import android.app.Application;

import androidx.appcompat.app.AppCompatDelegate;

public class App extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        DiagLog.installCrashHandler(this);
        Prefs.migrateSchoolSettings(this);
        applyNightMode(Prefs.nightMode(this));
        G.init(this);
        purgeDemoGrades();
        // 冷启动只做必须的事：后台任务登记延迟 1.2 秒放进统一线程池，避免和课表绘制抢 CPU。
        Bg.runDelayed(1200L, () -> {
            try {
                GradeCheckWorker.schedule(this);
                TimetableCheckWorker.schedule(this);
                if (ClassWidget.placedCount(this) > 0) WidgetRefreshWorker.schedule(this);
            } catch (Throwable ignored) {
            }
        });
    }

    /** 1.2.2：演示成绩入口已移除，清掉旧版本合并进真实成绩里的样本。 */
    private void purgeDemoGrades() {
        try {
            com.ecut.campus.data.Grades g = com.ecut.campus.data.GradesStore.load(this);
            if (com.ecut.campus.data.DemoGrades.strip(g) > 0) {
                if (g.semesters.isEmpty()) com.ecut.campus.data.GradesStore.clear(this);
                else com.ecut.campus.data.GradesStore.save(this, g);
            }
            if (Prefs.isGradesDemo(this)) Prefs.setGradesDemo(this, false);
        } catch (Throwable ignored) {
        }
    }

    /** 0 跟随系统，1 浅色，2 深色。 */
    public static void applyNightMode(int mode) {
        AppCompatDelegate.setDefaultNightMode(mode == 1 ? AppCompatDelegate.MODE_NIGHT_NO
                : mode == 2 ? AppCompatDelegate.MODE_NIGHT_YES
                : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
    }
}
