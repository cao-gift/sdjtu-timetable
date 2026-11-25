package com.ecut.campus;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.webkit.CookieManager;
import android.net.http.SslError;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.SslErrorHandler;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.ecut.campus.data.Grades;
import com.ecut.campus.data.GradesStore;
import com.ecut.campus.data.SdjtuPortal;
import com.ecut.campus.data.QiangzhiGrades;
import com.ecut.campus.data.QiangzhiSource;
import com.ecut.campus.data.TermStartProbe;
import com.ecut.campus.data.SemesterArchive;
import com.ecut.campus.data.Timetable;
import com.ecut.campus.data.SemesterMetaPolicy;
import com.ecut.campus.data.TimetableStore;
import com.ecut.campus.data.SyncDiff;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 使用山东交通学院 WebVPN 会话同步强智课表和成绩。 */
public final class CampusSyncClient {

    public enum Kind {
        TIMETABLE("课表"),
        GRADES("成绩");

        final String label;

        Kind(String label) {
            this.label = label;
        }
    }

    /** 最近一次当前学期课表同步的变化摘要（UI 取走后清空）。 */
    public static volatile String lastTimetableDiff = "";

    public interface Callback {
        void onSuccess(int count);
        void onFailure(String message);
        default void onLoginRequired(String message) { onFailure(message); }
    }

    private static final String TAG = "CampusSync";
    private static final long SDJTU_NAVIGATION_TIMEOUT_MS = 65_000L;
    private static final long DATA_TIMEOUT_MS = 20_000L;
    private final Context appContext;
    private final java.util.function.Supplier<WebView> webViewFactory;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Kind kind;
    private final Callback callback;
    private final String targetXnm;
    private final String targetXqm;
    private final java.util.concurrent.atomic.AtomicBoolean directCancelled =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private WebView webView;
    private SdjtuSyncSession sdjtu;
    private boolean webViewStarted;
    private boolean finished;
    private boolean dataRequested;

    private final Runnable timeout = () -> fail("同步超时，请确认统一门户登录仍有效");

    private CampusSyncClient(Context ctx, WebView existingView,
                             java.util.function.Supplier<WebView> webViewFactory,
                             Kind kind, String xnm, String xqm, Callback callback) {
        this.appContext = ctx.getApplicationContext();
        this.webView = existingView;
        this.webViewFactory = webViewFactory;
        this.kind = kind;
        this.callback = callback;
        this.targetXnm = xnm;
        this.targetXqm = xqm;
    }

    /** 必须在主线程调用，并先把 WebView 附着到当前 Activity 的不可见容器。 */
    public static CampusSyncClient start(WebView view, Kind kind, Callback callback) {
        return start(view, kind, null, null, callback);
    }

    /**
     * 延迟创建 WebView 的同步入口：成绩同步在会话仍有效时优先走直连 HTTP 通道，
     * 命中则完全免去 WebView 冷启动与页面渲染开销；仅在需要登录或兜底时才调用工厂创建 WebView。
     */
    public static CampusSyncClient startLazy(Context ctx, java.util.function.Supplier<WebView> webViewFactory,
                                             Kind kind, Callback callback) {
        CampusSyncClient client = new CampusSyncClient(ctx, null, webViewFactory, kind, null, null, callback);
        DiagLog.log(ctx, "sync", "开始同步 " + kind.label + " · " + SdjtuPortal.NAME);
        client.load();
        return client;
    }

    /**
     * 指定学年学期同步。xnm/xqm 为教务参数（如 2025 / 3），
     * 传 null 时跟随教务页面当前选中的学期。
     */
    public static CampusSyncClient start(WebView view, Kind kind,
                                         String xnm, String xqm, Callback callback) {
        CampusSyncClient client = new CampusSyncClient(view.getContext(), view, null, kind, xnm, xqm, callback);
        DiagLog.log(view.getContext(), "sync", "开始同步 " + kind.label
                + (xnm != null ? " xnm=" + xnm + " xqm=" + xqm : "")
                + " · " + SdjtuPortal.NAME);
        client.load();
        return client;
    }

    static boolean sdjtuLoginPaused(Context context) {
        return SdjtuSyncSession.loginPaused(context);
    }

