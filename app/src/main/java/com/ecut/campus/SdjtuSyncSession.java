package com.ecut.campus;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.SystemClock;
import android.webkit.WebView;

import com.ecut.campus.data.QiangzhiSource;
import com.ecut.campus.data.QiangzhiGrades;
import com.ecut.campus.data.SdjtuPortal;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;

/** Drives an existing WebView from a saved school session or the WebVPN portal to a Qiangzhi query. */
final class SdjtuSyncSession {
    interface Listener {
        void ready();
        void fail(String message, boolean loginRequired);
    }

    private static final String PAUSED_GENERATION = "pausedGeneration";
    // All clients and their WebView callbacks run on the main thread.
    private static SdjtuSyncSession signingIn;

    private final WebView web;
    private final Handler handler;
    private final Context context;
    private final Listener listener;
    private final CampusSyncClient.Kind kind;
    private final long generation;
    private final SharedPreferences attempts;
    private final boolean pausedWhenStarted;
    private final Runnable poll = this::inspect;
    private String portalScript;
    private String base;
    private boolean portalOpened;
    private boolean targetAfterSso;
    private boolean stopped;
    private boolean reading;
    private boolean evaluating;
    private boolean attempted;
    private int document;
    private long pageSince;
    private long submittedAt;

    SdjtuSyncSession(WebView web, Handler handler, CampusSyncClient.Kind kind, Listener listener) {
        this.web = web;
        this.handler = handler;
        this.kind = kind;
        this.listener = listener;
        context = web.getContext().getApplicationContext();
        generation = SdjtuCredentials.generation(context);
        attempts = context.getSharedPreferences("sdjtu_login_state", Context.MODE_PRIVATE);
        pausedWhenStarted = attempts.getLong(PAUSED_GENERATION, -1) == generation;
    }

    static boolean loginPaused(Context context) {
        return context.getSharedPreferences("sdjtu_login_state", Context.MODE_PRIVATE)
                .getLong(PAUSED_GENERATION, -1) == SdjtuCredentials.generation(context);
    }

    void start(String cachedBase) {
        if (!current()) return;
        try {
            portalScript = SdjtuAcademicPages.read(web, "sdjtu-portal.js");
        } catch (IOException e) {
            listener.fail("无法加载学校门户适配，请重新打开应用后重试", false);
            return;
        }
        String home = SdjtuPortal.studentHome(cachedBase);
        base = SdjtuPortal.academicBase(SdjtuPortal.isSchoolUrl(home) ? home : SdjtuPortal.STUDENT_HOME);
        // The WebView cookie jar can outlive the app's session flag. A direct read is also
        // possible on a fresh install because the school has a known academic root.
        web.loadUrl(target());
    }

    boolean current() {
        if (stopped) return false;
        if (SdjtuCredentials.isUpdating() || generation != SdjtuCredentials.generation(context)) {
            listener.fail("保存的门户账号已更改，请重新同步", false);
            return false;
        }
        return true;
    }

    private String target() {
        return base + (kind == CampusSyncClient.Kind.TIMETABLE ? QiangzhiSource.TIMETABLE_PATH : QiangzhiGrades.GRADES_PATH);
    }

    /** Match the real document path. The CAS service query can contain the full timetable URL. */
    static boolean targetPage(String url, CampusSyncClient.Kind kind) {
        if (!SdjtuPortal.isSchoolUrl(url) || SdjtuPortal.academicBase(url).isEmpty()) return false;
        String path = URI.create(url).getRawPath();
        return kind == CampusSyncClient.Kind.TIMETABLE
                ? path.matches(".*/jsxsd/xskb/xskb_list(?:\\.(?:do|htmlx|jsp))?/?")
                : path.matches(".*/jsxsd/kscj/cjcx_list(?:\\.(?:do|htmlx|jsp))?/?");
    }

