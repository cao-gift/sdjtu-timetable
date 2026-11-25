package com.ecut.campus;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.ecut.campus.data.SessionNotice;

/**
 * 「需要重新登录」提示条：成绩、考试安排、空教室、培养方案共用。
 * 纸面卡片 + 发丝描边，只用一个朱砂圆点；按钮把用户带去统一门户，登录后各页面自行继续同步。
 */
final class SessionBanner {

    private final Activity activity;
    private final LinearLayout root;
    private final TextView title, detail, action;
    private SessionNotice.Kind kind = SessionNotice.Kind.NONE;

    SessionBanner(Activity a, Runnable onLogin) {
        activity = a;
        root = new LinearLayout(a);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(Ui.dp(a, 14), Ui.dp(a, 10), Ui.dp(a, 6), Ui.dp(a, 10));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(Ui.dp(a, G.CARD_RADIUS));
        bg.setColor(G.CARD);
        bg.setStroke(Math.max(1, Ui.dp(a, 1)), G.CARD_STROKE);
        root.setBackground(bg);
        root.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.setVisibility(View.GONE);

        TextView dot = new TextView(a);
        dot.setText("●");
        dot.setTextSize(10f);
        dot.setTextColor(G.ACCENT);
        dot.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(-2, -2);
        dotLp.setMarginEnd(Ui.dp(a, 10));
        root.addView(dot, dotLp);

        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        title = new TextView(a);
        title.setTextSize(14.5f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(G.INK);
        texts.addView(title);
        detail = new TextView(a);
        detail.setTextSize(12.5f);
        detail.setTextColor(G.SUB);
        detail.setPadding(0, Ui.dp(a, 2), 0, 0);
        texts.addView(detail);
        root.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        action = new TextView(a);
        action.setGravity(Gravity.CENTER);
        action.setTextSize(14f);
        action.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        action.setTextColor(G.INK);
        action.setMinHeight(Ui.dp(a, 48));
        action.setMinWidth(Ui.dp(a, 48));
        action.setPadding(Ui.dp(a, 12), 0, Ui.dp(a, 12), 0);
        action.setBackgroundResource(Ui.selectable(a, false));
        action.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            onLogin.run();
        });
        root.addView(action, new LinearLayout.LayoutParams(-2, -2));
    }

    View view() { return root; }

    SessionNotice.Kind kind() { return kind; }

    /**
     * 按本机会话状态与当前页面最近一次查询结果刷新。
     *
     * @param pageLoginRequired 当前页面查询明确返回需要登录
     * @param busy              正在同步或正在登录时隐藏，避免和进度提示重复
     */
    void update(boolean pageLoginRequired, boolean busy, boolean enabled) {
        kind = SessionNotice.of(Prefs.sessionExpired(activity), SdjtuCredentials.isSaved(activity),
                SdjtuAutoLogin.isPaused(activity), pageLoginRequired);
        boolean show = !busy && SessionNotice.needsAction(kind);
        root.setVisibility(show ? View.VISIBLE : View.GONE);
        if (!show) return;
        title.setText(SessionNotice.title(kind));
        detail.setText(SessionNotice.detail(kind));
        action.setText(SessionNotice.action(kind));
        action.setEnabled(enabled);
        action.setAlpha(enabled ? 1f : 0.4f);
        root.setContentDescription(SessionNotice.title(kind) + "，" + SessionNotice.detail(kind));
    }
}
