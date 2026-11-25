package com.ecut.campus.data;

/**
 * 交院 WebVPN / 教务会话失效时，各页面统一使用的提示文案与判定（纯 Java，便于 JVM 单测）。
 *
 * <ul>
 *   <li>{@link Kind#NONE}：会话有效或尚未判定为失效，不提示。</li>
 *   <li>{@link Kind#AUTO}：会话失效但本机保存了门户账号，同步时自动登录，不打断用户。</li>
 *   <li>{@link Kind#LOGIN}：需要用户重新登录统一门户。</li>
 *   <li>{@link Kind#VERIFY}：自动登录因学校要求额外验证而暂停，需要打开门户完成验证。</li>
 * </ul>
 */
public final class SessionNotice {

    public enum Kind { NONE, AUTO, LOGIN, VERIFY }

    private SessionNotice() {}

    /**
     * @param expired           本机记录的会话已过期
     * @param savedCredentials  已保存门户账号密码（可自动登录）
     * @param autoLoginPaused   自动登录因验证码 / 短信 / 密码错误而暂停
     * @param pageLoginRequired 当前页面的最近一次查询明确返回「需要登录」
     */
    public static Kind of(boolean expired, boolean savedCredentials, boolean autoLoginPaused,
                          boolean pageLoginRequired) {
        if (!expired && !pageLoginRequired) return Kind.NONE;
        if (savedCredentials && autoLoginPaused) return Kind.VERIFY;
        if (savedCredentials && !pageLoginRequired) return Kind.AUTO;
        return Kind.LOGIN;
    }

    /** 只有需要用户动手时才显示提示条。 */
    public static boolean needsAction(Kind kind) {
        return kind == Kind.LOGIN || kind == Kind.VERIFY;
    }

    /** 提示条标题。 */
    public static String title(Kind kind) {
        switch (kind) {
            case LOGIN: return "需要重新登录";
            case VERIFY: return "统一门户需要验证";
            case AUTO: return "登录已失效";
            default: return "";
        }
    }

    /** 提示条说明。 */
    public static String detail(Kind kind) {
        switch (kind) {
            case LOGIN: return "教务登录已过期，登录统一门户后会继续当前同步。";
            case VERIFY: return "学校要求额外验证，请打开门户完成验证，或在设置中更新密码。";
            case AUTO: return "同步时将使用本机保存的账号自动登录。";
            default: return "";
        }
    }

    /** 提示条按钮文字。 */
    public static String action(Kind kind) {
        return kind == Kind.VERIFY ? "去验证" : "重新登录";
    }

    /** 设置页账号状态行。 */
    public static String status(Kind kind) {
        switch (kind) {
            case LOGIN: return "登录已过期，请重新登录";
            case VERIFY: return "需打开门户验证，或更新密码";
            case AUTO: return "会话已失效，同步时将自动登录";
            default: return "";
        }
    }

    /** 设置页账号徽标。 */
    public static String badge(Kind kind) {
        switch (kind) {
            case LOGIN: return "● 登录已过期";
            case VERIFY: return "● 需验证";
            case AUTO: return "● 登录已失效";
            default: return "";
        }
    }

    /** 课表页周次副标题后的短提示；自动登录可处理时不打扰。 */
    public static String subtitleHint(Kind kind) {
        switch (kind) {
            case LOGIN: return "  ·  需重新登录";
            case VERIFY: return "  ·  门户需验证";
            default: return "";
        }
    }
}
