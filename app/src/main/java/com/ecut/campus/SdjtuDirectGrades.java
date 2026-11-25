package com.ecut.campus;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;

import com.ecut.campus.data.Grades;
import com.ecut.campus.data.QiangzhiGrades;
import com.ecut.campus.data.SdjtuPortal;
import com.ecut.campus.data.SdjtuSessionCheck;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 复用 WebView Cookie 直接通过 HTTP POST 拉取强智全量成绩（支持多页并发抓取），
 * 省去冷启动 WebView、加载页面静态资源及 GET+POST 双重查库耗时。
 */
final class SdjtuDirectGrades {

    enum Status {
        SUCCESS,
        EMPTY_CONFIRMED,
        NO_PERMISSION,
        EXPIRED,
        FALLBACK
    }

    static final class Outcome {
        final Status status;
        final Grades grades;
        final String detail;

        Outcome(Status status, Grades grades, String detail) {
            this.status = status;
            this.grades = grades;
            this.detail = detail == null ? "" : detail;
        }
    }

    private static final int CONNECT_TIMEOUT_MS = 8_000;
    private static final int READ_TIMEOUT_MS = 12_000;
    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_RESPONSE_BYTES = 2 * 1024 * 1024;
    private static final int MAX_PAGE_THREADS = 4;
    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private SdjtuDirectGrades() {}

    static void fetchAsync(Context ctx, String academicBase, AtomicBoolean cancelled, Consumer<Outcome> onResult) {
        Context app = ctx.getApplicationContext();
        new Thread(() -> {
            Outcome out;
            try {
                out = fetchBlocking(app, academicBase, cancelled);
            } catch (Throwable t) {
                out = new Outcome(Status.FALLBACK, null, t.getClass().getSimpleName());
            }
            if (cancelled.get()) return;
            Outcome finalOut = out;
            MAIN.post(() -> {
                if (!cancelled.get()) onResult.accept(finalOut);
            });
        }, "sdjtu-direct-grades").start();
    }

    private static Outcome fetchBlocking(Context ctx, String academicBase, AtomicBoolean cancelled) {
        String validBase = SdjtuPortal.academicBase(academicBase);
        if (validBase.isEmpty()) {
            String home = SdjtuPortal.studentHome(Prefs.sdjtuAcademicBase(ctx));
            validBase = SdjtuPortal.academicBase(SdjtuPortal.isSchoolUrl(home) ? home : SdjtuPortal.STUDENT_HOME);
        }
        String targetUrl = validBase + QiangzhiGrades.GRADES_PATH;
        if (!SdjtuPortal.isSchoolUrl(targetUrl)) {
            return new Outcome(Status.FALLBACK, null, "invalid_base");
        }
        String referer = validBase + "framework/xsMain.jsp";
        PageResponse first = postPage(targetUrl, referer, 1, cancelled);
        if (cancelled.get()) return new Outcome(Status.FALLBACK, null, "cancelled");
        if (first.expired) return new Outcome(Status.EXPIRED, null, first.reason);
        if (first.body == null) return new Outcome(Status.FALLBACK, null, first.reason);

        String table1 = QiangzhiGrades.extractDataListTable(first.body);
        if (table1.isEmpty()) {
            if (QiangzhiGrades.isNoPermission(first.body)) {
                return new Outcome(Status.NO_PERMISSION, null, "no_permission");
            }
            if (SdjtuSessionCheck.isLoginBody(first.body)) {
                return new Outcome(Status.EXPIRED, null, "login_body");
            }
            return new Outcome(Status.FALLBACK, null, "no_table");
        }

        int totalPages = QiangzhiGrades.totalPages(first.body);
        String combinedHtml;
        if (totalPages <= 1) {
            combinedHtml = table1;
        } else {
            combinedHtml = fetchRemainingPages(first.finalUrl, referer, table1, totalPages, cancelled);
            if (combinedHtml == null) {
                return new Outcome(Status.FALLBACK, null, "pagination_fallback");
            }
        }

        Grades parsed = QiangzhiGrades.parseHtml(combinedHtml);
        if (parsed.totalCourseCount() > 0) {
            return new Outcome(Status.SUCCESS, parsed,
                    "direct_ok pages=" + totalPages + " courses=" + parsed.totalCourseCount());
        }
        if (QiangzhiGrades.hasValidHeader(table1)) {
            return new Outcome(Status.EMPTY_CONFIRMED, parsed, "empty_confirmed");
        }
        return new Outcome(Status.FALLBACK, null, "parse_empty");
    }

