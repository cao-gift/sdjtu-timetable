package com.ecut.campus;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import com.ecut.campus.data.SdjtuPortal;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** 在与统一门户共享 Cookie 的临时 WebView 中完成一次交院教务查询。所有调用在主线程。 */
final class SdjtuStudySync {
    interface Callback {
        void onSuccess(JSONObject data);
        void onFailure(String message, boolean loginRequired);
        void onChoose(String message, JSONObject data);
    }

    private final Activity activity;
    private final ViewGroup host;
    private final SdjtuAcademicPages.Feature feature;
    private final Callback callback;
    private final SdjtuAutoLogin autoLogin;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable inspect = this::inspectDocument;
    private final Runnable retryEntry = this::useNextEntry;
    private final String options;
    private FrameLayout window;
    private WebView web;
    private String install;
    private JSONObject submittedContext;
    private enum Entry { SAVED_PAGE, STUDENT_HOME, PORTAL }
    private Entry entry;
    private boolean finished, submitted, installed, evaluating, sawAcademic, ssoOpened;
    private int document, route;
    private long loginSince, pageSince;

    static SdjtuStudySync start(Activity activity, ViewGroup host, SdjtuAcademicPages.Feature feature,
                                JSONObject query, Callback callback) {
        SdjtuStudySync sync = new SdjtuStudySync(activity, host, feature, query, callback);
        // 即使设备缺少 WebView，回调也在页面保存本次同步引用之后执行。
        sync.handler.post(sync::begin);
        return sync;
    }

