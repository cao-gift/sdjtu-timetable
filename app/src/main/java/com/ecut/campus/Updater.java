package com.ecut.campus;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.ecut.campus.data.UpdateManifest;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** 检查新版本：优先请求自有官网静态清单，失败后依次回退多路加速镜像与 GitHub Releases。 */
public final class Updater {
    private Updater() {}

    private static final String REPO = BuildConfig.RELEASE_REPOSITORY == null ? "" : BuildConfig.RELEASE_REPOSITORY.trim();
    public static final String SITE_URL = UpdateManifest.normalizeBaseUrl(BuildConfig.UPDATE_BASE_URL);
    public static final String REPO_URL = "https://github.com/" + REPO;
    public static final String RELEASES_URL = REPO_URL + "/releases";
    public static final String FEEDBACK_EMAIL = "3514088137@qq.com";
    private static final long AUTO_INTERVAL = 6L * 60 * 60 * 1000;
    private static final int MAX_BODY_BYTES = 512 * 1024;

    public static final class Release {
        public String tag, name, notes, pageUrl, apkUrl;
        /** 备用下载地址（例如 GitHub Release APK），主地址失败时由 UpdateInstaller 接管。 */
        public String fallbackApkUrl = "";
        /** 安装包 SHA-256（小写十六进制），没有时为空 */
        public String sha256 = "";
        public long size;
        public int versionCode;
    }

    public interface Callback {
        /** release 为 null 表示所有更新源均不可达或解析失败。 */
        void done(Release release);
    }

    public static boolean hasOfficialSite() {
        return !SITE_URL.isEmpty();
    }

    public static boolean hasReleaseRepository() {
        return !REPO.isEmpty();
    }

    public static boolean hasUpdateSource() {
        return hasOfficialSite() || hasReleaseRepository();
    }

    public static String defaultDownloadPageUrl() {
        return hasOfficialSite() ? SITE_URL + "/" : RELEASES_URL;
    }

