package com.ecut.campus;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.button.MaterialButton;
import com.ecut.campus.data.PageSample;
import com.ecut.campus.data.SemesterArchive;
import com.ecut.campus.data.SyncDiff;
import com.ecut.campus.data.TermStartProbe;
import com.ecut.campus.data.QiangzhiGrades;
import com.ecut.campus.data.QiangzhiSource;
import com.ecut.campus.data.SdjtuPortal;
import com.ecut.campus.data.Timetable;
import com.ecut.campus.data.SemesterMetaPolicy;
import com.ecut.campus.data.TimetableStore;

import org.json.JSONTokener;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 山东交通学院统一门户、教务页面与手动课表导入。 */
public class WebShellActivity extends AppCompatActivity {

    private static final String TAG = "WebShell";
    public static final String EXTRA_TARGET_KEY = "target_key";
    /** 可选：进入页面后在底部浮窗显示的提示。 */
    public static final String EXTRA_HINT = "hint";
    static final String EXTRA_LOCATE_STUDY = "locate_study";
    static final String EXTRA_STUDY_SAVED = "study_saved";
    static final String EXTRA_RETURN_AFTER_SDJTU_LOGIN = "return_after_sdjtu_login";
    static final String EXTRA_LOGIN_PROMPT = "login_prompt";

    static final int REQ_SDJTU_TIMETABLE = 4071;
    static final int REQ_SDJTU_GRADES = 4072;

    static final String MANUAL_PORTAL_PROMPT =
            "登录统一门户后，请点击进入「教务系统」，待教务页面加载完成后再返回应用，登录状态才会生效。";
    private static final String MANUAL_PORTAL_HINT =
            "登录统一门户后，请点击进入「教务系统」，页面加载完成后返回即可。";

    /** 纯逻辑判断：未登录（或会话已过期）且无法使用自动登录时，先走手动登录。 */
    static boolean shouldOpenManualLoginFirst(boolean hasSession, boolean sessionExpired,
                                              boolean hasSavedCredentials, boolean autoLoginPaused) {
        boolean sessionReady = hasSession && !sessionExpired;
        boolean canAutoLogin = hasSavedCredentials && !autoLoginPaused;
        return !sessionReady && !canAutoLogin;
    }

    /** 未登录且无法自动登录时，点击同步应直接引导登录，而不是后台空等失败。 */
    static boolean needsManualLoginFirst(android.content.Context ctx) {
        return shouldOpenManualLoginFirst(
                Prefs.hasPortalSession(ctx),
                Prefs.sessionExpired(ctx),
                SdjtuCredentials.isSaved(ctx),
                SdjtuAutoLogin.isPaused(ctx));
    }

    /** 设置页「手动登录」：先弹框提示操作步骤，再打开统一门户。 */
    static void openManualPortal(Activity caller) {
        if (caller.isFinishing() || caller.isDestroyed()) return;
        new androidx.appcompat.app.AlertDialog.Builder(caller)
                .setTitle("登录提示")
                .setMessage(MANUAL_PORTAL_PROMPT)
                .setNegativeButton("取消", null)
                .setPositiveButton("前往登录", (dialog, which) -> {
                    Intent portal = new Intent(caller, WebShellActivity.class);
                    portal.putExtra(EXTRA_TARGET_KEY, "login");
                    portal.putExtra(EXTRA_HINT, MANUAL_PORTAL_HINT);
                    caller.startActivity(portal);
                }).show();
    }

    /** 同步前需要登录：直接打开统一门户并弹框提示，进入教务系统后自动返回继续同步。 */
    static void startManualLoginForSync(Activity caller, int requestCode, String syncLabel,
                                        @Nullable String reason) {
        if (caller.isFinishing() || caller.isDestroyed()) return;
        String target = (syncLabel == null || syncLabel.isEmpty()) ? "数据" : syncLabel;
        String prompt = (reason != null && !reason.isEmpty() ? reason + "\n\n" : "")
                + "登录统一门户后，请点击进入「教务系统」，页面加载完成后会自动返回并继续同步" + target + "。";
        Intent portal = new Intent(caller, WebShellActivity.class);
        portal.putExtra(EXTRA_TARGET_KEY, "login");
        portal.putExtra(EXTRA_RETURN_AFTER_SDJTU_LOGIN, true);
        portal.putExtra(EXTRA_LOGIN_PROMPT, prompt);
        portal.putExtra(EXTRA_HINT, "登录统一门户后，请点击进入「教务系统」，加载完成将自动返回同步。");
        caller.startActivityForResult(portal, requestCode);
    }

