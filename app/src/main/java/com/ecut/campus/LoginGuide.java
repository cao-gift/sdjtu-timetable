package com.ecut.campus;

import android.app.Activity;

import androidx.appcompat.app.AlertDialog;

/**
 * 使用指南说明，仅在「关于 → 使用指南」由用户手动点击查看。
 */
public final class LoginGuide {

    static final String TEXT =
            "① 同步课表\n点课表页右上角「⋯ → 同步课表」，或在「设置 → 教务账号」中同步。以后想更新再点一次就行。\n\n"
            + "② 看课表\n左右滑动切换周，点课程块看教室和老师。每次打开都会回到本周。\n\n"
            + "③ 查成绩\n设置 → 学业成绩 → 同步成绩。在「通用」里打开「出新成绩时提醒」，出分会自动通知你。\n\n"
            + "④ 和同学约时间\n设置 → 同学，扫码互加后能看到你们都没课的时间。\n\n";

    private LoginGuide() {}

    public static void show(Activity a) {
        new AlertDialog.Builder(a)
                .setTitle("使用指南")
                .setMessage(TEXT + "在「设置 → 教务账号」保存门户账号密码后，登录过期时可自动重新登录。学校要求额外验证时，请打开门户完成验证。")
                .setPositiveButton("知道了", null)
                .show();
    }
}