    void pageStarted(String url) {
        if (!current()) return;
        document++;
        evaluating = false;
        pageSince = SystemClock.elapsedRealtime();
        handler.removeCallbacks(poll);
        if (!SdjtuPortal.isSchoolUrl(url)) listener.fail("同步跳转到了学校 HTTPS 入口以外的页面，请打开统一门户核对", true);
    }

    void pageFinished(String url) {
        if (current() && !reading && url != null && url.equals(web.getUrl())) schedule(80);
    }

    private void schedule(long delay) {
        handler.removeCallbacks(poll);
        handler.postDelayed(poll, delay);
    }

    private boolean samePage(int id, String url) {
        if (!current() || id != document) return false;
        if (url.equals(web.getUrl())) return true;
        // The dashboard is a single-page app. A hash/history change can race a JS result
        // without onPageStarted (which normally resets the in-flight flag).
        evaluating = false;
        schedule(80);
        return false;
    }

    private void inspect() {
        String url = web.getUrl();
        if (!current() || reading || evaluating || !SdjtuPortal.isSchoolUrl(url)) return;
        int id = document;
        evaluating = true;
        if (SdjtuLoginForm.isLoginPage(url)) {
            SdjtuLoginForm.inspect(web, state -> {
                if (!samePage(id, url)) return;
                evaluating = false;
                onForm(state, id, url);
            });
            return;
        }

        URI uri = URI.create(url);
        String path = uri.getRawPath();
        // Only the portal's own resource list may launch an application card.
        boolean dashboard = "wwebvpn.sdjtu.edu.cn".equalsIgnoreCase(uri.getHost())
                && !path.matches("(?i)^/https?/.*");
        String js = "(function(){if(document.readyState==='loading'||!document.body)return {};"
                + "if(!document.__lvlvSyncPortal)document.__lvlvSyncPortal=" + portalScript
                + ";return document.__lvlvSyncPortal('portal'," + dashboard + ",false,null,{sync:true});})()";
        web.evaluateJavascript(js, raw -> {
            if (!samePage(id, url)) return;
            evaluating = false;
            JSONObject result;
            try {
                result = new JSONObject(WebShellActivity.unwrapJsString(raw));
            } catch (JSONException e) {
                schedule(500);
                return;
            }
            String stage = result.optString("stage");
            if ("login".equals(stage)) {
                // WebVPN fingerprint and ticket redirects can briefly look like login pages.
                long grace = path.toLowerCase(Locale.ROOT).contains("fingerprint") ? 12_000 : 4_000;
                if (SystemClock.elapsedRealtime() - pageSince < grace) schedule(550);
                else if (!portalOpened) openPortal();
                else requireLogin("门户需要你完成身份验证，请打开统一门户继续");
                return;
            }
            if ("error".equals(stage)) {
                if (!portalOpened && !attempted) openPortal();
                else listener.fail("学校教务暂时无法提供查询，请稍后重试", false);
                return;
            }
            if (stage.isEmpty()) { schedule(500); return; }

            String found = SdjtuPortal.academicBase(result.optString("base"));
            if (!found.isEmpty() && SdjtuPortal.isSchoolUrl(found)) {
                base = found;
                if (!base.equals(Prefs.sdjtuAcademicBase(context))) Prefs.setSdjtuAcademicBase(context, base);
                success();
            }
            if (targetPage(url, kind)) {
                if (found.isEmpty() && SystemClock.elapsedRealtime() - pageSince < 1_200) {
                    schedule(400);
                    return;
                }
                reading = true;
                listener.ready();
            } else if (!found.isEmpty() && SdjtuPortal.isSchoolUrl(found) && !targetAfterSso) {
                targetAfterSso = true;
                web.loadUrl(target());
            } else if (dashboard && SystemClock.elapsedRealtime() - pageSince > 15_000) {
                requireLogin("未能从门户资源页进入教务系统，请在统一门户中打开「教务系统」");
            } else if (!dashboard && !portalOpened && !attempted && SystemClock.elapsedRealtime() - pageSince > 5_000) {
                openPortal();
            } else {
                schedule(600);
            }
        });
    }