    private static String fetchRemainingPages(String url, String referer, String table1,
                                              int totalPages, AtomicBoolean cancelled) {
        int remaining = totalPages - 1;
        ExecutorService pool = Executors.newFixedThreadPool(Math.min(MAX_PAGE_THREADS, remaining));
        try {
            List<Future<PageResponse>> futures = new ArrayList<>(remaining);
            for (int pg = 2; pg <= totalPages; pg++) {
                final int pageNum = pg;
                futures.add(pool.submit(() -> postPage(url, referer, pageNum, cancelled)));
            }
            StringBuilder merged = new StringBuilder(table1);
            Set<String> seen = new HashSet<>();
            seen.add(table1);
            for (Future<PageResponse> f : futures) {
                if (cancelled.get()) return null;
                PageResponse resp = f.get();
                if (resp == null || resp.expired || resp.body == null) return null;
                String tbl = QiangzhiGrades.extractDataListTable(resp.body);
                if (tbl.isEmpty()) return null;
                if (seen.add(tbl)) {
                    merged.append('\n').append(tbl);
                }
            }
            return merged.toString();
        } catch (Throwable ignored) {
            return null;
        } finally {
            pool.shutdownNow();
        }
    }

    private static final class PageResponse {
        final String body;
        final String finalUrl;
        final boolean expired;
        final String reason;

        private PageResponse(String body, String finalUrl, boolean expired, String reason) {
            this.body = body;
            this.finalUrl = finalUrl;
            this.expired = expired;
            this.reason = reason;
        }
    }

    private static PageResponse postPage(String startUrl, String referer, int page, AtomicBoolean cancelled) {
        String currentUrl = startUrl;
        String method = "POST";
        byte[] payload = QiangzhiGrades.pageQueryBody(page).getBytes(StandardCharsets.UTF_8);

        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (cancelled.get()) return new PageResponse(null, currentUrl, false, "cancelled");
            HttpURLConnection conn = null;
            try {
                String cookie = CookieManager.getInstance().getCookie(currentUrl);
                if (cookie == null || cookie.trim().isEmpty()) {
                    return new PageResponse(null, currentUrl, true, "no_cookie");
                }
                conn = (HttpURLConnection) new URL(currentUrl).openConnection();
                conn.setRequestMethod(method);
                conn.setInstanceFollowRedirects(false);
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setRequestProperty("Cookie", cookie);
                conn.setRequestProperty("User-Agent", USER_AGENT);
                conn.setRequestProperty("Referer", referer);
                conn.setRequestProperty("Origin", "https://wwebvpn.sdjtu.edu.cn");
                conn.setRequestProperty("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8");
                conn.setRequestProperty("Accept-Encoding", "gzip");
                if ("POST".equals(method)) {
                    conn.setDoOutput(true);
                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    conn.setFixedLengthStreamingMode(payload.length);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(payload);
                    }
                }

                int code = conn.getResponseCode();
                if (code == 401 || code == 403) {
                    return new PageResponse(null, currentUrl, true, "http_" + code);
                }
                if (code >= 300 && code < 400) {
                    String next = SdjtuSessionProbe.resolveUrl(currentUrl, conn.getHeaderField("Location"));
                    if (next == null || !SdjtuPortal.isSchoolUrl(next)
                            || SdjtuSessionCheck.isLoginLocation(next)
                            || !next.contains("/jsxsd/kscj/")) {
                        return new PageResponse(null, currentUrl, true, "redirect_login");
                    }
                    currentUrl = next;
                    if (code != 307 && code != 308) method = "GET";
                    continue;
                }
                if (code != 200) {
                    return new PageResponse(null, currentUrl, false, "http_" + code);
                }
                String body = SdjtuSessionProbe.readResponse(conn, MAX_RESPONSE_BYTES);
                return new PageResponse(body, currentUrl, false, "ok");
            } catch (IOException e) {
                return new PageResponse(null, currentUrl, false, "io:" + e.getClass().getSimpleName());
            } catch (Throwable t) {
                return new PageResponse(null, currentUrl, false, t.getClass().getSimpleName());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return new PageResponse(null, currentUrl, true, "too_many_redirects");
    }
}
