package com.ecut.campus;

import android.content.Context;
import android.os.Handler;
import android.os.SystemClock;
import android.webkit.WebView;

import com.ecut.campus.data.QiangzhiGrades;
import com.ecut.campus.data.QiangzhiSource;
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

    private final WebView web;
    private final Handler handler;
    private final Context context;
    private final Listener listener;
    private final CampusSyncClient.Kind kind;
    private final SdjtuAutoLogin autoLogin;
    private final Runnable poll = this::inspect;
    private String portalScript;
    private String base;
    private boolean portalOpened;
    private boolean ssoOpened;
    private boolean targetAfterSso;
    private boolean stopped;
    private boolean reading;
    private boolean evaluating;
    private int document;
    private long pageSince;

    SdjtuSyncSession(WebView web, Handler handler, CampusSyncClient.Kind kind, Listener listener) {
        this.web = web;
        this.handler = handler;
        this.kind = kind;
        this.listener = listener;
        context = web.getContext().getApplicationContext();
        autoLogin = new SdjtuAutoLogin(context);
    }

    static boolean loginPaused(Context context) {
        return SdjtuAutoLogin.isPaused(context);
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
        // 明确未登录、会话已过期或用户主动点「自动登录」时直接走统一门户，完成认证并进入教务系统。
        if (kind == CampusSyncClient.Kind.LOGIN || !Prefs.hasPortalSession(context) || Prefs.sessionExpired(context)) {
            openPortal();
        } else {
            web.loadUrl(target());
        }
    }

    boolean current() {
        if (stopped) return false;
        if (autoLogin.credentialsChanged()) {
            listener.fail("保存的门户账号已更改，请重新同步", false);
            return false;
        }
        return true;
    }

    String currentBase() {
        return base;
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
        autoLogin.resetPage();
        pageSince = SystemClock.elapsedRealtime();
        handler.removeCallbacks(poll);
        if (!SdjtuPortal.isSchoolUrl(url)) {
            listener.fail("同步跳转到了学校 HTTPS 入口以外的页面，请打开统一门户核对", true);
            return;
        }
        if (!reading && (SdjtuLoginForm.isLoginPage(url) || SdjtuPortal.isDashboard(url)
                || !SdjtuPortal.academicBase(url).isEmpty())) {
            schedule(200);
        }
    }

    void pageFinished(String url) {
        if (current() && !reading && url != null && url.equals(web.getUrl())) schedule(30);
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
            autoLogin.inspectAndSubmit(web, pageSince, () -> samePage(id, url), new SdjtuAutoLogin.Callback() {
                @Override public void waitMore(long delayMs) {
                    evaluating = false;
                    schedule(delayMs);
                }

                @Override public void manual(String message) {
                    evaluating = false;
                    requireLogin(message);
                }

                @Override public void busy(String message) {
                    evaluating = false;
                    listener.fail(message, false);
                }
            });
            return;
        }

        URI uri = URI.create(url);
        String path = uri.getRawPath();
        // Only the portal's own resource list may launch an application card.
        boolean dashboard = SdjtuPortal.isDashboard(url);
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
                schedule(180);
                return;
            }
            String stage = result.optString("stage");
            if ("login".equals(stage)) {
                // WebVPN fingerprint and ticket redirects can briefly look like login pages.
                long grace = path != null && path.toLowerCase(Locale.ROOT).contains("fingerprint") ? 10_000 : 1_500;
                if (SystemClock.elapsedRealtime() - pageSince < grace) schedule(220);
                else if (!portalOpened) openPortal();
                else requireLogin("门户需要你完成身份验证，请打开统一门户继续");
                return;
            }
            if ("error".equals(stage)) {
                if (!portalOpened && !autoLogin.attempted()) openPortal();
                else listener.fail("学校教务暂时无法提供查询，请稍后重试", false);
                return;
            }
            if (stage.isEmpty()) { schedule(180); return; }

            if (dashboard) portalOpened = true;
            String found = SdjtuPortal.academicBase(result.optString("base"));
            if (!found.isEmpty() && SdjtuPortal.isSchoolUrl(found)) {
                base = found;
                if (!base.equals(Prefs.sdjtuAcademicBase(context))) Prefs.setSdjtuAcademicBase(context, base);
                success();
            }
            long elapsed = SystemClock.elapsedRealtime() - pageSince;
            if (targetPage(url, kind)) {
                if (found.isEmpty() && elapsed < 350) {
                    schedule(120);
                    return;
                }
                reading = true;
                listener.ready();
            } else if (!found.isEmpty() && SdjtuPortal.isSchoolUrl(found)) {
                if (kind == CampusSyncClient.Kind.GRADES || kind == CampusSyncClient.Kind.LOGIN) {
                    // 成绩查询或纯自动登录在建立教务根路径与 Cookie 后即可完成，不必再渲染目标页。
                    reading = true;
                    listener.ready();
                } else if (!targetAfterSso) {
                    targetAfterSso = true;
                    web.loadUrl(target());
                }
            } else if (dashboard && !ssoOpened
                    && (autoLogin.attempted()
                    || (!"academic".equals(stage) && elapsed > 300)
                    || elapsed > 1_200)) {
                ssoOpened = true;
                web.loadUrl(SdjtuPortal.ACADEMIC_SSO);
            } else if (dashboard && elapsed > 12_000) {
                requireLogin("未能从门户资源页进入教务系统，请在统一门户中打开「教务系统」");
            } else if (!dashboard && !portalOpened && !autoLogin.attempted() && elapsed > 2_500) {
                openPortal();
            } else {
                schedule(220);
            }
        });
    }

    private void openPortal() {
        portalOpened = true;
        targetAfterSso = false;
        web.loadUrl(SdjtuPortal.LOGIN);
    }

    /** Some installations serve the login/redirect page under the timetable URL with HTTP 200. */
    boolean reopenPortalForMissingTable() {
        if (!current() || portalOpened || autoLogin.attempted()) return false;
        reading = false;
        openPortal();
        return true;
    }

    void navigationError(String url, int status) {
        if (!current() || url == null || !url.equals(web.getUrl())) return;
        if (!portalOpened && !autoLogin.attempted() && !SdjtuPortal.academicBase(url).isEmpty()) {
            openPortal();
        } else if (status == 401) {
            requireLogin("门户需要重新登录，请打开统一门户继续");
        } else {
            listener.fail(status > 0 ? "学校门户返回 HTTP " + status + "，请稍后重试"
                    : "无法连接学校门户，请检查网络后重试", false);
        }
    }

    private void requireLogin(String message) {
        if (autoLogin.attempted()) autoLogin.pause();
        Prefs.setPortalSession(context, false);
        Prefs.setSessionExpired(context, true);
        listener.fail(message, true);
    }

    void success() {
        String validBase = SdjtuPortal.academicBase(base);
        if (!validBase.isEmpty() && !validBase.equals(Prefs.sdjtuAcademicBase(context))) {
            Prefs.setSdjtuAcademicBase(context, validBase);
        }
        autoLogin.onSuccess();
    }

    void stop() {
        stopped = true;
        autoLogin.stop();
        handler.removeCallbacks(poll);
    }
}