    static void offerSdjtuLogin(Activity caller, CampusSyncClient.Kind kind, String message) {
        if (caller.isFinishing() || caller.isDestroyed()) return;
        new androidx.appcompat.app.AlertDialog.Builder(caller)
                .setTitle("请登录统一门户")
                .setMessage(message + "\n\n登录统一门户后请点击进入「教务系统」，页面加载完成后会自动返回并继续同步" + kind.label + "。")
                .setNegativeButton("稍后", null)
                .setPositiveButton("前往登录", (dialog, which) ->
                        startManualLoginForSync(caller, kind == CampusSyncClient.Kind.TIMETABLE
                                ? REQ_SDJTU_TIMETABLE : REQ_SDJTU_GRADES, kind.label, null))
                .show();
    }

    private WebView webView;
    private ProgressBar progress;
    private TextView webTitle;
    private MaterialButton toggleFitBtn;

    private WebView popupWindow;
    private String targetUrl;
    private ValueCallback<Uri[]> fileCallback;
    private static final int REQ_FILE = 3001;
    private int retryCount = 0;
    private boolean importing = false;
    private SdjtuAcademicPages sdjtuPages;
    private SdjtuAcademicPages.Feature academicFeature = SdjtuAcademicPages.Feature.PORTAL;
    private boolean webResumed;
    private boolean loginForStudySync, locatingStudy, academicNotified;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        G.init(this);
        if (android.os.Build.VERSION.SDK_INT >= 21) {
            getWindow().setStatusBarColor(G.PAPER);
            getWindow().setNavigationBarColor(G.PAPER);
        }
        setContentView(R.layout.activity_web);

        webView = findViewById(R.id.webview);
        progress = findViewById(R.id.progress);
        webTitle = findViewById(R.id.web_title);
        toggleFitBtn = findViewById(R.id.btn_toggle_fit);
        academicFeature = SdjtuAcademicPages.Feature.fromTarget(getIntent().getStringExtra(EXTRA_TARGET_KEY));
        loginForStudySync = getIntent().getBooleanExtra(EXTRA_RETURN_AFTER_SDJTU_LOGIN, false);
        locatingStudy = isAcademicLookup() && getIntent().getBooleanExtra(EXTRA_LOCATE_STUDY, false);
        sdjtuPages = new SdjtuAcademicPages(webView, academicFeature, loginForStudySync, locatingStudy, new SdjtuAcademicPages.Listener() {
            @Override public void onAcademic() {
                CookieManager.getInstance().flush();
                if (loginForStudySync) {
                    setResult(RESULT_OK);
                    if (!academicNotified) {
                        academicNotified = true;
                        Toast.makeText(WebShellActivity.this, "已进入教务系统，正在继续同步…", Toast.LENGTH_SHORT).show();
                    }
                    finish();
                    return;
                }
                if (!academicNotified) {
                    academicNotified = true;
                    View hintBox = findViewById(R.id.web_hint);
                    if (hintBox != null) hintBox.setVisibility(View.GONE);
                    Toast.makeText(WebShellActivity.this, "已进入教务系统，登录状态已保存，现在可以返回了", Toast.LENGTH_LONG).show();
                }
                if (!isAcademicLookup()) findViewById(R.id.btn_import_page).setVisibility(View.VISIBLE);
            }

            @Override public void onStage(String stage) {
                if (locatingStudy) return;
                TextView info = findViewById(R.id.web_hint_text);
                if ("form".equals(stage)) {
                    findViewById(R.id.web_hint).setVisibility(View.GONE);
                } else if ("official".equals(stage)) {
                    info.setText("如有多份培养方案，请先在教务页面选择自己的专业或方案。");
                } else if ("plan".equals(stage) || "results".equals(stage)) {
                    findViewById(R.id.web_hint).setVisibility(View.GONE);
                }
            }
        });
        String key = getIntent().getStringExtra(EXTRA_TARGET_KEY);
        String title = loginForStudySync ? "山东交通学院统一门户"
                : isAcademicLookup() ? academicFeature.title : SdjtuPortal.NAME;
        webTitle.setText(title);
        targetUrl = initialUrl(key);
        String hint = getIntent().getStringExtra(EXTRA_HINT);
        View hintBox = findViewById(R.id.web_hint);
        if (hint != null && !hint.isEmpty() && hintBox != null) {
            ((android.widget.TextView) findViewById(R.id.web_hint_text)).setText(hint);
            hintBox.setVisibility(View.VISIBLE);
            hintBox.setTranslationY(dpPx(120));
            hintBox.animate().translationY(0f).setStartDelay(400).setDuration(320)
                    .setInterpolator(new android.view.animation.PathInterpolator(0.2f, 0f, 0f, 1f)).start();
            findViewById(R.id.web_hint_close).setOnClickListener(v ->
                    hintBox.animate().translationY(hintBox.getHeight() + dpPx(32)).alpha(0f)
                            .setStartDelay(0).setDuration(220)
                            .withEndAction(() -> hintBox.setVisibility(View.GONE)).start());
        }