    private void onForm(String state, int id, String url) {
        if (SdjtuLoginForm.MANUAL.equals(state)) {
            pause();
            requireLogin("学校要求手动验证或核对账号密码，请打开统一门户继续");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (attempted) {
            if (now - submittedAt < 10_000) schedule(600);
            else requireLogin("登录后仍停留在认证页面，请打开统一门户完成验证或更新保存的密码");
            return;
        }
        if (!SdjtuLoginForm.READY.equals(state)) {
            if (now - pageSince < 12_000) schedule(500);
            else requireLogin("认证页面未显示可用的账号密码表单，请打开统一门户继续");
            return;
        }
        if (now - pageSince < 1_000) { schedule(450); return; }
        if (signingIn != null && signingIn != this) {
            listener.fail("另一项同步正在登录统一门户，请稍后再同步", false);
            return;
        }
        if (attempts.getLong(PAUSED_GENERATION, -1) == generation) {
            requireLogin("自动登录已暂停，请手动登录统一门户，或在设置中更新账号密码");
            return;
        }
        SdjtuCredentials.Account account = SdjtuCredentials.load(context);
        if (account == null) {
            requireLogin("请登录山东交通学院统一门户，或在设置中保存门户账号密码");
            return;
        }
        if (!samePage(id, url)) return;
        // Record the guard before sending a password, including across abrupt process death.
        if (!attempts.edit().putLong(PAUSED_GENERATION, generation).commit()) {
            requireLogin("无法保存自动登录状态，请在统一门户中登录");
            return;
        }
        signingIn = this;
        attempted = true;
        submittedAt = now;
        evaluating = true;
        SdjtuLoginForm.submit(web, account.username, account.password, submission -> {
            if (!samePage(id, url)) return;
            evaluating = false;
            if (SdjtuLoginForm.SUBMITTED.equals(submission)) schedule(650);
            else requireLogin("门户登录表单已变化，请打开统一门户完成验证");
        });
    }

    private void pause() {
        if (SdjtuCredentials.isSaved(context)) attempts.edit().putLong(PAUSED_GENERATION, generation).apply();
    }

    private void openPortal() {
        portalOpened = true;
        targetAfterSso = false;
        web.loadUrl(SdjtuPortal.LOGIN);
    }

    /** Some installations serve the login/redirect page under the timetable URL with HTTP 200. */
    boolean reopenPortalForMissingTable() {
        if (!current() || portalOpened || attempted) return false;
        reading = false;
        openPortal();
        return true;
    }

    void navigationError(String url, int status) {
        if (!current() || url == null || !url.equals(web.getUrl())) return;
        if (!portalOpened && !attempted && !SdjtuPortal.academicBase(url).isEmpty()) {
            openPortal();
        } else if (status == 401) {
            requireLogin("门户需要重新登录，请打开统一门户继续");
        } else {
            listener.fail(status > 0 ? "学校门户返回 HTTP " + status + "，请稍后重试"
                    : "无法连接学校门户，请检查网络后重试", false);
        }
    }

    private void requireLogin(String message) {
        if (attempted) pause();
        Prefs.setPortalSession(context, false);
        Prefs.setSessionExpired(context, true);
        listener.fail(message, true);
    }

    void success() {
        String validBase = SdjtuPortal.academicBase(base);
        if (!validBase.isEmpty() && !validBase.equals(Prefs.sdjtuAcademicBase(context))) {
            Prefs.setSdjtuAcademicBase(context, validBase);
        }
        if ((attempted || pausedWhenStarted) && (signingIn == null || signingIn == this)
                && attempts.getLong(PAUSED_GENERATION, -1) == generation)
            attempts.edit().remove(PAUSED_GENERATION).apply();
    }

    void stop() {
        stopped = true;
        if (signingIn == this) signingIn = null;
        handler.removeCallbacks(poll);
    }
}
