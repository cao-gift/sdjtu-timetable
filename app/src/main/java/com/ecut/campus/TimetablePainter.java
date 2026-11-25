package com.ecut.campus;

import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.text.StaticLayout;
import android.text.TextPaint;

import androidx.annotation.Nullable;

import com.ecut.campus.data.RainForecast;
import com.ecut.campus.data.Timetable;

import java.util.Random;

/**
 * 课表细节绘制辅助（从 TimetableView 拆出）：雨雾与雨丝、倒计时圆环、翻页接缝阴影、滚动提示条。
 */
final class TimetablePainter {

    private final Paint rainPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pageEdgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();
    private final float density;
    private final float scaledDensity;

    TimetablePainter(float density, float scaledDensity) {
        this.density = density;
        this.scaledDensity = scaledDensity;
        pageEdgePaint.setStyle(Paint.Style.FILL);
    }

    private float dp(float v) { return v * density; }
    private float sp(float v) { return v * scaledDensity; }

    /** 翻页接缝：一条细边加短距离渐变，明确这是两页，而不是两张网格叠在一起。 */
    void drawPageEdge(Canvas canvas, int direction, float neighborOffset,
                      float pageWidth, float headerHeight, int h) {
        float edgeX = direction > 0 ? neighborOffset : neighborOffset + pageWidth;
        float shadowWidth = dp(12);
        float shadowStart = direction > 0 ? edgeX - shadowWidth : edgeX;
        float shadowEnd = direction > 0 ? edgeX : edgeX + shadowWidth;

        pageEdgePaint.setShader(new LinearGradient(
                shadowStart, 0, shadowEnd, 0,
                direction > 0 ? new int[]{0x00000000, G.HAIR} : new int[]{G.HAIR, 0x00000000},
                null, Shader.TileMode.CLAMP));
        canvas.drawRect(Math.min(shadowStart, shadowEnd), headerHeight,
                Math.max(shadowStart, shadowEnd), h, pageEdgePaint);
        pageEdgePaint.setShader(null);

        pageEdgePaint.setColor(G.alpha(G.INK, 0x1F));
        canvas.drawRect(edgeX - dp(0.45f), 0, edgeX + dp(0.45f), h, pageEdgePaint);
    }

