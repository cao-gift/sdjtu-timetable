package com.ecut.campus;

import android.app.Activity;
import android.graphics.Color;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;

import androidx.annotation.Nullable;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * 统一全面屏（Edge-to-Edge）与系统栏内边距适配，满足 Android 15 (targetSdk 35) 规范。
 * 状态栏与导航栏背景透明，内容按 WindowInsets 避让系统栏、刘海与输入法。
 */
public final class EdgeToEdge {

    private EdgeToEdge() {}

    /** 让页面内容延伸至状态栏与导航栏下方，并按当前深浅主题设置系统栏图标色调。 */
    @SuppressWarnings("deprecation")
    public static void enable(Activity activity) {
        if (activity == null) return;
        Window window = activity.getWindow();
        if (window == null) return;
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        if (Build.VERSION.SDK_INT >= 29) {
            window.setNavigationBarContrastEnforced(false);
            window.setStatusBarContrastEnforced(false);
        }
        WindowInsetsControllerCompat ctrl = WindowCompat.getInsetsController(window, window.getDecorView());
        if (ctrl != null) {
            boolean lightBars = !G.NIGHT;
            ctrl.setAppearanceLightStatusBars(lightBars);
            ctrl.setAppearanceLightNavigationBars(lightBars);
        }
    }

    /** 给整页根容器叠加系统栏（含刘海）与可选输入法内边距。 */
    public static void apply(Activity activity, @Nullable View root, boolean includeIme) {
        enable(activity);
        if (root == null) return;
        final int l = root.getPaddingLeft();
        final int t = root.getPaddingTop();
        final int r = root.getPaddingRight();
        final int b = root.getPaddingBottom();
        int mask = WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                | (includeIme ? WindowInsetsCompat.Type.ime() : 0);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets sys = insets.getInsets(mask);
            v.setPadding(l + sys.left, t + sys.top, r + sys.right, b + sys.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    /** 快捷入口：自动取 Activity 的 content 根视图并叠加内边距。 */
    public static void applyContent(Activity activity, boolean includeIme) {
        enable(activity);
        ViewGroup content = activity.findViewById(android.R.id.content);
        if (content != null && content.getChildCount() > 0) {
            apply(activity, content.getChildAt(0), includeIme);
        }
    }

    /** 仅叠加顶部状态栏与左右安全区（适用于底部由滚动容器或悬浮 Dock 自行避让的页面）。 */
    public static void applyTop(View view) {
        if (view == null) return;
        final int l = view.getPaddingLeft();
        final int t = view.getPaddingTop();
        final int r = view.getPaddingRight();
        final int b = view.getPaddingBottom();
        int mask = WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout();
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            Insets sys = insets.getInsets(mask);
            v.setPadding(l + sys.left, t + sys.top, r + sys.right, b);
            return insets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    /** 仅叠加底部导航栏与左右安全区（适用于底部可滚动列表或设置页）。 */
    public static void applyBottom(View view, boolean includeIme) {
        if (view == null) return;
        final int l = view.getPaddingLeft();
        final int t = view.getPaddingTop();
        final int r = view.getPaddingRight();
        final int b = view.getPaddingBottom();
        int mask = WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout()
                | (includeIme ? WindowInsetsCompat.Type.ime() : 0);
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            Insets sys = insets.getInsets(mask);
            v.setPadding(l + sys.left, t, r + sys.right, b + sys.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(view);
    }

    /** 给底部悬浮控件（如 CapsuleDock）叠加底部导航栏外边距。 */
    public static void applyBottomMargin(View view, @Nullable Runnable after) {
        if (view == null) return;
        ViewGroup.LayoutParams raw = view.getLayoutParams();
        if (!(raw instanceof ViewGroup.MarginLayoutParams)) return;
        final int baseBottom = ((ViewGroup.MarginLayoutParams) raw).bottomMargin;
        ViewCompat.setOnApplyWindowInsetsListener(view, (v, insets) -> {
            Insets sys = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
            int nextBottom = baseBottom + sys.bottom;
            if (lp.bottomMargin != nextBottom) {
                lp.bottomMargin = nextBottom;
                v.setLayoutParams(lp);
            }
            if (after != null) v.post(after);
            return insets;
        });
        ViewCompat.requestApplyInsets(view);
    }
}
