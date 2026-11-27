package com.ecut.campus;

import android.content.Context;
import android.webkit.CookieManager;

import com.ecut.campus.data.SdjtuPortal;
import com.ecut.campus.data.SdjtuSessionCheck;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 轻量级交院 WebVPN / 教务会话存活探测，避免会话失效后设置页仍长时间显示「已登录」。
 */
final class SdjtuSessionProbe {

    static final long RECHECK_INTERVAL_MS = 20_000L;
    static final long STALE_THRESHOLD_MS = 15 * 60_000L;
    static final long OFFLINE_STALE_MS = 2 * 60 * 60_000L;

    private static final int CONNECT_TIMEOUT_MS = 4_000;
    private static final int READ_TIMEOUT_MS = 4_000;
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_PROBE_BYTES = 64 * 1024;

    private static final List<Consumer<SdjtuSessionCheck.State>> WAITERS = new ArrayList<>();
    private static boolean probing;

    private SdjtuSessionProbe() {}

    /** 快速本地检查：WebView Cookie 罐里是否仍有交院 WebVPN 或教务 Cookie。 */
    static boolean hasSchoolCookie(Context ctx) {
        try {
            CookieManager cm = CookieManager.getInstance();
            String loginCookie = cm.getCookie(SdjtuPortal.LOGIN);
            if (loginCookie != null && !loginCookie.trim().isEmpty()) return true;
            String home = SdjtuPortal.studentHome(Prefs.sdjtuAcademicBase(ctx));
            String homeCookie = cm.getCookie(home);
            return homeCookie != null && !homeCookie.trim().isEmpty();
        } catch (Throwable ignored) {
            return true;
        }
    }

    /** 异步探测教务会话是否仍然有效，并发调用自动合并。 */
    static void probeAsync(Context ctx, Consumer<SdjtuSessionCheck.State> onResult) {
        Context app = ctx.getApplicationContext();
        synchronized (WAITERS) {
            if (onResult != null) WAITERS.add(onResult);
            if (probing) return;
            probing = true;
        }
        Bg.run(() -> {
            SdjtuSessionCheck.State state = probeBlocking(app);
            if (state == SdjtuSessionCheck.State.ACTIVE) {
                Prefs.setPortalSession(app, true);
                Prefs.setSessionExpired(app, false);
            } else if (state == SdjtuSessionCheck.State.EXPIRED) {
                Prefs.setPortalSession(app, false);
                Prefs.setSessionExpired(app, true);
            }
            List<Consumer<SdjtuSessionCheck.State>> callbacks;
            synchronized (WAITERS) {
                callbacks = new ArrayList<>(WAITERS);
                WAITERS.clear();
                probing = false;
            }
            Bg.main(() -> {
                for (Consumer<SdjtuSessionCheck.State> cb : callbacks) {
                    cb.accept(state);
                }
            });
        });
    }

    private static SdjtuSessionCheck.State probeBlocking(Context ctx) {
        if (!hasSchoolCookie(ctx)) {
            return SdjtuSessionCheck.State.EXPIRED;
        }
        String url = SdjtuPortal.studentHome(Prefs.sdjtuAcademicBase(ctx));
        if (!SdjtuPortal.isSchoolUrl(url)) {
            url = SdjtuPortal.STUDENT_HOME;
        }
        try {
            Http.Options opts = new Http.Options();
            opts.connectTimeoutMs = CONNECT_TIMEOUT_MS;
            opts.readTimeoutMs = READ_TIMEOUT_MS;
            opts.maxRedirects = MAX_REDIRECTS;
            opts.maxBytes = MAX_PROBE_BYTES;
            opts.userAgent = Http.MOBILE_UA;
            opts.headers.put("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8");
            opts.cookieProvider = u -> CookieManager.getInstance().getCookie(u);
            opts.redirectValidator = next -> SdjtuPortal.isSchoolUrl(next)
                    && !SdjtuSessionCheck.isLoginLocation(next);

            Http.Response resp = Http.get(url, opts);
            if (resp.missingCookie || resp.redirectBlocked || resp.tooManyRedirects) {
                return SdjtuSessionCheck.State.EXPIRED;
            }
            return SdjtuSessionCheck.classifyStudentHome(resp.code, null, resp.code == 200 ? resp.body : "");
        } catch (Throwable ignored) {
            return SdjtuSessionCheck.State.UNKNOWN;
        }
    }
}
