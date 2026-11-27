package com.ecut.campus;

import android.content.Context;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import com.ecut.campus.data.Timetable;
import com.ecut.campus.data.TimetableStore;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 后台约每 6 小时静默同步一次当前学期课表；
 * 若 {@link com.ecut.campus.data.SyncDiff} 发现时间、教室或课程变动，发出调课提醒通知。
 */
public class TimetableCheckWorker extends Worker {
    private static final String NAME = "timetable_change_check";

    public TimetableCheckWorker(@NonNull Context c, @NonNull WorkerParameters p) {
        super(c, p);
    }

    public static void schedule(Context ctx) {
        WorkManager wm = WorkManager.getInstance(ctx);
        if (!Prefs.scheduleChangeNotify(ctx)) {
            wm.cancelUniqueWork(NAME);
            return;
        }
        PeriodicWorkRequest req = new PeriodicWorkRequest.Builder(TimetableCheckWorker.class, 6, TimeUnit.HOURS)
                .setConstraints(new Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setInitialDelay(3, TimeUnit.HOURS)
                .build();
        wm.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req);
    }

    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();
        if (!Prefs.scheduleChangeNotify(ctx)) {
            return Result.success();
        }
        // 本地尚无课表时不跑后台调课比对（避免首次同步前空跑）
        Timetable local = TimetableStore.load(ctx);
        if (local == null || local.courses.isEmpty()) {
            return Result.success();
        }
        boolean sessionReady = Prefs.hasPortalSession(ctx) && !Prefs.sessionExpired(ctx);
        boolean canSignIn = SdjtuCredentials.isSaved(ctx)
                && !CampusSyncClient.sdjtuLoginPaused(ctx);
        if (!sessionReady && !canSignIn) {
            return Result.success();
        }
        CountDownLatch done = new CountDownLatch(1);
        final CampusSyncClient[] client = new CampusSyncClient[1];
        final AtomicBoolean aborted = new AtomicBoolean(false);
        Bg.main(() -> {
            if (aborted.get()) return;
            try {
                client[0] = CampusSyncClient.startLazyBackground(ctx, () -> new WebView(ctx),
                        CampusSyncClient.Kind.TIMETABLE, new CampusSyncClient.Callback() {
                            @Override public void onSuccess(int count) { done.countDown(); }
                            @Override public void onFailure(String message) { done.countDown(); }
                        });
            } catch (Throwable t) {
                done.countDown();
            }
        });
        try {
            done.await(90, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        } finally {
            aborted.set(true);
            Bg.main(() -> {
                if (client[0] != null) client[0].cancel();
            });
        }
        return Result.success();
    }
}