    private SdjtuStudySync(Activity activity, ViewGroup host, SdjtuAcademicPages.Feature feature,
                           JSONObject query, Callback callback) {
        this.activity = activity;
        this.host = host;
        this.feature = feature;
        this.options = query == null ? "{}" : query.toString();
        this.callback = callback;
        this.autoLogin = new SdjtuAutoLogin(activity);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void begin() {
        if (!active()) return;
        if (feature == SdjtuAcademicPages.Feature.PORTAL) {
            fail("无法识别教务查询类型", false);
            return;
        }
        if (host.findViewWithTag(SdjtuStudySync.class) != null) {
            fail("当前查询正在同步", false);
            return;
        }
        try {
            install = "document.__lvlvStudySync={portal:" + read("sdjtu-portal.js") + ",reader:"
                    + read(feature.asset) + "};";
            window = new FrameLayout(activity);
            window.setTag(SdjtuStudySync.class);
            window.setAlpha(0f);
            window.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            host.addView(window, 0, new ViewGroup.LayoutParams(1, 1));
            web = new WebView(activity);
            web.setFocusable(false);
            // 保持 VISIBLE 并给网页正常尺寸，否则强智会收起菜单或给结果 iframe 零宽高。
            android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
            window.addView(web, new FrameLayout.LayoutParams(metrics.widthPixels, metrics.heightPixels));
            WebSettings settings = web.getSettings();
            settings.setJavaScriptEnabled(true);
            settings.setDomStorageEnabled(true);
            settings.setUseWideViewPort(true);
            settings.setLoadWithOverviewMode(true);
            settings.setAllowFileAccess(false);
            settings.setAllowContentAccess(false);
            // 将学校菜单的 target=_blank 和 window.open 作为同一窗口的导航，保留表单 POST。
            settings.setSupportMultipleWindows(false);
            settings.setJavaScriptCanOpenWindowsAutomatically(true);
            settings.setUserAgentString(settings.getUserAgentString().replace("; wv", ""));
            CookieManager cookies = CookieManager.getInstance();
            cookies.setAcceptCookie(true);
            cookies.setAcceptThirdPartyCookies(web, true);
            web.setWebChromeClient(new WebChromeClient());
            web.setWebViewClient(new Client());
            String saved = SdjtuStudyRoute.load(activity, feature);
            handler.postDelayed(() -> fail(sawAcademic
                    ? "已进入旧版强智教务，但未取得查询结果，请打开教务原页核对。"
                    : "同步超时，请检查学校门户连接后重试。",
                    !sawAcademic && (!Prefs.hasPortalSession(activity) || Prefs.sessionExpired(activity))), 60_000L);
            if (!Prefs.hasPortalSession(activity) || Prefs.sessionExpired(activity)) {
                loadEntry(Entry.PORTAL, SdjtuPortal.LOGIN);
            } else if (saved != null && !saved.isEmpty()) {
                loadEntry(Entry.SAVED_PAGE, saved);
            } else {
                openStudentHome();
            }
        } catch (IOException | RuntimeException e) {
            fail("无法启动教务同步，请检查系统 WebView 后重试", false);
        }
    }

    private String read(String name) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                activity.getAssets().open(name), StandardCharsets.UTF_8))) {
            StringBuilder result = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) result.append(line).append('\n');
            return result.toString();
        }
    }

    private boolean active() {
        if (finished) return false;
        if (activity.isFinishing() || activity.isDestroyed()) {
            cancel();
            return false;
        }
        return true;
    }

    private boolean allowed(String url) {
        return SdjtuPortal.isSchoolUrl(url);
    }

    private final class Client extends WebViewClient {
        @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (allowed(url) || ("about:blank".equals(url) && !request.isForMainFrame())) return false;
            if (request.isForMainFrame()) fail("学校门户跳转到了无法用于同步的页面", false);
            return true;
        }

        @Override public void onPageStarted(WebView view, String url, Bitmap icon) {
            if (finished) return;
            document++;
            installed = evaluating = false;
            autoLogin.resetPage();
            loginSince = 0;
            pageSince = SystemClock.elapsedRealtime();
            handler.removeCallbacks(inspect);
            if (!allowed(url)) {
                fail("学校门户跳转到了无法用于同步的页面", false);
                return;
            }
            if (SdjtuLoginForm.isLoginPage(url) || SdjtuPortal.isDashboard(url)
                    || !SdjtuPortal.academicBase(url).isEmpty()) {
                schedule(200);
            }
        }

        @Override public void onPageFinished(WebView view, String url) {
            if (!finished && allowed(view.getUrl())) schedule(30);
        }

        @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame()) navigationFailed(request, "无法连接学校教务，请检查网络后重试", false);
        }

        @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
            if (!request.isForMainFrame() || response.getStatusCode() < 400) return;
            boolean login = response.getStatusCode() == 401;
            navigationFailed(request, login ? "请先登录山东交通学院统一门户" :
                    "学校教务返回错误（HTTP " + response.getStatusCode() + "），请稍后重试", login);
        }

        private void navigationFailed(WebResourceRequest request, String message, boolean login) {
            if (finished || !request.getUrl().toString().equals(web.getUrl())) return;
            if (entry != Entry.PORTAL && !submitted) useNextEntry();
            else fail(message, login);
        }
    }

    private void useNextEntry() {
        if (!active() || submitted) return;
        if (entry == Entry.SAVED_PAGE) openStudentHome();
        else if (entry == Entry.STUDENT_HOME) loadEntry(Entry.PORTAL, SdjtuPortal.LOGIN);
    }

    private void openStudentHome() {
        loadEntry(Entry.STUDENT_HOME, SdjtuPortal.studentHome(Prefs.sdjtuAcademicBase(activity)));
    }

    private void loadEntry(Entry next, String url) {
        entry = next;
        route++;
        document++;
        installed = evaluating = false;
        loginSince = 0;
        pageSince = SystemClock.elapsedRealtime();
        handler.removeCallbacks(inspect);
        handler.removeCallbacks(retryEntry);
        if (next != Entry.PORTAL) handler.postDelayed(retryEntry, next == Entry.SAVED_PAGE ? 8_000L : 14_000L);
        web.loadUrl(url);
    }

    private void schedule(long delay) {
        handler.removeCallbacks(inspect);
        handler.postDelayed(inspect, delay);
    }

    private void inspectDocument() {
        String url = web == null ? null : web.getUrl();
        if (!active() || evaluating || !allowed(url)) return;
        if (autoLogin.credentialsChanged()) {
            fail("保存的门户账号已更改，请重新同步", false);
            return;
        }
        int currentDocument = document, currentRoute = route;
        if (SdjtuLoginForm.isLoginPage(url)) {
            handler.removeCallbacks(retryEntry);
            evaluating = true;
            autoLogin.inspectAndSubmit(web, pageSince, () -> {
                if (!active() || currentRoute != route || currentDocument != document) return false;
                if (url.equals(web.getUrl())) return true;
                evaluating = false;
                schedule(80);
                return false;
            }, new SdjtuAutoLogin.Callback() {
                        @Override public void waitMore(long delayMs) {
                            evaluating = false;
                            schedule(delayMs);
                        }

                        @Override public void manual(String message) {
                            evaluating = false;
                            fail(message, true);
                        }

                        @Override public void busy(String message) {
                            evaluating = false;
                            fail(message, false);
                        }
                    });
            return;
        }
        try {
            JSONObject request = new JSONObject(options);
            String mode;
            if (feature == SdjtuAcademicPages.Feature.EXAMS) {
                mode = "early".equals(request.optString("examKind")) ? "examEarly" : "examFinal";
            } else {
                mode = feature == SdjtuAcademicPages.Feature.ROOMS ? "rooms" : "plan";
            }
            request.put("sync", true);
            request.put("submitted", submitted);
            if (submittedContext != null) request.put("context", submittedContext);
            String arguments = request.toString().replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
            String js = "(function(){if(document.readyState==='loading'||!document.body)return {};"
                    + "if(location.protocol!=='https:'||!/(^|\\.)sdjtu\\.edu\\.cn$/i"
                    + ".test(location.hostname))return {};"
                    + (installed ? "" : install)
                    + "var a=document.__lvlvStudySync;return a?a.portal('" + mode
                    + "',true,false,a.reader," + arguments + "):{stage:'install'};})()";
            evaluating = true;
            web.evaluateJavascript(js, raw -> {
                if (!active() || currentRoute != route) return;
                JSONObject result;
                try {
                    result = new JSONObject(WebShellActivity.unwrapJsString(raw));
                } catch (JSONException ignored) {
                    result = new JSONObject();
                }
                String stage = result.optString("stage");
                // 点击查询可能立即替换顶层文档。该提交针对整轮同步，只确认一次。
                if ("submitted".equals(stage) || (feature == SdjtuAcademicPages.Feature.EXAMS && "examPage".equals(stage))) {
                    submitted = true;
                    JSONObject context = result.optJSONObject("data");
                    if (context != null) submittedContext = context;
                    handler.removeCallbacks(retryEntry);
                }
                if (currentDocument != document) return;
                evaluating = false;
                installed = result.length() > 0 && !"install".equals(stage);
                accept(result, stage);
            });
        } catch (JSONException | RuntimeException e) {
            fail("无法读取教务查询条件或结果", false);
        }
    }

    private void accept(JSONObject result, String stage) {
        if ("login".equals(stage)) {
            long now = SystemClock.elapsedRealtime();
            if (loginSince == 0) loginSince = now;
            // WebVPN 会在指纹页与 CAS 之间自动跳转，稳定停留在登录墙才要求操作。
            String currentUrl = web == null ? "" : web.getUrl();
            boolean fingerprint = currentUrl != null
                    && currentUrl.toLowerCase(java.util.Locale.ROOT).contains("fingerprint");
            long grace = autoLogin.attempted() ? 10_000L : (fingerprint ? 8_000L : 1_500L);
            if (now - loginSince >= grace) {
                if (entry != Entry.PORTAL && !submitted) useNextEntry();
                else fail("请先登录山东交通学院统一门户，完成验证后再同步", true);
                return;
            }
        } else {
            loginSince = 0;
        }
        String base = SdjtuPortal.academicBase(result.optString("base"));
        long elapsed = SystemClock.elapsedRealtime() - pageSince;
        if (!base.isEmpty()) {
            sawAcademic = true;
            autoLogin.onSuccess();
            if (!base.equals(Prefs.sdjtuAcademicBase(activity))) Prefs.setSdjtuAcademicBase(activity, base);
        } else if (SdjtuPortal.isDashboard(web.getUrl()) && !"login".equals(stage) && !"error".equals(stage)
                && !stage.isEmpty() && !ssoOpened
                && (autoLogin.attempted()
                || (!"academic".equals(stage) && elapsed > 300L)
                || elapsed > 1_200L)) {
            ssoOpened = true;
            handler.removeCallbacks(retryEntry);
            web.loadUrl(SdjtuPortal.ACADEMIC_SSO);
            return;
        }
        SdjtuStudyRoute.remember(activity, feature, result.optString("entryPage"));
        JSONObject data = result.optJSONObject("data");
        switch (stage) {
            case "done":
                if (!SdjtuStudyStore.valid(feature, data)) {
                    fail("教务返回的数据格式暂时无法识别", false);
                    return;
                }
                Prefs.setPortalSession(activity, true);
                Prefs.setSessionExpired(activity, false);
                CookieManager.getInstance().flush();
                cancel();
                callback.onSuccess(data);
                return;
            case "choose":
                if (data == null) {
                    fail("未能读取学校提供的查询选项", false);
                } else {
                    cancel();
                    callback.onChoose(result.optString("message","请选择查询条件后同步"), data);
                }
                return;
            case "error":
                if (entry == Entry.SAVED_PAGE && !submitted) useNextEntry();
                else fail(result.optString("message","教务暂未返回可识别的结果"), false);
                return;
            default:
                schedule(stage.isEmpty() ? 150 : 220);
        }
    }

    private void fail(String message, boolean loginRequired) {
        if (finished) return;
        if (loginRequired) Prefs.setSessionExpired(activity, true);
        cancel();
        callback.onFailure(message, loginRequired);
    }

    String submittedTerm() {
        return submittedContext == null ? "" : submittedContext.optString("term");
    }

    void cancel() {
        if (finished) return;
        finished = true;
        autoLogin.stop();
        handler.removeCallbacksAndMessages(null);
        if (web != null) {
            web.setWebViewClient(new WebViewClient());
            web.setWebChromeClient(null);
            web.stopLoading();
            window.removeView(web);
            web.destroy();
            web = null;
        }
        if (window != null) host.removeView(window);
    }
}
