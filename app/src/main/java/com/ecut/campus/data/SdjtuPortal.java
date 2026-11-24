package com.ecut.campus.data;

import java.net.URI;
import java.util.Locale;

/** 山东交通学院入口及通过统一门户访问强智时的实际教务根地址。 */
public final class SdjtuPortal {
    public static final String NAME = "山东交通学院";
    public static final String LOGIN = "https://wwebvpn.sdjtu.edu.cn/";

    public static final String STUDENT_HOME = LOGIN
            + "https/77726476706e69737468656265737421eaff4b8b203c26437a029db9d65027203034e6/jsxsd/framework/xsMain.jsp";

    public static String studentHome(String cachedBase) {
        String base = academicBase(cachedBase);
        // Older releases could mistake an unrewritten root-relative frame for the WebVPN academic root.
        if (base.isEmpty() || (LOGIN + "jsxsd/").equals(base)) return STUDENT_HOME;
        return base + "framework/xsMain.jsp";
    }

    private SdjtuPortal() {}

    /** WebVPN、统一身份认证和教务页面只使用学校 HTTPS 域名。 */
    public static boolean isSchoolUrl(String url) {
        if (url == null) return false;
        try {
            return isSchoolAddress(URI.create(url));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isSchoolAddress(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getRawUserInfo() != null
                || uri.getHost() == null || (uri.getPort() != -1 && uri.getPort() != 443)) return false;
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        return host.equals("sdjtu.edu.cn") || host.endsWith(".sdjtu.edu.cn");
    }

    /** 保留 WebVPN 改写的路径前缀；登录参数、会话标识和片段不落盘。 */
    public static String academicBase(String page) {
        if (page == null || page.isEmpty()) return "";
        try {
            URI uri = URI.create(page);
            if (!isSchoolAddress(uri)) return "";
            String host = uri.getHost().toLowerCase(Locale.ROOT);
            String path = uri.getRawPath();
            int index = (path + "/").indexOf("/jsxsd/");
            if (index < 0) return "";
            String prefix = path.substring(0, index);
            if (!prefix.matches("(/[a-zA-Z0-9_-]+)*")) return "";
            String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
            return "https://" + host + port + prefix + "/jsxsd/";
        } catch (IllegalArgumentException e) {
            return "";
        }
    }
}
