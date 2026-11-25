package com.ecut.campus;

import android.app.Activity;
import android.content.Intent;
import android.os.SystemClock;
import android.webkit.WebView;

import com.ecut.campus.data.SdjtuPortal;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** 在当前交院 WebVPN 会话中自动完成统一认证并进入教务系统/菜单，让结果留在学校页面。 */
final class SdjtuAcademicPages {
    enum Feature {
        PORTAL("", "", "", null),
        ROOMS("sdjtu_rooms", "查空教室", "登录统一门户后进入强智教务。菜单路径：培养管理 → 我的课表 → 教室课表查询。", "sdjtu-rooms.js"),
        PLAN("sdjtu_plan", "培养方案", "登录统一门户后进入强智教务，选择「培养管理 → 培养方案 → 培养方案执行计划」。", "sdjtu-plan.js"),
        EXAMS("sdjtu_exams", "考试安排", "", "sdjtu-exams.js");

        final String target;
        final String title;
        final String guide;
        final String asset;

        Feature(String target, String title, String guide, String asset) {
            this.target = target;
            this.title = title;
            this.guide = guide;
            this.asset = asset;
        }

        static Feature fromTarget(String target) {
            if (ROOMS.target.equals(target)) return ROOMS;
            if (PLAN.target.equals(target)) return PLAN;
            return PORTAL;
        }

        void locate(Activity activity, int requestCode) {
            Intent intent = new Intent(activity, WebShellActivity.class);
            intent.putExtra(WebShellActivity.EXTRA_TARGET_KEY, target);
            intent.putExtra(WebShellActivity.EXTRA_LOCATE_STUDY, true);
            intent.putExtra(WebShellActivity.EXTRA_HINT, guide + " 打开查询结果后，点「读取此页」。");
            activity.startActivityForResult(intent, requestCode);
        }
    }

    interface Listener {
        void onAcademic();
        void onStage(String stage);
    }

    private final WebView view;
    private final Feature feature;
    private final Listener listener;
    private final boolean loginForSync;
    private final boolean manual;
    private final String install;
    private final SdjtuAutoLogin autoLogin;
    private final Runnable next = this::inspect;
    private int generation;
    private int attempts;
    private long pageSince;
    private boolean located;
    private boolean enteredAcademic;
    private boolean ssoFallbackUsed;
    private boolean manualAuthOnly;
    private boolean resetMenus;
    private boolean pageChanged;

    SdjtuAcademicPages(WebView view, Feature feature, boolean loginForSync, boolean manual, Listener listener) {
        this.view = view;
        this.feature = feature;
        this.listener = listener;
        this.loginForSync = loginForSync;
        this.manual = manual;
        this.autoLogin = new SdjtuAutoLogin(view.getContext());
        String scripts;
        try {
            String portal = read(view, "sdjtu-portal.js");
            String reader = feature.asset == null ? "null" : read(view, feature.asset);
            scripts = "document.__lvlvAcademic={portal:" + portal + ",reader:" + reader + "};";
        } catch (IOException e) {
            android.util.Log.w("WebShell", "无法加载交院教务阅读脚本", e);
            scripts = "";
        }
        install = scripts;
    }

