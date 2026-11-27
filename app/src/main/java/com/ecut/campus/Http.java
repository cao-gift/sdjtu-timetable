package com.ecut.campus;

import androidx.annotation.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.zip.GZIPInputStream;

/**
 * 统一轻量级 HTTP 客户端封装（基于 HttpURLConnection），集中处理超时、跳转校验、gzip 解压与字符集解码。
 */
public final class Http {

    public static final String MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36";
    public static final String APP_UA = "lvlv-android";

    private Http() {}

    public static final class Options {
        public int connectTimeoutMs = 8_000;
        public int readTimeoutMs = 10_000;
        public int maxRedirects = 5;
        public int maxBytes = 2 * 1024 * 1024;
        public String userAgent = APP_UA;
        public boolean gzip = true;
        public final Map<String, String> headers = new LinkedHashMap<>();
        /** 可选：每一跳跳转前动态读取 Cookie（如从 WebView CookieManager）。 */
        @Nullable public Function<String, String> cookieProvider;
        /** 可选：校验跳转目标地址是否允许继续跟随；返回 false 时终止并返回 redirectedBlocked=true。 */
        @Nullable public Predicate<String> redirectValidator;
    }

    public static final class Response {
        public final int code;
        public final String finalUrl;
        public final String body;
        public final boolean redirectBlocked;
        public final boolean missingCookie;
        public final boolean tooManyRedirects;
        public final String location;

        Response(int code, String finalUrl, String body, boolean redirectBlocked,
                 boolean missingCookie, boolean tooManyRedirects, String location) {
            this.code = code;
            this.finalUrl = finalUrl;
            this.body = body;
            this.redirectBlocked = redirectBlocked;
            this.missingCookie = missingCookie;
            this.tooManyRedirects = tooManyRedirects;
            this.location = location == null ? "" : location;
        }
    }

    /** 发一次 GET 请求并读取文本响应。 */
    public static Response get(String startUrl, Options opts) throws IOException {
        return request("GET", startUrl, null, opts);
    }

    /** 发一次表单 POST 请求并读取文本响应。 */
    public static Response postForm(String startUrl, byte[] body, Options opts) throws IOException {
        return request("POST", startUrl, body, opts);
    }

    private static Response request(String initialMethod, String startUrl, @Nullable byte[] payload, Options opts)
            throws IOException {
        Options o = opts == null ? new Options() : opts;
        String currentUrl = startUrl;
        String method = initialMethod;

        for (int hop = 0; hop <= o.maxRedirects; hop++) {
            HttpURLConnection conn = null;
            try {
                String cookie = null;
                if (o.cookieProvider != null) {
                    cookie = o.cookieProvider.apply(currentUrl);
                    if (cookie == null || cookie.trim().isEmpty()) {
                        return new Response(-1, currentUrl, "", false, true, false, "");
                    }
                }
                conn = openConnection(currentUrl, method, cookie, o);
                if ("POST".equals(method) && payload != null) {
                    conn.setDoOutput(true);
                    if (!o.headers.containsKey("Content-Type")) {
                        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    }
                    conn.setFixedLengthStreamingMode(payload.length);
                    try (OutputStream os = conn.getOutputStream()) {
                        os.write(payload);
                    }
                }
                int code = conn.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = conn.getHeaderField("Location");
                    String next = resolveUrl(currentUrl, loc);
                    if (next == null || (o.redirectValidator != null && !o.redirectValidator.test(next))) {
                        return new Response(code, currentUrl, "", true, false, false, loc);
                    }
                    currentUrl = next;
                    if (code != 307 && code != 308) method = "GET";
                    continue;
                }
                String body = (code >= 200 && code < 300) ? readBody(conn, o.maxBytes) : "";
                return new Response(code, currentUrl, body, false, false, false, "");
            } finally {
                if (conn != null) conn.disconnect();
            }
        }
        return new Response(-1, currentUrl, "", false, false, true, "");
    }

    /**
     * 打开一个跟随 HTTPS 跳转后的已连接 {@link HttpURLConnection}（用于流式下载大文件，如 APK）。
     * 调用方负责关闭 {@link HttpURLConnection#disconnect()}。
     */
    public static HttpURLConnection openHttpsStream(String startUrl, int connectTimeoutMs,
                                                    int readTimeoutMs, int maxRedirects) throws IOException {
        String url = startUrl;
        for (int hop = 0; hop <= maxRedirects; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(connectTimeoutMs);
            c.setReadTimeout(readTimeoutMs);
            c.setRequestProperty("User-Agent", APP_UA);
            int code = c.getResponseCode();
            if (code / 100 == 3) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) throw new IOException("跳转地址为空");
                url = new URL(new URL(url), loc).toString();
                if (!url.startsWith("https://")) throw new IOException("不安全的跳转");
                continue;
            }
            return c;
        }
        throw new IOException("跳转次数过多");
    }

    private static HttpURLConnection openConnection(String url, String method,
                                                    @Nullable String cookie, Options o) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(o.connectTimeoutMs);
        conn.setReadTimeout(o.readTimeoutMs);
        if (o.userAgent != null && !o.userAgent.isEmpty()) {
            conn.setRequestProperty("User-Agent", o.userAgent);
        }
        if (o.gzip) {
            conn.setRequestProperty("Accept-Encoding", "gzip");
        }
        if (cookie != null && !cookie.isEmpty()) {
            conn.setRequestProperty("Cookie", cookie);
        }
        for (Map.Entry<String, String> e : o.headers.entrySet()) {
            conn.setRequestProperty(e.getKey(), e.getValue());
        }
        return conn;
    }

    public static String resolveUrl(String base, String location) {
        if (location == null || location.trim().isEmpty()) return null;
        try {
            return URI.create(base).resolve(location.trim()).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static String readBody(HttpURLConnection conn, int maxBytes) throws IOException {
        InputStream raw = conn.getErrorStream();
        if (raw == null) raw = conn.getInputStream();
        if (raw == null) return "";
        String enc = conn.getContentEncoding();
        boolean gzip = enc != null && enc.toLowerCase(Locale.ROOT).contains("gzip");
        try (InputStream in = gzip ? new GZIPInputStream(raw) : raw) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
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

    private static Charset detectCharset(@Nullable String contentType) {
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
