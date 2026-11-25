package com.ecut.campus.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 纯 Java 更新清单解析与多源路由（无 Android 依赖，便于单测）。
 * 优先请求自有国内官网（如 EdgeOne Pages）的 /latest.json 与安装包，
 * 不可用时依次回退多路 GitHub 加速镜像、jsDelivr 与 GitHub Releases API。
 */
public final class UpdateManifest {
    private UpdateManifest() {}

    public static final int KIND_OFFICIAL_JSON = 1;
    public static final int KIND_GITHUB_API = 2;

    static final String[] GITHUB_MIRRORS = new String[] {
            "https://ghfast.top/",
            "https://gh-proxy.com/",
            "https://gh-proxy.org/"
    };

    public static final class ReleaseInfo {
        public String tag = "";
        public String name = "";
        public String notes = "";
        public String pageUrl = "";
        public String apkUrl = "";
        public String fallbackApkUrl = "";
        public String sha256 = "";
        public long size = 0L;
        public int versionCode = 0;
    }

    public static final class Endpoint {
        public final String label;
        public final String url;
        public final String baseUrl;
        public final int kind;

        public Endpoint(String label, String url, String baseUrl, int kind) {
            this.label = label;
            this.url = url;
            this.baseUrl = baseUrl;
            this.kind = kind;
        }
    }

    public static final class DownloadSource {
        public final String label;
        public final String url;

        public DownloadSource(String label, String url) {
            this.label = label;
            this.url = url;
        }
    }

