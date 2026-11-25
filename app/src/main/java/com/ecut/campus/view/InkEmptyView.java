package com.ecut.campus.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.ecut.campus.G;

/**
 * 统一「墨线」风格空状态插画（DESIGN.md 纸墨仪表风）：
 * 纯发丝线条 + 一点朱砂，不引入位图，自动适配浅色 / 深色 / 磨砂玻璃。
 */
public class InkEmptyView extends View {

    public static final int KIND_GRADES = 0;
    public static final int KIND_EXAMS = 1;
    public static final int KIND_ROOMS = 2;
    public static final int KIND_PLAN = 3;

    private int kind = KIND_GRADES;
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mute = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);

    public InkEmptyView(Context context) {
        this(context, null);
    }

    public InkEmptyView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeCap(Paint.Cap.ROUND);
        ink.setStrokeJoin(Paint.Join.ROUND);
        mute.setStyle(Paint.Style.STROKE);
        mute.setStrokeCap(Paint.Cap.ROUND);
        dash.setStyle(Paint.Style.STROKE);
        accent.setStyle(Paint.Style.FILL);
    }

    public void setKind(int kind) {
        this.kind = kind;
        invalidate();
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int s = Math.round(dp(72));
        setMeasuredDimension(resolveSize(s, widthMeasureSpec), resolveSize(s, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        ink.setColor(G.INK);
        ink.setStrokeWidth(dp(1.4f));
        mute.setColor(G.MUTE);
        mute.setStrokeWidth(dp(1f));
        dash.setColor(G.MUTE);
        dash.setStrokeWidth(dp(1f));
        dash.setPathEffect(new DashPathEffect(new float[]{dp(3), dp(3)}, 0));
        accent.setColor(G.alarm());

        if (kind == KIND_EXAMS) {
            // 精密刻度表盘 + 指针 + 朱砂刻度点
            float r = Math.min(w, h) * 0.36f;
            canvas.drawCircle(cx, cy, r, ink);
            for (int i = 0; i < 12; i++) {
                double rad = Math.toRadians(i * 30 - 90);
                float r1 = r - dp(i % 3 == 0 ? 5.5f : 3f);
                canvas.drawLine((float) (cx + Math.cos(rad) * r1), (float) (cy + Math.sin(rad) * r1),
                        (float) (cx + Math.cos(rad) * (r - dp(1.5f))), (float) (cy + Math.sin(rad) * (r - dp(1.5f))), mute);
            }
            canvas.drawLine(cx, cy, cx, cy - r * 0.52f, ink);
            canvas.drawLine(cx, cy, cx + r * 0.36f, cy + r * 0.22f, ink);
            canvas.drawCircle(cx, cy, dp(2.4f), accent);
        } else if (kind == KIND_ROOMS) {
            // 教室平面图草线 + 门弧 + 朱砂标记
            float hw = w * 0.35f, hh = h * 0.28f;
            canvas.drawRoundRect(new RectF(cx - hw, cy - hh, cx + hw, cy + hh), dp(4), dp(4), ink);
            canvas.drawLine(cx - hw * 0.15f, cy - hh, cx - hw * 0.15f, cy + hh * 0.2f, mute);
            canvas.drawLine(cx - hw, cy + hh * 0.1f, cx + hw * 0.25f, cy + hh * 0.1f, dash);
            canvas.drawCircle(cx + hw * 0.48f, cy - hh * 0.35f, dp(2.8f), accent);
        } else if (kind == KIND_PLAN) {
            // 书页刻度与目录线
            float hw = w * 0.30f, hh = h * 0.34f;
            canvas.drawRoundRect(new RectF(cx - hw, cy - hh, cx + hw, cy + hh), dp(4), dp(4), ink);
            canvas.drawLine(cx - hw + dp(7), cy - hh, cx - hw + dp(7), cy + hh, mute);
            canvas.drawLine(cx - hw + dp(13), cy - dp(8), cx + hw - dp(7), cy - dp(8), ink);
            canvas.drawLine(cx - hw + dp(13), cy, cx + hw - dp(11), cy, mute);
            canvas.drawLine(cx - hw + dp(13), cy + dp(8), cx + hw - dp(14), cy + dp(8), dash);
            canvas.drawCircle(cx + hw - dp(7), cy + dp(8), dp(2.5f), accent);
        } else {
            // 成绩刻度尺 + 空折线槽
            float hw = w * 0.36f, hh = h * 0.30f;
            canvas.drawRoundRect(new RectF(cx - hw, cy - hh, cx + hw, cy + hh), dp(4), dp(4), ink);
            for (int i = 0; i < 5; i++) {
                float x = cx - hw + dp(8) + i * ((hw * 2 - dp(16)) / 4f);
                canvas.drawLine(x, cy + hh, x, cy + hh - dp(i % 2 == 0 ? 6 : 3.5f), mute);
            }
            canvas.drawLine(cx - hw + dp(10), cy + dp(4), cx - dp(4), cy - dp(5), dash);
            canvas.drawLine(cx - dp(4), cy - dp(5), cx + hw - dp(10), cy - dp(10), ink);
            canvas.drawCircle(cx + hw - dp(10), cy - dp(10), dp(2.8f), accent);
        }
    }
}