        View backBtn = findViewById(R.id.btn_web_back);
        if (backBtn != null) {
            backBtn.setOnClickListener(v -> finish());
        }
        View refreshBtn = findViewById(R.id.btn_web_refresh);
        if (refreshBtn != null) {
            refreshBtn.setOnClickListener(v -> {
                if (webView != null) webView.reload();
            });
        }
        if (locatingStudy) {
            toggleFitBtn.setText("读取此页");
            toggleFitBtn.setOnClickListener(v -> captureStudy());
        } else if (isAcademicLookup()) {
            toggleFitBtn.setText(academicFeature == SdjtuAcademicPages.Feature.ROOMS ? "定位查询" : "定位方案");
            toggleFitBtn.setOnClickListener(v -> sdjtuPages.start(true));
        } else {
            toggleFitBtn.setVisibility(View.GONE);
        }
        View importBtn = findViewById(R.id.btn_import_page);
        importBtn.setVisibility(View.GONE);
        importBtn.setOnClickListener(v -> importThisPage());

        configureWebView();
        webView.loadUrl(targetUrl);

        if (savedInstanceState == null) {
            String loginPrompt = getIntent().getStringExtra(EXTRA_LOGIN_PROMPT);
            if (loginPrompt != null && !loginPrompt.isEmpty()) {
                new androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("登录提示")
                        .setMessage(loginPrompt)
                        .setPositiveButton("知道了", null)
                        .show();
            }
        }
    }

    private void captureStudy() {
        toggleFitBtn.setEnabled(false);
        sdjtuPages.capture(result -> {
            if (isFinishing() || isDestroyed()) return;
            toggleFitBtn.setEnabled(true);
            boolean hasEntry = SdjtuStudyRoute.remember(this, academicFeature, result.optString("entryPage"));
            if ("done".equals(result.optString("stage"))) {
                if (SdjtuStudyStore.save(this, academicFeature, result.optJSONObject("data"), System.currentTimeMillis())) {
                    setResult(RESULT_OK, new Intent().putExtra(EXTRA_STUDY_SAVED, true));
                    finish();
                } else Toast.makeText(this, "本次结果未能保存，请重试。", Toast.LENGTH_SHORT).show();
            } else if (hasEntry) {
                setResult(RESULT_OK);
                finish();
            } else {
                Toast.makeText(this, "请先在教务页面打开对应功能并显示查询结果，再点「读取此页」。", Toast.LENGTH_LONG).show();
            }
        });
    }

    private boolean isAcademicLookup() {
        return academicFeature != SdjtuAcademicPages.Feature.PORTAL;
    }

    private String initialUrl(String key) {
        if (locatingStudy) {
            return SdjtuPortal.STUDENT_HOME;
        }
        if (loginForStudySync || "login".equals(key) || "portal".equals(key)) {
            return SdjtuPortal.LOGIN;
        }
        if (Prefs.hasPortalSession(this) && ("timetable".equals(key) || "grades".equals(key))) {
            String base = SdjtuPortal.academicBase(SdjtuPortal.studentHome(Prefs.sdjtuAcademicBase(this)));
            if (SdjtuPortal.isSchoolUrl(base)) {
                return base + ("timetable".equals(key) ? QiangzhiSource.TIMETABLE_PATH : QiangzhiGrades.GRADES_PATH);
            }
        }
        return SdjtuPortal.LOGIN;
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings s = webView.getSettings();
        s.setJavaScriptEnabled(true);
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        s.setUserAgentString(s.getUserAgentString().replace("; wv", ""));

        s.setAllowFileAccess(false);

        CookieManager cm = CookieManager.getInstance();
        cm.setAcceptCookie(true);
        cm.setAcceptThirdPartyCookies(webView, true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageStarted(WebView view, String url, Bitmap favicon) {
                sdjtuPages.stop();
                findViewById(R.id.btn_import_page).setVisibility(View.GONE);
                progress.setVisibility(View.VISIBLE);
                progress.setProgress(0);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                progress.setVisibility(View.GONE);
                if (webResumed && SdjtuPortal.isSchoolUrl(url)) sdjtuPages.start(false);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if ("about:blank".equals(url) && !request.isForMainFrame()) return false;
                if (SdjtuPortal.isSchoolUrl(url)) return false;
                if (request.isForMainFrame()) Toast.makeText(WebShellActivity.this, "仅支持校内 HTTPS 页面", Toast.LENGTH_SHORT).show();
                return true;
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request.isForMainFrame()) {
                    Log.w(TAG, "main frame error: " + error.getDescription());
                    retryOnce();
                }
            }
        });

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                if (newProgress >= 100) progress.setVisibility(View.GONE);
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, Message resultMsg) {
                if (popupWindow != null) closePopup();
                WebView temp = new WebView(WebShellActivity.this);
                popupWindow = temp;
                temp.getSettings().setAllowFileAccess(false);
                temp.getSettings().setAllowContentAccess(false);
                temp.setWebViewClient(new WebViewClient() {
                    private void open(String url) {
                        if (temp != popupWindow || url == null || url.isEmpty() || "about:blank".equals(url)) return;
                        closePopup();
                        if (SdjtuPortal.isSchoolUrl(url)) webView.loadUrl(url);
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        if (request.isForMainFrame()) open(request.getUrl().toString());
                        return true;
                    }

                    @Override
                    public void onPageStarted(WebView v, String url, Bitmap icon) {
                        open(url);
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(temp);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb,
                                             FileChooserParams params) {
                if (fileCallback != null) {
                    fileCallback.onReceiveValue(null);
                    fileCallback = null;
                }
                fileCallback = cb;
                try {
                    startActivityForResult(params.createIntent(), REQ_FILE);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }
        });
    }

    private void closePopup() {
        if (popupWindow == null) return;
        WebView temp = popupWindow;
        popupWindow = null;
        temp.setWebViewClient(new WebViewClient());
        temp.stopLoading();
        // A popup WebView is never attached, so schedule destruction on the UI thread.
        new android.os.Handler(android.os.Looper.getMainLooper()).post(temp::destroy);
    }

    private void importThisPage() {
        if (importing || webView == null || !SdjtuPortal.isSchoolUrl(webView.getUrl())) return;
        importing = true;
        Toast.makeText(this, "正在读取强智教务课表…", Toast.LENGTH_SHORT).show();
        webView.evaluateJavascript(QiangzhiSource.extractionJs(null, null), raw -> {
            importing = false;
            if (webView == null || isFinishing() || !SdjtuPortal.isSchoolUrl(webView.getUrl())) return;
            QiangzhiSource.Result res = QiangzhiSource.parseHtml(unwrapJsString(raw));
            if (!res.ok()) {
                showUnsupported();
                return;
            }
            probeTermStart(res, () -> showImportPreview(res));
        });
    }

    private void probeTermStart(QiangzhiSource.Result res, Runnable next) {
        SemesterMetaPolicy.fillSdjtuStartDate(res.timetable);
        String have = res.timetable.semester.startDate;
        if (have != null && !have.isEmpty()) { next.run(); return; }
        webView.evaluateJavascript(TermStartProbe.probeJs(res.timetable.semester.id), raw -> {
            if (webView == null || isFinishing()) return;
            String monday = TermStartProbe.detect(unwrapJsString(raw), WeekMath.todayDateLabel());
            DiagLog.log(this, "import", "开学日探测: " + (monday.isEmpty() ? "没找到" : monday));
            if (!monday.isEmpty()) res.timetable.semester.startDate = monday;
            next.run();
        });
    }

    private void showImportPreview(QiangzhiSource.Result res) {
        Timetable current = TimetableStore.load(this);
        String semId = res.timetable.semester.id;
        // 页面上是别的学期（不是当前学期）：合并进归档里的那个学期，不能写进当前学期。
        boolean otherSemester = semId != null && !semId.isEmpty()
                && current.semester.id != null && !current.semester.id.isEmpty()
                && !semId.equals(current.semester.id);
        Timetable archived = otherSemester ? SemesterArchive.loadSemester(this, semId) : null;
        Timetable before = otherSemester ? (archived != null ? archived : new Timetable()) : current;
        Timetable merged = TimetableStore.merge(before, res.timetable);
        SemesterMetaPolicy.fillSdjtuStartDate(merged);
        String diff = SyncDiff.summarize(before, merged);
        StringBuilder msg = new StringBuilder();
        String semName = res.timetable.semester.name;
        msg.append("学期：").append(semName == null || semName.isEmpty() ? "没认出来（沿用当前学期）" : semName).append('\n');
        msg.append("共 ").append(res.courseCount).append(" 门课：");
        int shown = 0;
        for (Timetable.Course c : res.timetable.courses) {
            if (shown == 12) { msg.append(" 等"); break; }
            if (shown > 0) msg.append("、");
            msg.append(c.name);
            shown++;
        }
        if (res.failedWeekExpr > 0) {
            msg.append("\n\n有 ").append(res.failedWeekExpr).append(" 处上课周没认出来，导入后请核对。");
        }
        com.ecut.campus.data.ImportCheck.Result check = com.ecut.campus.data.ImportCheck.check(res.timetable);
        String crowd = com.ecut.campus.data.ImportCheck.warning(check);
        if (!crowd.isEmpty()) {
            msg.append("\n\n").append(crowd);
            DiagLog.log(this, "import", "自检: 拥挤时段 " + check.crowdedSlots + " 最多同时 " + check.maxTogether
                    + " 门 · 最大节次 " + check.maxSection);
        }
        boolean offerLog = res.failedWeekExpr > 0 || !crowd.isEmpty();
        if (diff != null && !diff.isEmpty()) msg.append("\n\n和现在的课表相比：\n").append(diff);
        String start = merged.semester.startDate;
        msg.append("\n\n第 1 周周一：").append(start == null || start.isEmpty()
                ? "没从教务读到，导入后请在课表设置里选一下" : start);
        if (otherSemester) msg.append("\n\n这不是当前学期，会存到历史学期里，当前课表不变。");
        msg.append("\n\n导入会替换之前从教务导入的课程，你自己添加的课程会保留。");
        androidx.appcompat.app.AlertDialog.Builder preview = new androidx.appcompat.app.AlertDialog.Builder(this);
        if (offerLog) preview.setNeutralButton("反馈上游", (d, w) -> exportPageSample());
        preview
                .setTitle("导入这份课表？")
                .setMessage(msg.toString())
                .setNegativeButton("取消", null)
                .setPositiveButton("导入", (d, w) -> {
                    boolean saved = otherSemester
                            ? SemesterArchive.saveSemester(this, merged, false)
                            : TimetableStore.save(this, merged);
                    if (!saved) {
                        Toast.makeText(this, "课表写入本地失败", Toast.LENGTH_LONG).show();
                        return;
                    }
                    SemesterArchive.unifyColors(this);
                    Prefs.setLastSyncTime(this, new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date()));
                    Prefs.setTimetableSyncedAt(this, System.currentTimeMillis());
                    Prefs.setPortalSession(this, true);
                    Prefs.setSessionExpired(this, false);
                    ClassWidget.updateAll(getApplicationContext());
                    ClassReminder.reschedule(getApplicationContext());
                    NextClassNotice.update(getApplicationContext());
                    DiagLog.log(this, "import", "已导入 " + merged.courses.size() + " 门");
                    Toast.makeText(this, "已导入 " + res.courseCount + " 门课", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private void showUnsupported() {
        if (isFinishing()) return;
        String msg = QiangzhiSource.EMPTY_HINT + "\n\n"
                + "可点「反馈上游」向原项目维护者提交适配反馈：会附一份页面结构样本，"
                + "只记网页用了哪些标签和字段名，不含姓名、学号等文字。";
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("没读到课表")
                .setMessage(msg)
                .setNegativeButton("关闭", null)
                .setPositiveButton("反馈上游", (d, w) -> exportPageSample())
                .show();
    }

    private void exportPageSample() {
        if (webView == null) return;
        webView.evaluateJavascript(PageSample.collectJs(), raw -> {
            String sample = PageSample.sanitize(unwrapJsString(raw));
            if (sample.isEmpty()) {
                Toast.makeText(this, "没能读取页面结构", Toast.LENGTH_SHORT).show();
                return;
            }
            DiagLog.savePageSample(this, sample);
            DiagLog.log(this, "import", "已保存页面结构样本 " + sample.length() + " 字");
            DiagLog.upload(this);
        });
    }

    /** 将 evaluateJavascript 返回的 JSON 编码字符串还原为原始字符串 */
    static String unwrapJsString(String raw) {
        if (raw == null) return "";
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || "null".equals(trimmed) || "\"\"".equals(trimmed)) return "";
        try {
            Object val = new JSONTokener(trimmed).nextValue();
            return val != null ? val.toString() : "";
        } catch (Exception e) {
            if (trimmed.startsWith("\"") && trimmed.endsWith("\"") && trimmed.length() >= 2) {
                return trimmed.substring(1, trimmed.length() - 1)
                        .replace("\\\"", "\"")
                        .replace("\\\\", "\\");
            }
            return trimmed;
        }
    }

    private void retryOnce() {
        if (retryCount >= 2) return;
        retryCount++;
        webView.postDelayed(() -> {
            if (webView == null) return;
            String u = webView.getUrl();
            webView.loadUrl(SdjtuPortal.isSchoolUrl(u) ? u : targetUrl);
        }, 800);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FILE && fileCallback != null) {
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int n = data.getClipData().getItemCount();
                    results = new Uri[n];
                    for (int i = 0; i < n; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            fileCallback.onReceiveValue(results);
            fileCallback = null;
        }
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        webResumed = false;
        if (sdjtuPages != null) sdjtuPages.stop();
        if (webView != null) webView.onPause();
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onResume() {
        super.onResume();
        webResumed = true;
        if (webView != null) webView.onResume();
        if (sdjtuPages != null && webView.getProgress() == 100) sdjtuPages.start(false);
    }

    @Override
    protected void onDestroy() {
        if (sdjtuPages != null) sdjtuPages.destroy();
        closePopup();
        CookieManager.getInstance().flush();
        if (webView != null) {
            webView.loadUrl("about:blank");
            if (webView.getParent() instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) webView.getParent()).removeView(webView);
            }
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    private float dpPx(float v) {
        return v * getResources().getDisplayMetrics().density;
    }
}