    /** 归一化官网根地址，仅允许 HTTPS；无效或非 HTTPS 返回空字符串。 */
    public static String normalizeBaseUrl(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.isEmpty() || s.matches(".*\\s.*")) return "";
        String lower = s.toLowerCase(Locale.US);
        if (lower.startsWith("http://")) return "";
        if (!lower.startsWith("https://")) {
            if (s.contains(":")) return "";
            s = "https://" + s;
        }
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        if (s.length() <= "https://".length()) return "";
        return s;
    }

    /** 解析相对路径或绝对 HTTPS URL；拒绝非 HTTPS 地址。 */
    public static String resolveHttpsUrl(String baseUrl, String candidate) {
        if (candidate == null) return "";
        String s = candidate.trim();
        if (s.isEmpty()) return "";
        String lower = s.toLowerCase(Locale.US);
        if (lower.startsWith("https://")) return s;
        if (s.startsWith("/")) {
            String base = normalizeBaseUrl(baseUrl);
            if (!base.isEmpty()) {
                return "/".equals(s) ? base + "/" : base + s;
            }
        }
        return "";
    }

    /** 生成按优先级排列的检查更新接口列表。 */
    public static List<Endpoint> checkEndpoints(String updateBaseUrl, String releaseRepository) {
        List<Endpoint> out = new ArrayList<>();
        String site = normalizeBaseUrl(updateBaseUrl);
        String repo = releaseRepository == null ? "" : releaseRepository.trim();

        if (!site.isEmpty()) {
            out.add(new Endpoint("官网", site + "/latest.json", site, KIND_OFFICIAL_JSON));
        }
        if (!repo.isEmpty()) {
            String releaseManifest = "https://github.com/" + repo + "/releases/latest/download/latest.json";
            for (int i = 0; i < GITHUB_MIRRORS.length; i++) {
                out.add(new Endpoint("加速镜像 " + (i + 1), GITHUB_MIRRORS[i] + releaseManifest, site, KIND_OFFICIAL_JSON));
            }
            out.add(new Endpoint("CDN 镜像",
                    "https://fastly.jsdelivr.net/gh/" + repo + "@main/site/latest.json",
                    site, KIND_OFFICIAL_JSON));
            out.add(new Endpoint("GitHub API",
                    "https://api.github.com/repos/" + repo + "/releases/latest",
                    site, KIND_GITHUB_API));
        }
        return out;
    }

    /** 解析官网或静态托管的 latest.json。 */
    public static ReleaseInfo parseOfficialJson(String json, String baseUrl, String defaultPageUrl) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            String tag = o.optString("tag", "").trim();
            if (tag.isEmpty()) tag = o.optString("tag_name", "").trim();
            if (tag.isEmpty()) {
                String vn = o.optString("versionName", "").trim();
                if (!vn.isEmpty()) tag = vn.startsWith("v") || vn.startsWith("V") ? vn : "v" + vn;
            }
            if (tag.isEmpty()) return null;

            ReleaseInfo r = new ReleaseInfo();
            r.tag = tag;
            r.name = o.optString("name", tag).trim();
            if (r.name.isEmpty()) r.name = tag;
            r.notes = o.optString("notes", o.optString("body", ""));
            r.versionCode = Math.max(0, o.optInt("versionCode", 0));
            r.size = Math.max(0L, o.optLong("size", 0L));
            r.sha256 = normalizeSha256(o.optString("sha256", ""));

            String primary = resolveHttpsUrl(baseUrl, o.optString("apkUrl", ""));
            String fallback = resolveHttpsUrl(baseUrl, o.optString("githubApkUrl", ""));
            if (primary.isEmpty() && !fallback.isEmpty()) {
                primary = fallback;
                fallback = "";
            }
            r.apkUrl = primary;
            r.fallbackApkUrl = fallback.equals(primary) ? "" : fallback;

            String page = resolveHttpsUrl(baseUrl, o.optString("pageUrl", ""));
            if (page.isEmpty()) page = defaultPageUrl == null ? "" : defaultPageUrl;
            r.pageUrl = page;
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    /** 解析 GitHub Releases API JSON。 */
    public static ReleaseInfo parseGitHubReleaseJson(String json, String defaultPageUrl) {
        if (json == null || json.trim().isEmpty()) return null;
        try {
            JSONObject o = new JSONObject(json);
            String tag = o.optString("tag_name", "").trim();
            if (tag.isEmpty()) return null;

            ReleaseInfo r = new ReleaseInfo();
            r.tag = tag;
            r.name = o.optString("name", tag).trim();
            if (r.name.isEmpty()) r.name = tag;
            r.notes = o.optString("body", "");
            String htmlUrl = resolveHttpsUrl("", o.optString("html_url", ""));
            r.pageUrl = htmlUrl.isEmpty() ? (defaultPageUrl == null ? "" : defaultPageUrl) : htmlUrl;

            JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    if (a.optString("name", "").endsWith(".apk")) {
                        r.apkUrl = resolveHttpsUrl("", a.optString("browser_download_url", ""));
                        r.size = Math.max(0L, a.optLong("size", 0L));
                        r.sha256 = normalizeSha256(a.optString("digest", ""));
                        break;
                    }
                }
            }
            return r;
        } catch (Exception e) {
            return null;
        }
    }

    /** 生成 APK 下载源列表：官网直连优先，其次多路国内加速镜像，最后 GitHub 直连。 */
    public static List<DownloadSource> downloadSources(String apkUrl, String fallbackApkUrl) {
        List<DownloadSource> out = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        String primary = resolveHttpsUrl("", apkUrl);
        String fallback = resolveHttpsUrl("", fallbackApkUrl);

        if (!primary.isEmpty() && !isGitHubUrl(primary)) {
            addUnique(out, seen, "官方下载", primary);
        }
        if (isGitHubUrl(primary)) {
            addGitHubSources(out, seen, primary);
        }
        if (!fallback.isEmpty()) {
            if (isGitHubUrl(fallback)) {
                addGitHubSources(out, seen, fallback);
            } else {
                addUnique(out, seen, "备用下载", fallback);
            }
        }
        return out;
    }

    public static boolean isNewer(ReleaseInfo remote, String localVersionName, int localVersionCode) {
        if (remote == null || remote.tag == null || remote.tag.trim().isEmpty()) return false;
        int cmp = VersionCompare.compare(remote.tag, localVersionName);
        if (cmp > 0) return true;
        if (cmp < 0) return false;
        return remote.versionCode > 0 && localVersionCode > 0 && remote.versionCode > localVersionCode;
    }

    static String normalizeSha256(String raw) {
        if (raw == null) return "";
        String s = raw.trim().toLowerCase(Locale.US);
        if (s.startsWith("sha256:")) s = s.substring(7).trim();
        if (s.length() != 64) return "";
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f');
            if (!hex) return "";
        }
        return s;
    }

    private static boolean isGitHubUrl(String url) {
        return url != null && url.startsWith("https://github.com/");
    }

    private static void addGitHubSources(List<DownloadSource> out, Set<String> seen, String ghUrl) {
        for (int i = 0; i < GITHUB_MIRRORS.length; i++) {
            addUnique(out, seen, "加速镜像 " + (i + 1), GITHUB_MIRRORS[i] + ghUrl);
        }
        addUnique(out, seen, "GitHub", ghUrl);
    }

    private static void addUnique(List<DownloadSource> out, Set<String> seen, String label, String url) {
        if (url == null || url.isEmpty()) return;
        if (seen.add(url)) out.add(new DownloadSource(label, url));
    }
}
