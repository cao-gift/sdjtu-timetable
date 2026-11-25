package com.ecut.campus.data;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * 交院 WebVPN / 旧版强智教务会话探测的纯 Java 判定逻辑（无 Android 依赖，便于 JVM 单测）。
 */
public final class SdjtuSessionCheck {

    public enum State {
        ACTIVE,
        EXPIRED,
        UNKNOWN
    }

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;
    private static final Pattern PASSWORD_INPUT = Pattern.compile(
            "<input\\b[^>]*\\btype\\s*=\\s*[\"']?password[\"']?", FLAGS);
    private static final Pattern JS_LOGIN_REDIRECT = Pattern.compile(
            "(?:window\\.)?(?:top\\.)?location(?:\\.href)?\\s*(?:=|\\.replace\\s*\\(|\\.assign\\s*\\()\\s*[\"'][^\"']*(?:/login|/cas/login|fingerprint|sso\\.jsp|/jsxsd/?[\"'])",
            FLAGS);
    private static final Pattern LOGIN_TEXT = Pattern.compile(
            "登录已过期|请重新登录|会话已过期|用户未登录|统一身份认证|账号密码登录", FLAGS);
    private static final Pattern MAINTENANCE_TEXT = Pattern.compile(
            "系统维护中|服务暂不可用|Bad\\s+Gateway|Service\\s+Unavailable|Gateway\\s+Time-?out", FLAGS);
    private static final Pattern ACADEMIC_MARKER = Pattern.compile(
            "LeftMenu1_divChildMenu|divFirstMenuClass|Top1_divLoginName|Nsb_top|Nsb_menu|"
                    + "\\bid\\s*=\\s*[\"']?(?:dataList|kbtable|mxh)[\"']?|<frameset\\b|"
                    + "我的课表|学期理论课表|教室课表查询|培养方案|学业成绩|成绩查询",
            FLAGS);

    private SdjtuSessionCheck() {}

    /** 判断重定向目标是否指向登录、认证、指纹、登出或脱离教务系统。 */
    public static boolean isLoginLocation(String location) {
        if (location == null) return true;
        String trimmed = location.trim();
        if (trimmed.isEmpty()) return true;
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.contains("/login") || lower.contains("/cas/login")
                || lower.contains("fingerprint") || lower.contains("logout")
                || lower.contains("sso.jsp")) {
            return true;
        }
        return !lower.contains("/jsxsd/");
    }

    /** 判断 HTTP 200 页面正文是否呈现登录页、过期提示或登录跳转脚本。 */
    public static boolean isLoginBody(String body) {
        if (body == null || body.isEmpty()) return false;
        if (PASSWORD_INPUT.matcher(body).find()) return true;
        if (body.contains("normalLoginForm") || body.contains("/cas/login")) return true;
        if (body.length() < 4000 && JS_LOGIN_REDIRECT.matcher(body).find()) return true;
        return body.length() < 2000 && LOGIN_TEXT.matcher(body).find();
    }

    /** 判断页面正文是否包含已进入强智教务的特征标记。 */
    public static boolean hasAcademicMarker(String body) {
        return body != null && !body.isEmpty() && ACADEMIC_MARKER.matcher(body).find();
    }

    /** 根据对学生主页 xsMain.jsp 的探测响应判定当前会话状态。 */
    public static State classifyStudentHome(int statusCode, String location, String body) {
        if (statusCode == 401 || statusCode == 403) {
            return State.EXPIRED;
        }
        if (statusCode >= 300 && statusCode < 400) {
            return isLoginLocation(location) ? State.EXPIRED : State.UNKNOWN;
        }
        if (statusCode == 200) {
            if (body == null || body.trim().isEmpty()) {
                return State.UNKNOWN;
            }
            boolean hasAcademic = hasAcademicMarker(body);
            if (isLoginBody(body) && !hasAcademic) {
                return State.EXPIRED;
            }
            if (hasAcademic) {
                return State.ACTIVE;
            }
            if (body.length() < 2500 && MAINTENANCE_TEXT.matcher(body).find()) {
                return State.UNKNOWN;
            }
            return State.EXPIRED;
        }
        return State.UNKNOWN;
    }
}
