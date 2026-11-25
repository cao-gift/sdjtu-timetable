package com.ecut.campus;

import android.content.Context;

import com.ecut.campus.data.CourseTodo;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** 课程作业与待办本地持久化（filesDir/course_todos.json，原子写入）。 */
public final class CourseTodoStore {

    private static final String FILE = "course_todos.json";
    private static final String TMP = "course_todos.json.tmp";

    private CourseTodoStore() {}

    public static CourseTodo load(Context ctx) {
        if (ctx == null) return new CourseTodo();
        File f = new File(ctx.getApplicationContext().getFilesDir(), FILE);
        if (!f.exists()) return new CourseTodo();
        try (FileInputStream in = new FileInputStream(f);
             ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return CourseTodo.fromJson(bos.toString("UTF-8"));
        } catch (Throwable ignored) {
            return new CourseTodo();
        }
    }

    public static boolean save(Context ctx, CourseTodo book) {
        if (ctx == null || book == null) return false;
        File dir = ctx.getApplicationContext().getFilesDir();
        File tmp = new File(dir, TMP);
        File dst = new File(dir, FILE);
        try {
            String json = book.toJson().toString();
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(json.getBytes(StandardCharsets.UTF_8));
                fos.flush();
                fos.getFD().sync();
            }
            if (dst.exists()) dst.delete();
            return tmp.renameTo(dst);
        } catch (Throwable ignored) {
            if (tmp.exists()) tmp.delete();
            return false;
        }
    }

    public static void clear(Context ctx) {
        if (ctx == null) return;
        File dir = ctx.getApplicationContext().getFilesDir();
        new File(dir, FILE).delete();
        new File(dir, TMP).delete();
        TodoPhotoStore.clearAll(ctx);
    }
}
