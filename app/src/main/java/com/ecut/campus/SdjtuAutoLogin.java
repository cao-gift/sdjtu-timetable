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
    private boolean explicitPause;
    private long submittedAt;
    private long readySince;
    private long manualSince;
    private long busySince;

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
        manualSince = 0;
        busySince = 0;
    }

    boolean credentialsChanged() {
        return SdjtuCredentials.isUpdating() || generation != SdjtuCredentials.generation(context);
    }

    boolean attempted() {
        return attempted;
    }

    void inspectAndSubmit(WebView web, long pageSince, BooleanSupplier stillSamePage, Callback cb) {
        long now = SystemClock.elapsedRealtime();
        if (!attempted) {
            if (signingIn != null && signingIn != this) {
                if (busySince == 0) busySince = now;
                if (now - busySince < 6_000) {
                    cb.waitMore(300);
                } else {
                    cb.busy("另一项同步正在登录统一门户，请稍后再同步");
                }
                return;
            }
            busySince = 0;
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
        long now = SystemClock.elapsedRealtime();
        if (SdjtuLoginForm.MANUAL.equals(state)) {
            if (!attempted) {
                // Angular 渲染登录页时，表单节点可能早于选项卡/隐藏字段/布局完成出现；
                // 短暂等待确认不是渲染瞬态，再决定暂停自动登录，避免误判失败。
                if (manualSince == 0) manualSince = now;
                if (now - manualSince < 900 && now - pageSince < 4_000) {
                    cb.waitMore(180);
                    return;
                }
            }
            pause();
            cb.manual("学校要求手动验证或核对账号密码，请打开统一门户继续");
            return;
        }
        manualSince = 0;
        if (attempted) {
            if (now - submittedAt < 18_000) cb.waitMore(200);
            else {
                pause();
                cb.manual("登录后仍停留在认证页面，请打开统一门户完成验证或更新保存的密码");
            }
            return;
        }
        if (!SdjtuLoginForm.READY.equals(state)) {
            readySince = 0;
            if (now - pageSince < 18_000) cb.waitMore(120);
            else cb.manual("认证页面未显示可用的账号密码表单，请打开统一门户继续");
            return;
        }
        if (readySince == 0) {
            readySince = now;
            cb.waitMore(70);
            return;
        }
        if (now - readySince < 60) {
            cb.waitMore(70 - (now - readySince));
            return;
        }
        if (signingIn != null && signingIn != this) {
            if (busySince == 0) busySince = now;
            if (now - busySince < 6_000) {
                cb.waitMore(300);
            } else {
                cb.busy("另一项同步正在登录统一门户，请稍后再同步");
            }
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
        explicitPause = false;
        submittedAt = now;
        SdjtuLoginForm.submit(web, account.username, account.password, submission -> {
            if (!stillSamePage.getAsBoolean()) return;
            if (SdjtuLoginForm.SUBMITTED.equals(submission)) cb.waitMore(200);
            else {
                pause();
                cb.manual("门户登录表单已变化，请打开统一门户完成验证");
            }
        });
    }

    void pause() {
        explicitPause = true;
        if (SdjtuCredentials.isSaved(context)) {
            attempts.edit().putLong(PAUSED_GENERATION, generation).apply();
        }
    }

    void onSuccess() {
        explicitPause = false;
        if ((attempted || pausedWhenStarted) && (signingIn == null || signingIn == this)
                && attempts.getLong(PAUSED_GENERATION, -1) == generation) {
            attempts.edit().remove(PAUSED_GENERATION).apply();
        }
    }

    void stop() {
        if (signingIn == this) {
            // 用户中途退出或网络超时取消（非认证拒登）时，解除进程防重标记，避免下次自动登录被误锁。
            if (attempted && !explicitPause
                    && attempts.getLong(PAUSED_GENERATION, -1) == generation) {
                attempts.edit().remove(PAUSED_GENERATION).apply();
            }
            signingIn = null;
        }
    }
}
