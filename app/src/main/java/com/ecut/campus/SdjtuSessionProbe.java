package com.ecut.campus;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;

import com.ecut.campus.data.SdjtuPortal;
import com.ecut.campus.data.SdjtuSessionCheck;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.zip.GZIPInputStream;

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
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
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
        new Thread(() -> {
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
            MAIN.post(() -> {
                for (Consumer<SdjtuSessionCheck.State> cb : callbacks) {
                    cb.accept(state);
                }
            });
        }, "sdjtu-session-probe").start();
    }

    private static SdjtuSessionCheck.State probeBlocking(Context ctx) {
        if (!hasSchoolCookie(ctx)) {
            return SdjtuSessionCheck.State.EXPIRED;
        }
        String url = SdjtuPortal.studentHome(Prefs.sdjtuAcademicBase(ctx));
        if (!SdjtuPortal.isSchoolUrl(url)) {
            url = SdjtuPortal.STUDENT_HOME;
        }
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpURLConnection conn = null;
            try {
                String cookie = CookieManager.getInstance().getCookie(url);
                if (cookie == null || cookie.trim().isEmpty()) {
                    return SdjtuSessionCheck.State.EXPIRED;
                }
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("Cookie", cookie);
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8");
                conn.setRequestProperty("Accept-Encoding", "gzip");

                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    String next = resolveUrl(url, loc);
                    if (next == null || !SdjtuPortal.isSchoolUrl(next)
                            || SdjtuSessionCheck.isLoginLocation(next)) {
                        return SdjtuSessionCheck.State.EXPIRED;
                    }
                    url = next;
                    continue;
                }
                String body = code == 200 ? readResponse(conn, MAX_PROBE_BYTES) : "";
                return SdjtuSessionCheck.classifyStudentHome(code, null, body);
            } catch (Throwable ignored) {
                return SdjtuSessionCheck.State.UNKNOWN;
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return SdjtuSessionCheck.State.EXPIRED;
    }

    static String resolveUrl(String base, String location) {
        if (location == null || location.trim().isEmpty()) return null;
        try {
            return URI.create(base).resolve(location.trim()).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    static String readResponse(HttpURLConnection conn, int maxBytes) throws IOException {
        InputStream raw = conn.getErrorStream();
        if (raw == null) raw = conn.getInputStream();
        if (raw == null) return "";
        String enc = conn.getContentEncoding();
        boolean gzip = enc != null && enc.toLowerCase(Locale.ROOT).contains("gzip");
        try (InputStream in = gzip ? new GZIPInputStream(raw) : raw) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                int take = maxBytes > 0 ? Math.min(n, maxBytes - total) : n;
                if (take > 0) {
                    out.write(buf, 0, take);
                    total += take;
                }
                if (maxBytes > 0 && total >= maxBytes) break;
            }
            return new String(out.toByteArray(), detectCharset(conn.getContentType()));
        }
    }

    private static Charset detectCharset(String contentType) {
        if (contentType != null) {
            for (String part : contentType.split(";")) {
                String p = part.trim();
                if (p.toLowerCase(Locale.ROOT).startsWith("charset=")) {
                    String name = p.substring("charset=".length()).replace("\"", "").trim();
                    try {
                        return Charset.forName(name);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        return StandardCharsets.UTF_8;
    }
}
