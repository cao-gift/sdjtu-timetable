package com.ecut.campus;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.UUID;

/**
 * 课程作业与备注图片本地存储（filesDir/todo_photos/）：
 * 拍照和相册选图均压缩到最长边 1280px JPEG，私有目录保存，删除待办或清空数据时一并清理。
 */
final class TodoPhotoStore {

    private static final String DIR = "todo_photos";
    private static final int MAX_DIM = 1280;

    private TodoPhotoStore() {}

    static File dir(Context c) {
        File d = new File(c.getApplicationContext().getFilesDir(), DIR);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    static File fileOf(Context c, String name) {
        if (name == null || name.isEmpty() || name.contains("/") || name.contains("\\") || name.contains("..")) {
            return null;
        }
        return new File(dir(c), name);
    }

    static String newFilename() {
        return "tp_" + System.currentTimeMillis() + "_" + UUID.randomUUID().toString().substring(0, 6) + ".jpg";
    }

    static Uri uriForCapture(Context c, File dst) {
        return FileProvider.getUriForFile(c, c.getPackageName() + ".fileprovider", dst);
    }

    /** 从相册 Uri 导入并压缩保存，返回文件名；失败返回 null。 */
    static String importFromUri(Context c, Uri uri) {
        if (c == null || uri == null) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream is = c.getContentResolver().openInputStream(uri)) {
                if (is == null) return null;
                BitmapFactory.decodeStream(is, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;

            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_DIM);
            Bitmap bmp;
            try (InputStream is = c.getContentResolver().openInputStream(uri)) {
                if (is == null) return null;
                bmp = BitmapFactory.decodeStream(is, null, opts);
            }
            if (bmp == null) return null;
            String name = newFilename();
            File dst = new File(dir(c), name);
            boolean ok = writeScaled(bmp, dst);
            bmp.recycle();
            if (!ok) {
                dst.delete();
                return null;
            }
            return name;
        } catch (Throwable e) {
            DiagLog.error(c, "todo", "导入图片失败", e);
            return null;
        }
    }

    /** 拍照原图就地缩放压缩，减少存储占用。 */
    static boolean compressInPlace(File f) {
        if (f == null || !f.exists() || f.length() == 0) return false;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, MAX_DIM);
            Bitmap bmp = BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
            if (bmp == null) return false;
            File tmp = new File(f.getParentFile(), f.getName() + ".tmp");
            boolean ok = writeScaled(bmp, tmp);
            bmp.recycle();
            if (ok) {
                if (f.exists()) f.delete();
                return tmp.renameTo(f);
            }
            tmp.delete();
            return false;
        } catch (Throwable ignored) {
            return f.exists() && f.length() > 0;
        }
    }

    static Bitmap decodeThumb(Context c, String name, int targetPx) {
        File f = fileOf(c, name);
        if (f == null || !f.exists()) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(f.getAbsolutePath(), bounds);
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, Math.max(120, targetPx));
            return BitmapFactory.decodeFile(f.getAbsolutePath(), opts);
        } catch (Throwable ignored) {
            return null;
        }
    }

    static void delete(Context c, String name) {
        File f = fileOf(c, name);
        if (f != null && f.exists()) f.delete();
    }

    static void clearAll(Context c) {
        if (c == null) return;
        File d = new File(c.getApplicationContext().getFilesDir(), DIR);
        File[] files = d.listFiles();
        if (files != null) {
            for (File f : files) f.delete();
        }
    }

    private static int sampleSize(int w, int h, int maxDim) {
        int s = 1;
        while (w / s > maxDim * 2 || h / s > maxDim * 2) s *= 2;
        return s;
    }

    private static boolean writeScaled(Bitmap bmp, File dst) throws Exception {
        Bitmap out = bmp;
        int max = Math.max(bmp.getWidth(), bmp.getHeight());
        if (max > MAX_DIM) {
            float scale = MAX_DIM / (float) max;
            out = Bitmap.createScaledBitmap(bmp,
                    Math.max(1, Math.round(bmp.getWidth() * scale)),
                    Math.max(1, Math.round(bmp.getHeight() * scale)), true);
        }
        try (FileOutputStream fos = new FileOutputStream(dst)) {
            return out.compress(Bitmap.CompressFormat.JPEG, 82, fos);
        } finally {
            if (out != bmp) out.recycle();
        }
    }
}
