package com.ecut.campus;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.webkit.WebView;

import java.util.function.BooleanSupplier;

/** 交院统一认证 CAS 自动提交状态机与单次重试保护，供后台同步与门户页共用。 */
final class SdjtuAutoLogin {
    interface Callback {
        void waitMore(long delayMs);
        void manual(String message);
        void busy(String message);
    }

    private static final String PAUSED_GENERATION = "pausedGeneration";
    // All WebView callbacks run on the main thread.
    private static SdjtuAutoLogin signingIn;

    private final Context context;
    private final long generation;
    private final SharedPreferences attempts;
    private final boolean pausedWhenStarted;
    private boolean attempted;
    private long submittedAt;
    private long readySince;

    SdjtuAutoLogin(Context context) {
        this.context = context.getApplicationContext();
        this.generation = SdjtuCredentials.generation(this.context);
        this.attempts = this.context.getSharedPreferences("sdjtu_login_state", Context.MODE_PRIVATE);
        this.pausedWhenStarted = attempts.getLong(PAUSED_GENERATION, -1) == generation;
    }

    static boolean isPaused(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences("sdjtu_login_state", Context.MODE_PRIVATE)
                .getLong(PAUSED_GENERATION, -1) == SdjtuCredentials.generation(context);
    }

    static boolean canAttempt(Context context) {
        return SdjtuCredentials.isSaved(context) && !isPaused(context);
    }

    static void clearPause(Context context) {
        context.getApplicationContext()
                .getSharedPreferences("sdjtu_login_state", Context.MODE_PRIVATE)
                .edit().remove(PAUSED_GENERATION).apply();
    }

    void resetPage() {
        readySince = 0;
    }

    boolean credentialsChanged() {
        return SdjtuCredentials.isUpdating() || generation != SdjtuCredentials.generation(context);
    }

    boolean attempted() {
        return attempted;
    }

    void inspectAndSubmit(WebView web, long pageSince, BooleanSupplier stillSamePage, Callback cb) {
        if (!attempted) {
            if (signingIn != null && signingIn != this) {
                cb.busy("另一项同步正在登录统一门户，请稍后再同步");
                return;
            }
            if (!SdjtuCredentials.isSaved(context)) {
                cb.manual("请登录山东交通学院统一门户，或在设置中保存门户账号密码");
                return;
            }
            if (attempts.getLong(PAUSED_GENERATION, -1) == generation) {
                cb.manual("自动登录已暂停，请手动登录统一门户，或在设置中更新账号密码");
                return;
            }
        }
        SdjtuLoginForm.inspect(web, state -> {
            if (!stillSamePage.getAsBoolean()) return;
            onForm(web, state, pageSince, stillSamePage, cb);
        });
    }

    private void onForm(WebView web, String state, long pageSince, BooleanSupplier stillSamePage, Callback cb) {
        if (SdjtuLoginForm.MANUAL.equals(state)) {
            pause();
            cb.manual("学校要求手动验证或核对账号密码，请打开统一门户继续");
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (attempted) {
            if (now - submittedAt < 10_000) cb.waitMore(250);
            else {
                pause();
                cb.manual("登录后仍停留在认证页面，请打开统一门户完成验证或更新保存的密码");
            }
            return;
        }
        if (!SdjtuLoginForm.READY.equals(state)) {
            readySince = 0;
            if (now - pageSince < 10_000) cb.waitMore(150);
            else cb.manual("认证页面未显示可用的账号密码表单，请打开统一门户继续");
            return;
        }
        if (readySince == 0) {
            readySince = now;
            cb.waitMore(150);
            return;
        }
        if (now - readySince < 120) {
            cb.waitMore(150 - (now - readySince));
            return;
        }
        if (signingIn != null && signingIn != this) {
            cb.busy("另一项同步正在登录统一门户，请稍后再同步");
            return;
        }
        if (attempts.getLong(PAUSED_GENERATION, -1) == generation) {
            cb.manual("自动登录已暂停，请手动登录统一门户，或在设置中更新账号密码");
            return;
        }
        SdjtuCredentials.Account account = SdjtuCredentials.load(context);
        if (account == null) {
            cb.manual("请登录山东交通学院统一门户，或在设置中保存门户账号密码");
            return;
        }
        if (!stillSamePage.getAsBoolean()) return;
        // Record the guard before sending a password, including across abrupt process death.
        if (!attempts.edit().putLong(PAUSED_GENERATION, generation).commit()) {
            cb.manual("无法保存自动登录状态，请在统一门户中登录");
            return;
        }
        signingIn = this;
        attempted = true;
        submittedAt = now;
        SdjtuLoginForm.submit(web, account.username, account.password, submission -> {
            if (!stillSamePage.getAsBoolean()) return;
            if (SdjtuLoginForm.SUBMITTED.equals(submission)) cb.waitMore(250);
            else {
                pause();
                cb.manual("门户登录表单已变化，请打开统一门户完成验证");
            }
        });
    }

    void pause() {
        if (SdjtuCredentials.isSaved(context)) {
            attempts.edit().putLong(PAUSED_GENERATION, generation).apply();
        }
    }

    void onSuccess() {
        if ((attempted || pausedWhenStarted) && (signingIn == null || signingIn == this)
                && attempts.getLong(PAUSED_GENERATION, -1) == generation) {
            attempts.edit().remove(PAUSED_GENERATION).apply();
        }
    }

    void stop() {
        if (signingIn == this) signingIn = null;
    }
}
