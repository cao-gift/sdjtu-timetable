package com.ecut.campus.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

import com.ecut.campus.G;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 学期 GPA 细线刻度图（DESIGN.md 纸墨仪表风格）：
 * 细线横轨 + 墨线折线 + 空心圆点 + 选中/最新学期朱砂点，无彩色渐变与花哨图表。
 */
public class GpaGaugeChartView extends View {

    public static final class Point {
        public final String semesterId;
        public final String label;
        public final double gpa;

        public Point(String semesterId, String label, double gpa) {
            this.semesterId = semesterId;
            this.label = label;
            this.gpa = gpa;
        }
    }

    public interface OnPointSelectListener {
        void onSelect(String semesterId);
    }

    private final List<Point> points = new ArrayList<>();
    private String selectedId = "ALL";
    private OnPointSelectListener listener;

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint meanPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotStrokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path linePath = new Path();
    private float[] xs = new float[0];
    private float[] ys = new float[0];

    public GpaGaugeChartView(Context context) {
        this(context, null);
    }

    public GpaGaugeChartView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        gridPaint.setStyle(Paint.Style.STROKE);
        meanPaint.setStyle(Paint.Style.STROKE);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        dotFillPaint.setStyle(Paint.Style.FILL);
        dotStrokePaint.setStyle(Paint.Style.STROKE);
        textPaint.setTypeface(Typeface.MONOSPACE);
    }

    /** 供单测与绘制共用：计算相邻学期 GPA 变化文案（无颜色剧透，纯中性等宽数字）。 */
    public static String trendDeltaLabel(double prevGpa, double currGpa) {
        double delta = currGpa - prevGpa;
        if (Math.abs(delta) < 0.005) return "持平";
        return String.format(Locale.US, "%+.2f", delta);
    }

    public void setData(List<Point> list, String selectedSemesterId) {
        points.clear();
        if (list != null) points.addAll(list);
        selectedId = selectedSemesterId == null ? "ALL" : selectedSemesterId;
        setVisibility(points.size() >= 2 ? VISIBLE : GONE);
        invalidate();
    }

    public void setOnPointSelectListener(OnPointSelectListener l) {
        this.listener = l;
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
    private float sp(float v) { return v * getResources().getDisplayMetrics().scaledDensity; }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = Math.round(dp(136));
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int n = points.size();
        if (n < 2) return;
        int w = getWidth(), h = getHeight();
        float left = dp(34);
        float right = w - dp(16);
        float top = dp(34);
        float bottom = h - dp(24);
        float chartH = Math.max(dp(44), bottom - top);

        double minGpa = 5.0, maxGpa = 0.0, sumGpa = 0.0;
        for (Point p : points) {
            minGpa = Math.min(minGpa, p.gpa);
            maxGpa = Math.max(maxGpa, p.gpa);
            sumGpa += p.gpa;
        }
        double meanGpa = sumGpa / n;
        double floor = Math.max(0.0, Math.floor((minGpa - 0.3) * 2.0) / 2.0);
        double ceil = Math.min(5.0, Math.ceil((maxGpa + 0.25) * 2.0) / 2.0);
        if (ceil - floor < 1.0) ceil = Math.min(5.0, floor + 1.0);

        // 顶部等宽趋势摘要行（纯纸墨中性色，不做涨跌红绿剧透）
        int activeIdx = n - 1;
        for (int i = 0; i < n; i++) {
            if (points.get(i).semesterId.equals(selectedId)) {
                activeIdx = i;
                break;
            }
        }
        textPaint.setTextSize(sp(10f));
        textPaint.setTypeface(Typeface.MONOSPACE);
        textPaint.setTextAlign(Paint.Align.LEFT);
        textPaint.setColor(G.MUTE);
        canvas.drawText("GPA TREND · 学期趋势", 0, dp(13), textPaint);

        String summary = String.format(Locale.US, "均值 %.2f", meanGpa);
        if (activeIdx > 0) {
            summary = "较上学期 " + trendDeltaLabel(points.get(activeIdx - 1).gpa, points.get(activeIdx).gpa)
                    + "  ·  " + summary;
        }
        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setColor(G.SUB);
        canvas.drawText(summary, right, dp(13), textPaint);

        // 3 条发丝水平刻度线 + 左侧等宽标尺
        gridPaint.setColor(G.LINE);
        gridPaint.setStrokeWidth(Math.max(1f, dp(0.7f)));
        textPaint.setTextSize(sp(9.5f));
        textPaint.setTextAlign(Paint.Align.RIGHT);
        textPaint.setColor(G.MUTE);
        for (int i = 0; i <= 2; i++) {
            float frac = i / 2f;
            float y = top + chartH * frac;
            double val = ceil - (ceil - floor) * frac;
            canvas.drawLine(left, y, right, y, gridPaint);
            canvas.drawText(String.format(Locale.US, "%.1f", val), left - dp(6), y + dp(3.2f), textPaint);
        }

        // 均值虚线参考轨
        float meanNorm = (float) ((meanGpa - floor) / Math.max(0.1, ceil - floor));
        float meanY = bottom - chartH * Math.max(0f, Math.min(1f, meanNorm));
        meanPaint.setColor(G.alpha(G.MUTE, 0x99));
        meanPaint.setStrokeWidth(Math.max(1f, dp(0.8f)));
        meanPaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{dp(3), dp(3)}, 0));
        canvas.drawLine(left, meanY, right, meanY, meanPaint);

        xs = new float[n];
        ys = new float[n];
        float spanX = Math.max(1f, right - left);
        float padX = dp(10);
        float innerSpan = Math.max(1f, spanX - padX * 2);

        linePath.reset();
        for (int i = 0; i < n; i++) {
            Point p = points.get(i);
            xs[i] = left + padX + (n == 1 ? innerSpan / 2f : innerSpan * i / (float) (n - 1));
            float norm = (float) ((p.gpa - floor) / Math.max(0.1, ceil - floor));
            ys[i] = bottom - chartH * Math.max(0f, Math.min(1f, norm));
            if (i == 0) linePath.moveTo(xs[i], ys[i]);
            else linePath.lineTo(xs[i], ys[i]);
        }

        // 细墨折线
        linePaint.setColor(G.INK);
        linePaint.setStrokeWidth(dp(1.5f));
        canvas.drawPath(linePath, linePaint);

        // 竖向短刻度 + 圆点 + 读数（仅选中/最新学期 1 处用朱砂点）
        float r = dp(3.6f);
        dotStrokePaint.setStrokeWidth(dp(1.5f));
        textPaint.setTextAlign(Paint.Align.CENTER);
        int labelStep = n > 6 ? 2 : 1;
        for (int i = 0; i < n; i++) {
            Point p = points.get(i);
            boolean active = i == activeIdx;

            // 底部标尺短刻度
            gridPaint.setColor(active ? G.alarm() : G.MUTE);
            canvas.drawLine(xs[i], bottom, xs[i], bottom + dp(4f), gridPaint);

            dotFillPaint.setColor(active ? G.alarm() : G.PAPER);
            dotStrokePaint.setColor(active ? G.alarm() : G.INK);
            canvas.drawCircle(xs[i], ys[i], active ? r + dp(0.8f) : r, dotFillPaint);
            canvas.drawCircle(xs[i], ys[i], active ? r + dp(0.8f) : r, dotStrokePaint);

            // 点上方等宽 GPA 数值
            textPaint.setTextSize(sp(9.5f));
            textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, active ? Typeface.BOLD : Typeface.NORMAL));
            textPaint.setColor(active ? G.INK : G.SUB);
            canvas.drawText(String.format(Locale.US, "%.2f", p.gpa), xs[i], ys[i] - dp(7.5f), textPaint);

            // 底部等宽短学期名（学期过多时抽稀避免重叠，选中项始终显示）
            if (active || i == 0 || i == n - 1 || i % labelStep == 0) {
                textPaint.setTextSize(sp(9f));
                textPaint.setColor(active ? G.INK : G.MUTE);
                canvas.drawText(p.label, xs[i], bottom + dp(16f), textPaint);
            }
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_UP && listener != null && xs.length > 0) {
            float tx = event.getX();
            int best = -1;
            float bestDist = dp(32);
            for (int i = 0; i < xs.length; i++) {
                float d = Math.abs(tx - xs[i]);
                if (d < bestDist) {
                    bestDist = d;
                    best = i;
                }
            }
            if (best >= 0) {
                performClick();
                listener.onSelect(points.get(best).semesterId);
                return true;
            }
        }
        return event.getActionMasked() == MotionEvent.ACTION_DOWN || super.onTouchEvent(event);
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }
}
