package com.ecut.campus;

import android.webkit.ValueCallback;
import android.webkit.WebView;

import com.ecut.campus.data.SdjtuPortal;

import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

final class SdjtuLoginForm {
    static final String WAIT = "WAIT";
    static final String READY = "READY";
    static final String MANUAL = "MANUAL";
    static final String SUBMITTED = "SUBMITTED";

    private static final String HOST = "wwebvpn.sdjtu.edu.cn";
    private static final String PATH = "/https/77726476706e69737468656265737421f3f652cd69236c5a6a1dc7a99c406d36e9/cas/login";
    private static final String SERVICE = "https://wwebvpn.sdjtu.edu.cn/login?cas_login=true";
    private static final Set<String> EXACT_SERVICES = new HashSet<>(Arrays.asList(
            SERVICE,
            SdjtuPortal.ACADEMIC_SSO,
            "http://192.168.253.164/sso.jsp",
            "http://192.168.253.164/jsxsd/sso.jsp",
            "http://192.168.253.164/jsxsd/",
            "https://zhjwgl.sdjtu.edu.cn/sso.jsp",
            "http://zhjwgl.sdjtu.edu.cn/sso.jsp"
    ));
    private static final String[] ACADEMIC_SERVICE_PREFIXES = {
            "https://zhjwgl.sdjtu.edu.cn/jsxsd/",
            "http://zhjwgl.sdjtu.edu.cn/jsxsd/",
            "http://192.168.253.164/jsxsd/",
            SdjtuPortal.LOGIN + "https/77726476706e69737468656265737421eaff4b8b203c26437a029db9d65027203034e6/jsxsd/",
            SdjtuPortal.LOGIN + "http/77726476706e69737468656265737421a1ae13d27666301e2c5ddae2c90377/jsxsd/"
    };
    private static String script;

    private SdjtuLoginForm() {}

    static boolean isLoginPage(String url) {
        if (url == null) return false;
        try {
            URI page = new URI(url);
            String query = page.getRawQuery();
            return "https".equalsIgnoreCase(page.getScheme())
                    && (HOST.equalsIgnoreCase(page.getRawAuthority())
                        || (HOST + ":443").equalsIgnoreCase(page.getRawAuthority()))
                    && PATH.equals(page.getRawPath())
                    && page.getRawFragment() == null
                    && query != null && query.startsWith("service=") && !query.contains("&")
                    && isAllowedService(URLDecoder.decode(query.substring("service=".length()), "UTF-8"));
        } catch (URISyntaxException | IllegalArgumentException | IOException e) {
            return false;
        }
    }

    private static boolean isAllowedService(String service) {
        if (EXACT_SERVICES.contains(service)) return true;
        if (service == null || service.indexOf('?') >= 0 || service.indexOf('#') >= 0
                || service.contains("..")) return false;
        for (String prefix : ACADEMIC_SERVICE_PREFIXES) {
            if (service.startsWith(prefix)) {
                String tail = service.substring(prefix.length());
                return tail.matches("[a-zA-Z0-9_./-]*");
            }
        }
        return false;
    }

    static void inspect(WebView view, ValueCallback<String> done) {
        evaluate(view, null, null, done);
    }

    static void submit(WebView view, String username, String password, ValueCallback<String> done) {
        if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
            done.onReceiveValue(MANUAL);
            return;
        }
        evaluate(view, username, password, done);
    }

    private static String argument(String value) {
        return value == null ? "null" : JSONObject.quote(value)
                .replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }

    private static void evaluate(WebView view, String username, String password, ValueCallback<String> done) {
        String page = view.getUrl();
        if (!isLoginPage(page)) {
            done.onReceiveValue(MANUAL);
            return;
        }
        try {
            if (script == null) script = SdjtuAcademicPages.read(view, "sdjtu-login-form.js");
        } catch (IOException e) {
            done.onReceiveValue(MANUAL);
            return;
        }
        // The renderer rechecks the same document before accessing the form. WebVPN serves
        // many other applications on this origin, and a navigation can race this callback.
        String call = script + "(" + argument(PATH) + "," + argument(page.substring(page.indexOf('?')))
                + "," + argument(username) + "," + argument(password) + ")";
        view.evaluateJavascript(call, result -> {
            if ("\"WAIT\"".equals(result)) done.onReceiveValue(WAIT);
            else if ("\"READY\"".equals(result)) done.onReceiveValue(READY);
            else if ("\"SUBMITTED\"".equals(result)) done.onReceiveValue(SUBMITTED);
            else done.onReceiveValue(MANUAL);
        });
    }
}
