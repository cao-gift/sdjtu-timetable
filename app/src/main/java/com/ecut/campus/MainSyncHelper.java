package com.ecut.campus;

import android.content.Context;
import android.graphics.Typeface;
import android.text.SpannableStringBuilder;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

/** 主页标题排版与后台静默同步协调器（从 MainActivity 拆出）。 */
final class MainSyncHelper {

    private MainSyncHelper() {}

    /** 刻度标题：小字「第」+ 巨大等宽周数 + 「周」。 */
    static CharSequence gaugeWeekTitle(Context ctx, int week) {
        String num = week < 10 ? "0" + week : String.valueOf(week);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        sb.append("第 ");
        int a = sb.length();
        sb.append(num);
        int b = sb.length();
        sb.append(" 周");
        float d = ctx.getResources().getDisplayMetrics().scaledDensity;
        sb.setSpan(new AbsoluteSizeSpan(Math.round(13 * d)), 0, a, 0);
        sb.setSpan(new ForegroundColorSpan(G.MUTE), 0, a, 0);
        sb.setSpan(new AbsoluteSizeSpan(Math.round(46 * d)), a, b, 0);
        sb.setSpan(new TypefaceSpan("monospace"), a, b, 0);
        sb.setSpan(new StyleSpan(Typeface.BOLD), a, b, 0);
        sb.setSpan(new AbsoluteSizeSpan(Math.round(13 * d)), b, sb.length(), 0);
        sb.setSpan(new ForegroundColorSpan(G.MUTE), b, sb.length(), 0);
        sb.setSpan(new TypefaceSpan("monospace"), b, sb.length(), 0);
        return sb;
    }

    /** 课表超过 3 天没同步或会话过期时，在副标题后提示一句。 */
    static String staleHint(Context ctx) {
        if (Prefs.sessionExpired(ctx)) return "  ·  登录已过期";
        long at = Prefs.timetableSyncedAt(ctx);
        if (at <= 0) return "";
        long days = (System.currentTimeMillis() - at) / 86_400_000L;
        return days >= 3 ? "  ·  " + days + " 天前同步" : "";
    }

    static void startSilentSync(MainActivity a, CampusSyncClient.Kind kind) {
        startSilentSync(a, kind, true);
    }

    static void startSilentSync(MainActivity a, CampusSyncClient.Kind kind, boolean allowLoginRedirect) {
        if (a.activeSync != null && a.activeSync.isRunning()) {
            Toast.makeText(a, "正在同步，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        int requestCode = kind == CampusSyncClient.Kind.TIMETABLE
                ? WebShellActivity.REQ_SDJTU_TIMETABLE : WebShellActivity.REQ_SDJTU_GRADES;
        if (allowLoginRedirect && WebShellActivity.needsManualLoginFirst(a)) {
            WebShellActivity.startManualLoginForSync(a, requestCode, kind.label, null);
            return;
        }
        boolean autoLogin = !Prefs.hasPortalSession(a) || Prefs.sessionExpired(a);
        Toast.makeText(a, (autoLogin ? "正在自动登录并同步" : "正在后台同步") + kind.label + "…",
                Toast.LENGTH_SHORT).show();
        a.activeSync = CampusSyncClient.start(a.newWebViewForSync(), kind,
                new CampusSyncClient.Callback() {
                    @Override
                    public void onSuccess(int count) {
                        a.activeSync = null;
                        a.refreshTimetableTab();
                        a.refreshSettingsTab();
                        String diff = kind == CampusSyncClient.Kind.TIMETABLE
                                ? CampusSyncClient.lastTimetableDiff : "";
                        CampusSyncClient.lastTimetableDiff = "";
                        if (diff != null && !diff.isEmpty()) {
                            new AlertDialog.Builder(a)
                                    .setTitle("课表有变化")
                                    .setMessage(diff)
                                    .setPositiveButton("知道了", null)
                                    .show();
                        } else {
                            Toast.makeText(a, kind.label + "已更新：" + count + " 门",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onLoginRequired(String message) {
                        a.activeSync = null;
                        a.refreshSettingsTab();
                        if (allowLoginRedirect) {
                            WebShellActivity.startManualLoginForSync(a, requestCode, kind.label, message);
                        } else {
                            WebShellActivity.offerSdjtuLogin(a, kind, message);
                        }
                    }

                    @Override
                    public void onFailure(String message) {
                        a.activeSync = null;
                        a.refreshSettingsTab();
                        a.refreshTimetableTab();
                        DiagLog.failDialog(a, kind.label + "没同步成功", message);
                    }
                });
    }
}