    public boolean isRunning() {
        return !finished;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configure() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(false);
        // 将 WebVPN 门户资源卡的 target=_blank 与 window.open 直接落在同一隐藏会话中，继续经 shouldOverrideUrlLoading 校验。
        s.setSupportMultipleWindows(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));
        s.setSavePassword(false);
        s.setSaveFormData(false);
        if (android.os.Build.VERSION.SDK_INT >= 26)
            webView.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
        android.util.DisplayMetrics metrics = webView.getResources().getDisplayMetrics();
        // The host clips this invisible child. Give portal forms and cards a usable viewport.
        ViewGroup.LayoutParams layout = webView.getLayoutParams();
        if (layout != null) {
            layout.width = metrics.widthPixels;
            layout.height = metrics.heightPixels;
            webView.setLayoutParams(layout);
        } else {
            webView.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY));
            webView.layout(0, 0, metrics.widthPixels, metrics.heightPixels);
        }
        webView.setFocusable(false);
        webView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                if (!finished) sdjtu.pageStarted(url);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                if (!finished) sdjtu.pageFinished(url);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                if (!finished && request.isForMainFrame()) sdjtu.navigationError(request.getUrl().toString(), 0);
            }

            @Override
            public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
                if (!finished && request.isForMainFrame() && response.getStatusCode() >= 400) {
                    sdjtu.navigationError(request.getUrl().toString(), response.getStatusCode());
                }
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler ssl, SslError error) {
                ssl.cancel();
                if (!finished) fail("学校门户证书验证失败，请稍后重试");
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if ("about:blank".equals(url) && !request.isForMainFrame()) return false;
                if (SdjtuPortal.isSchoolUrl(url)) return false;
                if (request.isForMainFrame() && !finished && sdjtu.current()) {
                    fail("门户跳转到无法用于同步的地址，请打开统一门户核对", true);
                }
                return true;
            }
        });

        webView.setWebChromeClient(new WebChromeClient());
    }

    private void load() {
        if (kind == Kind.GRADES
                && Prefs.hasPortalSession(appContext)
                && !Prefs.sessionExpired(appContext)
                && SdjtuSessionProbe.hasSchoolCookie(appContext)) {
            handler.postDelayed(timeout, DATA_TIMEOUT_MS);
            SdjtuDirectGrades.fetchAsync(appContext, Prefs.sdjtuAcademicBase(appContext),
                    directCancelled, outcome -> {
                        if (finished) return;
                        dlog("GRADE_DIRECT(pre): " + outcome.status + " " + outcome.detail);
                        switch (outcome.status) {
                            case SUCCESS:
                                saveGrades(outcome.grades);
                                return;
                            case EMPTY_CONFIRMED:
                                fail("成绩表是空的：这个账号暂时还没有成绩", false);
                                return;
                            case NO_PERMISSION:
                                fail("教务系统没开放成绩查询（可能评教没做完或查询未开放），在教务网页里能查到后再同步", false);
                                return;
                            case EXPIRED:
                                Prefs.setPortalSession(appContext, false);
                                Prefs.setSessionExpired(appContext, true);
                                if (WebShellActivity.needsManualLoginFirst(appContext)) {
                                    fail("教务登录已失效，请重新登录统一门户", true);
                                    return;
                                }
                                startWebViewSync();
                                return;
                            default:
                                startWebViewSync();
                        }
                    });
            return;
        }
        startWebViewSync();
    }

    private void startWebViewSync() {
        if (finished || webViewStarted) return;
        webViewStarted = true;
        if (webView == null && webViewFactory != null) {
            webView = webViewFactory.get();
        }
        if (webView == null) {
            cancel();
            return;
        }
        sdjtu = new SdjtuSyncSession(webView, handler, kind, new SdjtuSyncSession.Listener() {
            @Override public void ready() { requestData(); }
            @Override public void fail(String message, boolean loginRequired) {
                CampusSyncClient.this.fail(message, loginRequired);
            }
        });
        configure();
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, SDJTU_NAVIGATION_TIMEOUT_MS);
        handler.post(() -> { if (!finished && sdjtu != null) sdjtu.start(Prefs.sdjtuAcademicBase(appContext)); });
    }

    private void requestData() {
        if (dataRequested) return;
        dataRequested = true;
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(timeout, DATA_TIMEOUT_MS);

        if (kind == Kind.TIMETABLE) {
            extractTimetable();
        } else {
            extractGrades();
        }
    }

    private void extractTimetable() {
        String js = QiangzhiSource.extractionJs(targetXnm, targetXqm);
        webView.evaluateJavascript(js, raw -> {
            if (finished || !sdjtu.current()) return;
            String text = WebShellActivity.unwrapJsString(raw);
            if (text == null || text.isEmpty() || text.startsWith("ERR:")) {
                fail("课表接口没有返回数据");
                return;
            }
            if (text.startsWith("NOTABLE") && retrySdjtuAfterMissingTable()) return;
            QiangzhiSource.Result result = QiangzhiSource.parseHtml(text);
            DiagLog.log(appContext, "sync", "强智课表解析: 学期="
                    + result.timetable.semester.id
                    + " 原始=" + result.rawRecords + " 课程=" + result.courseCount
                    + " 周次失败=" + result.failedWeekExpr + " 合并重复=" + result.mergedDuplicates
                    + (result.warnings.isEmpty() ? "" : " " + result.warnings));
            if (!result.ok()) {
                String msg = text.startsWith("NOTABLE") ? "没找到课表，可能需要重新登录教务系统"
                        : "课表解析为空，请稍后重试";
                captureParseSnapshotThenFail(msg, text.startsWith("NOTABLE"), text);
                return;
            }
            Timetable currentActive = (targetXnm == null || targetXnm.isEmpty())
                    ? TimetableStore.load(appContext) : null;
            String activeId = currentActive != null ? currentActive.semester.id
                    : SemesterArchive.activeSemesterId(appContext);
            QiangzhiSource.SyncTarget target = QiangzhiSource.resolveSyncTarget(
                    targetXnm, targetXqm, result.timetable.semester.id, activeId, currentCalendarTerm());
            if (target.action == QiangzhiSource.SyncTarget.Action.SKIP) {
                DiagLog.log(appContext, "sync", "课表跳过保存: " + target.skipReason);
                fail(target.skipReason);
                return;
            }
            probeTermStartThenSave(result, target);
        });
    }

    private static String currentCalendarTerm() {
        java.util.Calendar cal = java.util.Calendar.getInstance();
        return HistorySync.currentTermId(cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH) + 1);
    }

    /** 还没有开学日期时，先从教务推出第 1 周周一，再落盘；探测失败不影响课表保存。 */
    private void probeTermStartThenSave(QiangzhiSource.Result result, QiangzhiSource.SyncTarget target) {
        if (SemesterMetaPolicy.fillSdjtuStartDate(result.timetable)) {
            saveTimetable(result, target);
            return;
        }
        String semId = target.semesterId;
        Timetable local = SemesterArchive.loadSemester(appContext, semId);
        boolean localHasStart = local != null && local.semester != null
                && semId.equals(local.semester.id)
                && local.semester.startDate != null && !local.semester.startDate.isEmpty();
        if (localHasStart) {
            saveTimetable(result, target);
            return;
        }
        webView.evaluateJavascript(TermStartProbe.probeJs(semId), raw -> {
            if (finished) return;
            String monday = TermStartProbe.detect(WebShellActivity.unwrapJsString(raw),
                    WeekMath.todayDateLabel());
            dlog("开学日探测: " + (monday.isEmpty() ? "没找到" : monday));
            if (!monday.isEmpty()) result.timetable.semester.startDate = monday;
            saveTimetable(result, target);
        });
    }

    private void saveTimetable(QiangzhiSource.Result result, QiangzhiSource.SyncTarget target) {
        if (finished || !sdjtu.current()) return;
        if (target == null || target.action == QiangzhiSource.SyncTarget.Action.SKIP
                || target.semesterId.isEmpty()) {
            fail(target != null && !target.skipReason.isEmpty() ? target.skipReason : "课表学期无效，已跳过保存");
            return;
        }
        Timetable merged;
        if (target.action == QiangzhiSource.SyncTarget.Action.SAVE_ARCHIVE) {
            // 历史学期：合并进归档里该学期的既有数据，不动当前学期。
            Timetable existing = SemesterArchive.loadSemester(appContext, target.semesterId);
            merged = TimetableStore.merge(
                    existing != null ? existing : new Timetable(), result.timetable);
            if (!SemesterArchive.saveSemester(appContext, merged, false)) {
                fail("历史课表写入归档失败");
                return;
            }
        } else {
            Timetable currentActive = TimetableStore.load(appContext);
            Timetable before = target.semesterId.equals(currentActive.semester.id)
                    ? currentActive : SemesterArchive.loadSemester(appContext, target.semesterId);
            if (before == null) before = new Timetable();
            merged = TimetableStore.merge(before, result.timetable);
            lastTimetableDiff = SyncDiff.summarize(before, merged);
            if (!SemesterArchive.saveSemester(appContext, merged, true)) {
                fail("课表写入本地失败");
                return;
            }
        }
        SemesterArchive.unifyColors(appContext);
        Prefs.setLastSyncTime(appContext, now());
        dlog("Timetable saved: " + merged.courses.size()
                + " courses (" + result.rawRecords + " records)");
        Prefs.setPortalSession(appContext, true);
        finishSuccess(merged.courses.size());
    }

    private void extractGrades() {
        CookieManager.getInstance().flush();
        String activeBase = sdjtu != null ? sdjtu.currentBase() : Prefs.sdjtuAcademicBase(appContext);
        SdjtuDirectGrades.fetchAsync(appContext, activeBase, directCancelled, outcome -> {
            if (finished || (sdjtu != null && !sdjtu.current())) return;
            dlog("GRADE_DIRECT(post): " + outcome.status + " " + outcome.detail);
            switch (outcome.status) {
                case SUCCESS:
                    saveGrades(outcome.grades);
                    return;
                case EMPTY_CONFIRMED:
                    fail("成绩表是空的：这个账号暂时还没有成绩", false);
                    return;
                case NO_PERMISSION:
                    fail("教务系统没开放成绩查询（可能评教没做完或查询未开放），在教务网页里能查到后再同步", false);
                    return;
                default:
                    extractGradesViaWebView();
            }
        });
    }

    private void extractGradesViaWebView() {
        if (finished || webView == null || (sdjtu != null && !sdjtu.current())) return;
        webView.evaluateJavascript(QiangzhiGrades.extractionJs(), raw -> {
            if (finished || (sdjtu != null && !sdjtu.current())) return;
            String html = WebShellActivity.unwrapJsString(raw);
            if ("__NOPERM__".equals(html)) {
                fail("教务系统没开放成绩查询（可能评教没做完或查询未开放），在教务网页里能查到后再同步");
                return;
            }
            if (html != null && html.startsWith("NOTABLE") && retrySdjtuAfterMissingTable()) return;
            if (html == null || html.isEmpty() || html.startsWith("ERR:") || html.startsWith("NOTABLE")) {
                captureParseSnapshotThenFail("没找到成绩表，可能需要重新登录教务系统", true, html);
                return;
            }
            Grades parsed = QiangzhiGrades.parseHtml(html);
            dlog("GRADE_ROWS(qiangzhi): " + html.length() + " chars, courses="
                    + parsed.totalCourseCount() + ", semesters=" + parsed.semesters.size());
            if (parsed.totalCourseCount() <= 0) {
                String msg = html.contains("dataList") ? "成绩表是空的：这个账号暂时还没有成绩"
                        : "成绩解析为空，请稍后重试";
                captureParseSnapshotThenFail(msg, false, html);
                return;
            }
            saveGrades(parsed);
        });
    }

    private boolean retrySdjtuAfterMissingTable() {
        if (sdjtu == null || !sdjtu.reopenPortalForMissingTable()) return false;
        dataRequested = false;
        handler.removeCallbacks(timeout);
        handler.postDelayed(timeout, SDJTU_NAVIGATION_TIMEOUT_MS);
        return true;
    }

    private void saveGrades(Grades parsed) {
        if (finished || (sdjtu != null && !sdjtu.current())) return;
        // 教务成绩是全量权威数据。直接覆盖而不是合并，避免旧演示数据
        // 或已撤销课程永久残留。
        parsed.updatedAt = new SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(new Date());
        parsed.recomputeAll();
        // 0.4：和上一次对比，记下新出的成绩（首次同步不算）
        try {
            Grades before = GradesStore.load(appContext);
            if (!Prefs.isGradesDemo(appContext)) {
                java.util.List<Grades.CourseGrade> fresh =
                        com.ecut.campus.data.GradeDiff.newGrades(before, parsed);
                if (!fresh.isEmpty()) {
                    java.util.Set<String> keys = Prefs.newGradeKeys(appContext);
                    keys.addAll(com.ecut.campus.data.GradeDiff.newKeys(before, parsed));
                    Prefs.setNewGradeKeys(appContext, keys);
                    Notify.newGrades(appContext, fresh);
                }
            } else {
                Prefs.setNewGradeKeys(appContext, java.util.Collections.emptySet());
            }
        } catch (Exception e) {
            Log.w(TAG, "grade diff failed", e);
        }
        if (!GradesStore.save(appContext, parsed)) {
            fail("成绩写入本地失败");
            return;
        }
        Prefs.setGradesDemo(appContext, false);
        Prefs.setLastSyncTime(appContext, now());
        dlog("Grades replaced: " + parsed.totalCourseCount()
                + " courses in " + parsed.semesters.size() + " semesters");
        Prefs.setPortalSession(appContext, true);
        finishSuccess(parsed.totalCourseCount());
    }

    private void finishSuccess(int count) {
        if (finished) return;
        if (sdjtu != null) sdjtu.success();
        finished = true;
        Prefs.setSessionExpired(appContext, false);
        DiagLog.log(appContext, "sync", kind.label + " 成功: " + count);
        if (kind == Kind.TIMETABLE) Prefs.setTimetableSyncedAt(appContext, System.currentTimeMillis());
        cleanup();
        callback.onSuccess(count);
    }

    private void dlog(String m) {
        Log.i(TAG, DiagLog.scrub(m));
        DiagLog.log(appContext, "sync", m);
    }

    /** 解析失败时在释放 WebView 前抓一份脱敏页面结构快照，方便学校改版时直接做成测试用例。 */
    private void captureParseSnapshotThenFail(String message, boolean loginRequired, String rawHint) {
        if (finished) return;
        if (webView == null) {
            DiagLog.saveParseFailureSnapshot(appContext, kind.name(), message, "", rawHint);
            fail(message, loginRequired);
            return;
        }
        try {
            webView.evaluateJavascript(com.ecut.campus.data.PageSample.collectJs(), raw -> {
                if (finished) return;
                String sample = WebShellActivity.unwrapJsString(raw);
                DiagLog.saveParseFailureSnapshot(appContext, kind.name(), message, sample, rawHint);
                fail(message, loginRequired);
            });
        } catch (Throwable t) {
            DiagLog.saveParseFailureSnapshot(appContext, kind.name(), message, "", rawHint);
            fail(message, loginRequired);
        }
    }

    private void fail(String message) {
        fail(message, false);
    }

    private void fail(String message, boolean loginRequired) {
        if (finished) return;
        finished = true;
        Log.w(TAG, kind.label + " failed: " + message);
        DiagLog.log(appContext, "sync", kind.label + " 失败: " + message);
        if (loginRequired || (message != null && message.contains("登录已失效"))) {
            Prefs.setPortalSession(appContext, false);
            Prefs.setSessionExpired(appContext, true);
        }
        cleanup();
        if (loginRequired) callback.onLoginRequired(message);
        else callback.onFailure(message);
    }

    private void cleanup() {
        directCancelled.set(true);
        if (sdjtu != null) sdjtu.stop();
        handler.removeCallbacksAndMessages(null);
        if (webView != null) {
            webView.setWebViewClient(new WebViewClient());
            webView.setWebChromeClient(null);
            webView.stopLoading();
            CookieManager.getInstance().flush();
            if (webView.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) webView.getParent()).removeView(webView);
            }
            webView.loadUrl("about:blank");
            webView.destroy();
            webView = null;
        }
    }

    /** Activity 销毁时静默取消，不触发失败回调，避免向已销毁页面弹 Toast。 */
    public void cancel() {
        if (finished) return;
        finished = true;
        cleanup();
    }

    private static String now() {
        return new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date());
    }
}