    public static void fetchLatest(Callback cb) {
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Release r = null;
            List<UpdateManifest.Endpoint> endpoints = UpdateManifest.checkEndpoints(SITE_URL, REPO);
            String defaultPage = defaultDownloadPageUrl();
            for (UpdateManifest.Endpoint ep : endpoints) {
                String body = fetchJson(ep);
                if (body == null) continue;
                UpdateManifest.ReleaseInfo info = ep.kind == UpdateManifest.KIND_GITHUB_API
                        ? UpdateManifest.parseGitHubReleaseJson(body, defaultPage)
                        : UpdateManifest.parseOfficialJson(body, ep.baseUrl, defaultPage);
                if (info != null) {
                    r = toRelease(info);
                    break;
                }
            }
            final Release fr = r;
            main.post(() -> cb.done(fr));
        }, "update-check").start();
    }

    private static String fetchJson(UpdateManifest.Endpoint ep) {
        HttpURLConnection c = null;
        try {
            String url = ep.url;
            int code = -1;
            for (int hop = 0; hop < 5; hop++) {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(6000);
                c.setReadTimeout(8000);
                c.setRequestProperty("Accept", ep.kind == UpdateManifest.KIND_GITHUB_API
                        ? "application/vnd.github+json" : "application/json");
                c.setRequestProperty("Cache-Control", "no-cache");
                c.setRequestProperty("User-Agent", "lvlv-android");
                code = c.getResponseCode();
                if (code / 100 == 3) {
                    String loc = c.getHeaderField("Location");
                    c.disconnect();
                    c = null;
                    if (loc == null) return null;
                    url = new URL(new URL(url), loc).toString();
                    if (!url.startsWith("https://")) return null;
                    continue;
                }
                break;
            }
            if (code != 200 || c == null) return null;
            try (InputStream is = c.getInputStream()) {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                int total = 0;
                while ((n = is.read(buf)) > 0) {
                    total += n;
                    if (total > MAX_BODY_BYTES) return null;
                    bo.write(buf, 0, n);
                }
                return bo.toString(StandardCharsets.UTF_8.name());
            }
        } catch (Exception ignored) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static Release toRelease(UpdateManifest.ReleaseInfo info) {
        Release r = new Release();
        r.tag = info.tag;
        r.name = info.name;
        r.notes = info.notes;
        r.pageUrl = info.pageUrl;
        r.apkUrl = info.apkUrl;
        r.fallbackApkUrl = info.fallbackApkUrl;
        r.sha256 = info.sha256;
        r.size = info.size;
        r.versionCode = info.versionCode;
        return r;
    }

    public static boolean isNewer(Release r) {
        if (r == null) return false;
        UpdateManifest.ReleaseInfo info = new UpdateManifest.ReleaseInfo();
        info.tag = r.tag;
        info.versionCode = r.versionCode;
        return UpdateManifest.isNewer(info, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE);
    }

    /** 打开 App 时静默检查，6 小时最多一次；勾了「不再提示」的版本不再弹。 */
    public static void autoCheck(Activity a) {
        try { UpdateInstaller.cleanup(a); } catch (Exception ignored) {}
        if (!hasUpdateSource() || !Prefs.updateAutoCheck(a)) return;
        long now = System.currentTimeMillis();
        if (now - Prefs.updateCheckedAt(a) < AUTO_INTERVAL) return;
        fetchLatest(r -> {
            if (r == null) return;
            Prefs.setUpdateCheckedAt(a.getApplicationContext(), System.currentTimeMillis());
            if (a.isFinishing() || a.isDestroyed() || !isNewer(r)) return;
            if (r.tag.equals(Prefs.updateSkippedTag(a))) return;
            showDialog(a, r, true);
        });
    }

    public static void showDialog(Activity a, Release r, boolean allowSkip) {
        String notes = r.notes == null ? "" : r.notes.trim();
        if (notes.length() > 600) notes = notes.substring(0, 600) + "…";
        float dp = a.getResources().getDisplayMetrics().density;
        android.widget.LinearLayout box = new android.widget.LinearLayout(a);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        box.setPadding(Math.round(24 * dp), Math.round(8 * dp), Math.round(24 * dp), 0);
        android.widget.TextView msg = new android.widget.TextView(a);
        msg.setText((notes.isEmpty() ? "" : notes + "\n\n") + "当前版本 v" + BuildConfig.VERSION_NAME);
        msg.setTextSize(14f);
        msg.setTextColor(G.SUB);
        msg.setLineSpacing(0f, 1.15f);
        box.addView(msg);
        android.widget.CheckBox never = new android.widget.CheckBox(a);
        never.setText("这个版本不再提示");
        never.setTextSize(13f);
        never.setTextColor(G.SUB);
        if (allowSkip) {
            android.widget.LinearLayout.LayoutParams lp = new android.widget.LinearLayout.LayoutParams(
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                    android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.topMargin = Math.round(10 * dp);
            lp.leftMargin = Math.round(-6 * dp);
            box.addView(never, lp);
        }
        android.widget.ScrollView sv = new android.widget.ScrollView(a);
        sv.addView(box);
        new MaterialAlertDialogBuilder(a)
                .setTitle("发现新版本 " + r.tag)
                .setView(sv)
                .setPositiveButton("更新", (d, w) -> {
                    boolean hasApk = (r.apkUrl != null && !r.apkUrl.isEmpty())
                            || (r.fallbackApkUrl != null && !r.fallbackApkUrl.isEmpty());
                    if (hasApk) UpdateInstaller.start(a, r);
                    else open(a, r.pageUrl == null || r.pageUrl.isEmpty() ? defaultDownloadPageUrl() : r.pageUrl);
                })
                .setNegativeButton("关闭", (d, w) -> {
                    if (allowSkip && never.isChecked()) Prefs.setUpdateSkippedTag(a, r.tag);
                })
                .setOnCancelListener(d -> {
                    if (allowSkip && never.isChecked()) Prefs.setUpdateSkippedTag(a, r.tag);
                })
                .show();
    }

    public static void open(Context c, String url) {
        try {
            c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Exception e) {
            android.widget.Toast.makeText(c, "没有可以打开链接的应用", android.widget.Toast.LENGTH_SHORT).show();
        }
    }
}
