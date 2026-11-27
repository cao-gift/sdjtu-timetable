package com.ecut.campus;

import android.os.Handler;
import android.os.Looper;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 统一后台线程池与主线程回调工具，替代分散的裸 {@code new Thread(...)}。
 */
public final class Bg {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final AtomicInteger SEQ = new AtomicInteger(1);
    private static final ExecutorService POOL = Executors.newFixedThreadPool(4, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(() -> {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
                r.run();
            }, "lvlv-bg-" + SEQ.getAndIncrement());
            t.setDaemon(true);
            return t;
        }
    });

    private Bg() {}

    /** 在后台线程池执行任务。 */
    public static void run(Runnable task) {
        if (task == null) return;
        POOL.execute(task);
    }

    /** 延迟指定毫秒后在后台线程池执行任务。 */
    public static void runDelayed(long delayMs, Runnable task) {
        if (task == null) return;
        if (delayMs <= 0L) {
            POOL.execute(task);
            return;
        }
        MAIN.postDelayed(() -> POOL.execute(task), delayMs);
    }

    /** 后台计算并在主线程回传结果。 */
    public static <T> void io(Supplier<T> work, Consumer<T> onMain) {
        if (work == null) return;
        POOL.execute(() -> {
            T result = work.get();
            if (onMain != null) {
                MAIN.post(() -> onMain.accept(result));
            }
        });
    }

    /** 切回主线程执行。 */
    public static void main(Runnable task) {
        if (task == null) return;
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run();
        } else {
            MAIN.post(task);
        }
    }
}
