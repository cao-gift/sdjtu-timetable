package com.ecut.campus;

import android.content.Context;
import android.webkit.CookieManager;

import com.ecut.campus.data.Grades;
import com.ecut.campus.data.QiangzhiGrades;
import com.ecut.campus.data.SdjtuPortal;
import com.ecut.campus.data.SdjtuSessionCheck;

import java.io.IOException;
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

    private SdjtuDirectGrades() {}

    static void fetchAsync(Context ctx, String academicBase, AtomicBoolean cancelled, Consumer<Outcome> onResult) {
        Context app = ctx.getApplicationContext();
        Bg.run(() -> {
            Outcome out;
            try {
                out = fetchBlocking(app, academicBase, cancelled);
            } catch (Throwable t) {
                out = new Outcome(Status.FALLBACK, null, t.getClass().getSimpleName());
            }
            if (cancelled.get()) return;
            Outcome finalOut = out;
            Bg.main(() -> {
                if (!cancelled.get()) onResult.accept(finalOut);
            });
        });
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
        if (cancelled.get()) return new PageResponse(null, startUrl, false, "cancelled");
        byte[] payload = QiangzhiGrades.pageQueryBody(page).getBytes(StandardCharsets.UTF_8);
        try {
            Http.Options opts = new Http.Options();
            opts.connectTimeoutMs = CONNECT_TIMEOUT_MS;
            opts.readTimeoutMs = READ_TIMEOUT_MS;
            opts.maxRedirects = MAX_REDIRECTS;
            opts.maxBytes = MAX_RESPONSE_BYTES;
            opts.userAgent = Http.MOBILE_UA;
            opts.headers.put("Referer", referer);
            opts.headers.put("Origin", "https://wwebvpn.sdjtu.edu.cn");
            opts.headers.put("Accept", "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8");
            opts.cookieProvider = u -> CookieManager.getInstance().getCookie(u);
            opts.redirectValidator = next -> SdjtuPortal.isSchoolUrl(next)
                    && !SdjtuSessionCheck.isLoginLocation(next)
                    && next.contains("/jsxsd/kscj/");

            Http.Response resp = Http.postForm(startUrl, payload, opts);
            if (resp.missingCookie) return new PageResponse(null, resp.finalUrl, true, "no_cookie");
            if (resp.redirectBlocked) return new PageResponse(null, resp.finalUrl, true, "redirect_login");
            if (resp.tooManyRedirects) return new PageResponse(null, resp.finalUrl, true, "too_many_redirects");
            if (resp.code == 401 || resp.code == 403) {
                return new PageResponse(null, resp.finalUrl, true, "http_" + resp.code);
            }
            if (resp.code != 200) {
                return new PageResponse(null, resp.finalUrl, false, "http_" + resp.code);
            }
            return new PageResponse(resp.body, resp.finalUrl, false, "ok");
        } catch (IOException e) {
            return new PageResponse(null, startUrl, false, "io:" + e.getClass().getSimpleName());
        } catch (Throwable t) {
            return new PageResponse(null, startUrl, false, t.getClass().getSimpleName());
        }
    }
}
