package com.ecut.campus;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;

import androidx.annotation.Nullable;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.GlobalHistogramBinarizer;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;

/** 本地生成与识别二维码（ZXing core，Apache-2.0）。免 CAMERA 权限。 */
public final class QrBitmap {
    private QrBitmap() {}

    /** 超出二维码容量返回 null。 */
    public static Bitmap make(String text, int size, int ink, int paper) {
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L);
            hints.put(EncodeHintType.MARGIN, 1);
            BitMatrix m = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            int w = m.getWidth(), h = m.getHeight();
            int[] px = new int[w * h];
            for (int y = 0; y < h; y++)
                for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? ink : paper;
            return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888);
        } catch (Exception e) {
            return null;
        }
    }

    /** 从图片 Uri 识别二维码文本，识别失败返回 null。 */
    @Nullable
    public static String decodeUri(Context ctx, Uri uri) {
        if (ctx == null || uri == null) return null;
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
                if (is == null) return null;
                BitmapFactory.decodeStream(is, null, bounds);
            }
            int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (maxDim / sample > 1280) sample *= 2;
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            opts.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp;
            try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
                if (is == null) return null;
                bmp = BitmapFactory.decodeStream(is, null, opts);
            }
            if (bmp == null) return null;
            try {
                return decode(bmp);
            } finally {
                bmp.recycle();
            }
        } catch (Throwable ignored) {
            return null;
        }
    }

    /** 从 Bitmap 识别二维码文本，识别失败返回 null。 */
    @Nullable
    public static String decode(Bitmap bmp) {
        if (bmp == null) return null;
        int w = bmp.getWidth(), h = bmp.getHeight();
        if (w <= 0 || h <= 0) return null;
        int[] px = new int[w * h];
        bmp.getPixels(px, 0, w, 0, 0, w, h);
        RGBLuminanceSource src = new RGBLuminanceSource(w, h, px);
        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
        QRCodeReader reader = new QRCodeReader();
        try {
            Result r = reader.decode(new BinaryBitmap(new HybridBinarizer(src)), hints);
            if (r != null && r.getText() != null) return r.getText();
        } catch (Throwable ignored) {
        }
        try {
            reader.reset();
            Result r = reader.decode(new BinaryBitmap(new GlobalHistogramBinarizer(src)), hints);
            if (r != null && r.getText() != null) return r.getText();
        } catch (Throwable ignored) {
        }
        return null;
    }
}
