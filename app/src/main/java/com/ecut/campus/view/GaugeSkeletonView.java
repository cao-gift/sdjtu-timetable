package com.ecut.campus.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.ecut.campus.G;

/**
 * 纸墨风格加载骨架屏（替代转圈 ProgressBar）：发丝分隔线 + 等宽索引条 + 呼吸淡入占位块。
 * 系统关闭动画时保持静态。
 */
public class GaugeSkeletonView extends View {

    private int rows = 5;
    private float pulse = 0.5f;
    private ValueAnimator animator;
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();

    public GaugeSkeletonView(Context context) {
        this(context, null);
    }

    public GaugeSkeletonView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        barPaint.setStyle(Paint.Style.FILL);
        linePaint.setStyle(Paint.Style.STROKE);
    }

    public void setRows(int rows) {
        this.rows = Math.max(2, Math.min(8, rows));
        requestLayout();
        invalidate();
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = Math.round(rows * dp(64));
        setMeasuredDimension(w, resolveSize(h, heightMeasureSpec));
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        syncAnimator();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopAnimator();
        super.onDetachedFromWindow();
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        syncAnimator();
    }

    private void syncAnimator() {
        if (getVisibility() == VISIBLE && isAttachedToWindow() && ValueAnimator.areAnimatorsEnabled()) {
            if (animator == null) {
                animator = ValueAnimator.ofFloat(0.35f, 0.85f);
                animator.setDuration(900);
                animator.setRepeatMode(ValueAnimator.REVERSE);
                animator.setRepeatCount(ValueAnimator.INFINITE);
                animator.addUpdateListener(a -> {
                    pulse = (float) a.getAnimatedValue();
                    invalidate();
                });
                animator.start();
            }
        } else {
            stopAnimator();
            pulse = 0.55f;
        }
    }

    private void stopAnimator() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth();
        float rowH = dp(64);
        float r = dp(G.GLASS ? 8 : 3);
        int alpha = Math.round(255 * 0.14f * pulse);
        barPaint.setColor(G.alpha(G.INK, Math.max(10, Math.min(60, alpha))));
        linePaint.setColor(G.LINE);
        linePaint.setStrokeWidth(1f);

        float[] titleWidths = {0.48f, 0.36f, 0.54f, 0.42f, 0.50f};
        for (int i = 0; i < rows; i++) {
            float y = i * rowH;
            if (i > 0) canvas.drawLine(0, y, w, y, linePaint);
            // 序号占位
            rect.set(0, y + dp(18), dp(18), y + dp(28));
            canvas.drawRoundRect(rect, r, r, barPaint);
            // 主标题占位
            float tw = w * titleWidths[i % titleWidths.length];
            rect.set(dp(28), y + dp(16), dp(28) + tw, y + dp(30));
            canvas.drawRoundRect(rect, r, r, barPaint);
            // 副标题占位
            rect.set(dp(28), y + dp(38), dp(28) + tw * 0.68f, y + dp(47));
            canvas.drawRoundRect(rect, r, r, barPaint);
            // 右侧数值占位
            rect.set(w - dp(44), y + dp(18), w, y + dp(36));
            canvas.drawRoundRect(rect, r, r, barPaint);
        }
    }
}