    /** 1. 静态雨蓝光晕。 */
    void drawRainOuterGlow(Canvas canvas, RectF rect, float radius, int rainLv, boolean live) {
        float glow = RainForecast.glowStrength(rainLv);
        if (glow <= 0f) return;
        int rainBlue = G.NIGHT ? RainForecast.RAIN_BLUE_NIGHT : RainForecast.RAIN_BLUE_DAY;
        float factor = live ? 0.5f : 1f;
        float k = glow * factor * (G.NIGHT ? 0.78f : 0.56f);
        float[] radiiDp = {7.2f, 5.6f, 4.2f, 3.0f, 2.0f, 1.0f};
        float[] weights = {0.10f, 0.14f, 0.18f, 0.22f, 0.26f, 0.30f};
        rainPaint.setShader(null);
        rainPaint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < radiiDp.length; i++) {
            float r = dp(radiiDp[i]);
            int a = Math.max(1, Math.min(255, Math.round(255f * weights[i] * k)));
            rainPaint.setColor(G.alpha(rainBlue, a));
            canvas.drawRoundRect(
                    new RectF(rect.left - r, rect.top - r, rect.right + r, rect.bottom + r),
                    radius + r, radius + r, rainPaint);
        }
    }

    /** 2. 卡内静态水汽。 */
    void drawRainInnerMist(Canvas canvas, RectF rect, float radius, int rainLv, boolean live) {
        float glow = RainForecast.glowStrength(rainLv);
        if (glow <= 0f) return;
        int rainBlue = G.NIGHT ? RainForecast.RAIN_BLUE_NIGHT : RainForecast.RAIN_BLUE_DAY;
        float factor = live ? 0.5f : 1f;
        int topA = Math.max(1, Math.min(255, Math.round(60f * glow * factor)));
        float mistH = rect.height() * 0.5f;
        if (mistH <= dp(4f)) return;
        canvas.save();
        clipPath.reset();
        clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.clipPath(clipPath);
        rainPaint.setStyle(Paint.Style.FILL);
        rainPaint.setShader(new LinearGradient(
                0, rect.top, 0, rect.top + mistH,
                new int[]{G.alpha(rainBlue, topA), G.alpha(rainBlue, topA / 4), 0x00000000},
                new float[]{0f, 0.5f, 1f},
                Shader.TileMode.CLAMP));
        canvas.drawRect(rect.left, rect.top, rect.right, rect.top + mistH, rainPaint);
        rainPaint.setShader(null);
        canvas.restore();
    }

    /** 3. 斜向静态细雨丝，避开已排版文字。 */
    void drawRainStreaks(Canvas canvas, RectF rect, Timetable.Course course, Timetable.Session s,
                         int textColor, int rainLv, float innerLeft, float innerTop,
                         float headingHeight, int mask, float gap,
                         @Nullable StaticLayout timeL, @Nullable StaticLayout teacherL,
                         float tagH, float liveTagW, @Nullable StaticLayout locL) {
        int n = RainForecast.streakCount(rainLv);
        if (n <= 0) return;
        float w = rect.width();
        float topBand = Math.max(rect.top + dp(22f), innerTop + headingHeight + dp(2f));
        float botBand = rect.bottom - dp(14f);
        if (botBand - topBand < dp(12f)) return;

        float[] boxTop = new float[6];
        float[] boxBot = new float[6];
        float[] boxRight = new float[6];
        int boxCount = 0;
        float curY = innerTop + headingHeight;
        if ((mask & 2) != 0 && timeL != null) {
            boxTop[boxCount] = curY + gap - dp(1.5f);
            boxBot[boxCount] = curY + gap + timeL.getHeight() + dp(1.5f);
            boxRight[boxCount] = innerLeft + maxLineWidth(timeL) + dp(3f);
            boxCount++;
            curY += gap + timeL.getHeight();
        }
        if ((mask & 4) != 0 && teacherL != null) {
            boxTop[boxCount] = curY + gap - dp(1.5f);
            boxBot[boxCount] = curY + gap + teacherL.getHeight() + dp(1.5f);
            boxRight[boxCount] = innerLeft + maxLineWidth(teacherL) + dp(3f);
            boxCount++;
            curY += gap + teacherL.getHeight();
        }
        if ((mask & 8) != 0 && tagH > 0f) {
            boxTop[boxCount] = curY + gap - dp(1.5f);
            boxBot[boxCount] = curY + gap + tagH + dp(1.5f);
            boxRight[boxCount] = innerLeft + liveTagW + dp(3f);
            boxCount++;
            curY += gap + tagH;
        }
        if ((mask & 1) != 0 && locL != null) {
            boxTop[boxCount] = curY + gap - dp(1.5f);
            boxBot[boxCount] = curY + gap + locL.getHeight() + dp(1.5f);
            boxRight[boxCount] = innerLeft + maxLineWidth(locL) + dp(3f);
            boxCount++;
        }

        float baseLen = dp(RainForecast.streakLenDp(rainLv));
        int baseAlpha = RainForecast.streakAlpha(rainLv);
        int seed = ((course.name != null ? course.name.hashCode() : 0) * 31 + s.dayOfWeek * 7 + s.startSection) ^ 0x5A3C;
        Random rnd = new Random(seed);
        final float cos70 = 0.34202015f;
        final float sin70 = 0.9396926f;

        rainPaint.setShader(null);
        rainPaint.setStyle(Paint.Style.STROKE);
        rainPaint.setStrokeCap(Paint.Cap.ROUND);
        rainPaint.setStrokeWidth(Math.max(1f, dp(0.9f)));
        float spanY = botBand - topBand;
        float maxRight = rect.right - dp(2.4f);
        for (int i = 0; i < n; i++) {
            float r1 = rnd.nextFloat(), r2 = rnd.nextFloat(), r3 = rnd.nextFloat();
            float u = (i + 0.5f) / n;
            float normY = (u + 0.07f * r1) % 1f;
            if (normY < 0f) normY += 1f;
            float sy = topBand + spanY * normY;
            float sx = rect.left + w * (0.45f + 0.50f * r2);
            float len = baseLen * (0.7f + 0.3f * r3);
            float ex = sx - len * cos70;
            float ey = sy + len * sin70;

            boolean skip = false;
            for (int b = 0; b < boxCount; b++) {
                if (ey >= boxTop[b] && sy <= boxBot[b] && ex < boxRight[b]) {
                    float shiftedSx = Math.max(sx, boxRight[b] + len * cos70 + dp(1.5f));
                    if (shiftedSx <= maxRight) {
                        sx = shiftedSx;
                        ex = sx - len * cos70;
                    } else {
                        skip = true;
                        break;
                    }
                }
            }
            if (skip || sx > maxRight) continue;

            float t = (sy - topBand) / spanY;
            float fd = Math.min(1f, t * 4f) * Math.min(1f, (1f - t) * 3f);
            int a = Math.round(baseAlpha * fd);
            if (a <= 4) continue;
            rainPaint.setColor(G.alpha(textColor, a));
            canvas.drawLine(sx, sy, ex, ey, rainPaint);
        }
    }

    private static float maxLineWidth(StaticLayout layout) {
        float max = 0f;
        for (int i = 0; i < layout.getLineCount(); i++) {
            max = Math.max(max, layout.getLineWidth(i));
        }
        return max;
    }

    /** 下一节课开始前 15 分钟倒计时圆环。 */
    void drawCountdown(Canvas canvas, RectF rect, int minutes, Paint accentPaint, TextPaint subTextPaint) {
        int color = G.alarm();
        float r = dp(8f);
        float cx = rect.right - r - dp(3f);
        float cy = rect.bottom - r - dp(3f);
        subTextPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        subTextPaint.setColor(color);
        subTextPaint.setAlpha(255);
        if (cy - r < rect.top + dp(14)) {
            subTextPaint.setTextSize(sp(8.5f));
            subTextPaint.setTextAlign(Paint.Align.RIGHT);
            canvas.drawText(minutes + "分后", rect.right - dp(4f), rect.bottom - dp(4f), subTextPaint);
            return;
        }
        accentPaint.setStyle(Paint.Style.STROKE);
        accentPaint.setStrokeWidth(dp(1.4f));
        accentPaint.setColor(G.alpha(G.INK, 0x22));
        canvas.drawCircle(cx, cy, r, accentPaint);
        accentPaint.setColor(color);
        float sweep = 360f * Math.max(0, 15 - minutes) / 15f;
        canvas.drawArc(new RectF(cx - r, cy - r, cx + r, cy + r), -90f, Math.max(sweep, 8f), false, accentPaint);
        accentPaint.setStyle(Paint.Style.FILL);
        subTextPaint.setTextSize(sp(7.5f));
        subTextPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(String.valueOf(minutes), cx, cy + dp(2.6f), subTextPaint);
        subTextPaint.setTextSize(sp(7f));
        subTextPaint.setTextAlign(Paint.Align.RIGHT);
        if (cx - r - dp(2) - subTextPaint.measureText("分后") > rect.left + dp(4)) {
            canvas.drawText("分后", cx - r - dp(2f), cy + dp(2.4f), subTextPaint);
        }
    }

    /** 右侧细滚动提示条。 */
    void drawScrollHint(Canvas canvas, int w, int h, float headerHeight,
                        float scrollOffset, float maxScrollOffset,
                        int visibleSections, int totalSections, Paint blockPaint) {
        if (maxScrollOffset <= 0) return;
        float trackTop = headerHeight + dp(4);
        float trackBottom = h - dp(4);
        float trackHeight = trackBottom - trackTop;
        float thumbHeight = Math.max(dp(24), trackHeight * visibleSections / Math.max(1, totalSections));
        float progress = Math.max(0f, Math.min(1f, scrollOffset / maxScrollOffset));
        float thumbTop = trackTop + (trackHeight - thumbHeight) * progress;

        blockPaint.setStyle(Paint.Style.FILL);
        blockPaint.setColor(G.STROKE);
        float x = w - dp(3);
        canvas.drawRoundRect(new RectF(x - dp(1.5f), thumbTop, x + dp(1.5f), thumbTop + thumbHeight),
                dp(2), dp(2), blockPaint);
    }
}