    static String read(WebView view, String name) throws IOException {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                view.getContext().getAssets().open(name), StandardCharsets.UTF_8))) {
            StringBuilder content = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) content.append(line).append('\n');
            return content.toString();
        }
    }

    // A user can open a different leaf inside xsMain.jsp without changing the address bar.
    void capture(java.util.function.Consumer<JSONObject> callback) {
        if (!manual || install.isEmpty() || !SdjtuPortal.isSchoolUrl(view.getUrl())) { callback.accept(new JSONObject()); return; }
        String mode = feature == Feature.ROOMS ? "rooms" : "plan";
        int current = generation;
        String call = "(function(){if(location.protocol!=='https:'||!/(^|\\.)sdjtu\\.edu\\.cn$/i.test(location.hostname))return {};"
                + "if(!document.__lvlvAcademic){" + install + "}var a=document.__lvlvAcademic;"
                + "return a.portal('" + mode + "',false,false,a.reader,{sync:true,capture:true,submitted:true});})()";
        view.evaluateJavascript(call, value -> {
            JSONObject result = new JSONObject();
            if (current == generation) {
                try { result = new JSONObject(WebShellActivity.unwrapJsString(value)); }
                catch (JSONException ignored) { /* A frame has not finished loading. */ }
            }
            callback.accept(result);
        });
    }

    void stop() {
        generation++;
        view.removeCallbacks(next);
    }

    void destroy() {
        stop();
        autoLogin.stop();
    }

    void start(boolean retry) {
        stop();
        if (retry) {
            located = false;
            enteredAcademic = false;
            ssoFallbackUsed = false;
            manualAuthOnly = false;
        }
        resetMenus = retry;
        attempts = 0;
        pageChanged = true;
        pageSince = SystemClock.elapsedRealtime();
        inspect();
    }

    private void inspect() {
        String url = view.getUrl();
        if (install.isEmpty() || !SdjtuPortal.isSchoolUrl(url)) return;
        int current = generation;
        if (!manual && !manualAuthOnly && !autoLogin.credentialsChanged() && SdjtuLoginForm.isLoginPage(url)) {
            autoLogin.inspectAndSubmit(view, pageSince,
                    () -> generation == current && url.equals(view.getUrl()),
                    new SdjtuAutoLogin.Callback() {
                        @Override public void waitMore(long delayMs) {
                            if (generation == current && view.isShown()) view.postDelayed(next, delayMs);
                        }

                        @Override public void manual(String message) {
                            manualAuthOnly = true;
                        }

                        @Override public void busy(String message) {
                            manualAuthOnly = true;
                        }
                    });
            return;
        }

        boolean dashboard = SdjtuPortal.isDashboard(url);
        boolean navigate = !manual && (feature != Feature.PORTAL ? !located : !enteredAcademic);
        String mode = feature == Feature.ROOMS ? "rooms" : feature == Feature.PLAN ? "plan" : "portal";
        String call = "(function(){"
                + (pageChanged ? "if(!document.__lvlvAcademic){" + install + "}" : "")
                + "var a=document.__lvlvAcademic;"
                + "return a?a.portal('" + mode + "'," + navigate + "," + resetMenus + (manual ? ",null" : ",a.reader") + "):{};})()";
        pageChanged = false;
        resetMenus = false;
        view.evaluateJavascript(call, value -> {
            if (generation != current) return;
            JSONObject result;
            try {
                result = new JSONObject(WebShellActivity.unwrapJsString(value));
            } catch (JSONException ignored) {
                result = new JSONObject();
            }
            String base = SdjtuPortal.academicBase(result.optString("base"));
            if (!base.isEmpty()) {
                enteredAcademic = true;
                autoLogin.onSuccess();
                if (!base.equals(Prefs.sdjtuAcademicBase(view.getContext()))) {
                    Prefs.setSdjtuAcademicBase(view.getContext(), base);
                }
                if (!Prefs.hasPortalSession(view.getContext()) || Prefs.sessionExpired(view.getContext())) {
                    Prefs.setPortalSession(view.getContext(), true);
                    Prefs.setSessionExpired(view.getContext(), false);
                }
                listener.onAcademic();
            }
            SdjtuStudyRoute.remember(view.getContext(), feature, result.optString("entryPage"));
            String stage = result.optString("stage");
            if (navigate && dashboard && base.isEmpty() && !"login".equals(stage) && !"error".equals(stage)
                    && !stage.isEmpty() && !ssoFallbackUsed && SystemClock.elapsedRealtime() - pageSince > 3_500L) {
                ssoFallbackUsed = true;
                view.loadUrl(SdjtuPortal.ACADEMIC_SSO);
                return;
            }
            if ("results".equals(stage) || "form".equals(stage) || "plan".equals(stage)) located = true;
            listener.onStage(stage);
            // A student can spend several minutes choosing a plan or finishing WebVPN login.
            // Poll only the visible document; no network request is made by this timer.
            boolean waiting = feature == Feature.PORTAL
                    ? base.isEmpty() && (loginForSync || (!enteredAcademic && attempts < 25))
                    : !located;
            if (!manual && waiting && view.isShown()) {
                attempts++;
                view.postDelayed(next, attempts < 5 ? 800 : attempts < 12 ? 2200 : 7000);
            }
        });
    }
}
