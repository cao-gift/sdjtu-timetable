package com.ecut.campus.view;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;

import com.ecut.campus.G;

/**
 * 周数旋钮：竖向刻度滚轮，中央指针选中。
 * 上下拖动跟手、过格轻震、边缘弹性阻力与回弹、惯性减速吸附；点击刻度直接跳到该周。
 */
public class WeekDialView extends View {

    public interface OnWeekPicked { void onPicked(int week); }
    public interface OnWeekChanged { void onChanged(int week); }

    private int totalWeeks = 20;
    private int week = 1;
    private int actualWeek = 1;
    private float scrollPos = 0f;
    private OnWeekPicked pickListener;
    private OnWeekChanged changeListener;

    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ruler = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fade = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint center = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF centerBandRect = new RectF();
    private final RectF pointerRect = new RectF();
    private final PorterDuffXfermode dstOutMode = new PorterDuffXfermode(PorterDuff.Mode.DST_OUT);
    private LinearGradient topFadeShader;
    private LinearGradient bottomFadeShader;
    private final int touchSlop;

    private VelocityTracker velocityTracker;
    private float downY, lastY;
    private boolean dragging, dragMoved;
    private ValueAnimator fling;

    public WeekDialView(Context context) {
        super(context);
        tick.setTextAlign(Paint.Align.CENTER);
        center.setColor(G.INK);
        accent.setColor(G.alarm());
        ruler.setStyle(Paint.Style.STROKE);
        ruler.setStrokeCap(Paint.Cap.ROUND);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        float fadeH = dp(40);
        topFadeShader = new LinearGradient(0, 0, 0, fadeH, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP);
        bottomFadeShader = new LinearGradient(0, h, 0, h - fadeH, 0xFF000000, 0x00000000, Shader.TileMode.CLAMP);
    }

    public void configure(int current, int total, int actual) {
        totalWeeks = Math.max(1, total);
        actualWeek = Math.max(1, Math.min(totalWeeks, actual));
        setWeek(current);
    }

    public void setWeek(int w) {
        week = Math.max(1, Math.min(totalWeeks, w));
        scrollPos = week - 1;
        notifyChanged();
        invalidate();
    }

    public int week() { return week; }

    public void setOnWeekPicked(OnWeekPicked l) { pickListener = l; }
    public void setOnWeekChanged(OnWeekChanged l) { changeListener = l; }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    private void notifyChanged() {
        if (changeListener != null) changeListener.onChanged(week);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int layer = canvas.saveLayer(0, 0, getWidth(), getHeight(), null);
        int w = getWidth(), h = getHeight();
        float rowH = dp(40);
        float centerY = h / 2f;

        // 中央高亮带
        center.setColor(G.INK);
        center.setAlpha(24);
        centerBandRect.set(dp(8), centerY - rowH / 2, w - dp(8), centerY + rowH / 2);
        canvas.drawRoundRect(centerBandRect, dp(G.GLASS ? 14 : 6), dp(G.GLASS ? 14 : 6), center);
        center.setAlpha(255);

        int first = (int) Math.floor(scrollPos - h / 2f / rowH - 1);
        int last = (int) Math.ceil(scrollPos + h / 2f / rowH + 1);
        for (int w1 = first; w1 <= last; w1++) {
            if (w1 < 0 || w1 >= totalWeeks) continue;
            float y = centerY + (w1 - scrollPos) * rowH;
            float dist = Math.min(1f, Math.abs(y - centerY) / Math.max(1f, h / 2f));
            int n = w1 + 1;
            boolean isCurrent = n == week;
            boolean isActual = n == actualWeek;

            // 两侧精密刻度线（每 5 周长刻度，其余短刻度）
            boolean major = (n == 1) || (n % 5 == 0) || isCurrent;
            float tickLen = dp(major ? 12f : 6f);
            ruler.setStrokeWidth(dp(isCurrent ? 1.5f : 0.9f));
            ruler.setColor(isCurrent ? G.INK : blend(G.MUTE, dist));
            canvas.drawLine(dp(18), y, dp(18) + tickLen, y, ruler);
            canvas.drawLine(w - dp(18) - tickLen, y, w - dp(18), y, ruler);

            String num = n < 10 ? "0" + n : String.valueOf(n);
            String label = "第 " + num + " 周" + (isActual ? " · 本周" : "");
            tick.setTextSize(dp(isCurrent ? 17.5f : 15.5f));
            tick.setTypeface(Typeface.create(Typeface.MONOSPACE, isCurrent ? Typeface.BOLD : Typeface.NORMAL));
            tick.setColor(isCurrent ? (isActual ? G.alarm() : G.INK) : blend(G.SUB2, dist));
            canvas.drawText(label, w / 2f, y + dp(6), tick);

            if (isActual && !isCurrent) {
                accent.setAlpha(Math.round(255 * Math.max(0.25f, 1f - dist * 0.7f)));
                canvas.drawCircle(w - dp(36), y, dp(2.4f), accent);
            }
        }

        // 指针侧条
        center.setColor(week == actualWeek ? G.alarm() : G.INK);
        center.setAlpha(255);
        pointerRect.set(dp(6), centerY - dp(13), dp(10), centerY + dp(13));
        canvas.drawRoundRect(pointerRect, dp(2), dp(2), center);

        if (topFadeShader != null && bottomFadeShader != null) {
            float fadeH = dp(40);
            fade.setXfermode(dstOutMode);
            fade.setShader(topFadeShader);
            canvas.drawRect(0, 0, w, fadeH, fade);
            fade.setShader(bottomFadeShader);
            canvas.drawRect(0, h - fadeH, w, h, fade);
            fade.setShader(null);
            fade.setXfermode(null);
        }
        canvas.restoreToCount(layer);
    }

    private static int blend(int color, float dist) {
        int alpha = (int) (0x99 * Math.max(0f, 1f - dist * 0.82f));
        return (alpha << 24) | (color & 0x00FFFFFF);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
        velocityTracker.addMovement(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                if (fling != null) { fling.cancel(); fling = null; }
                dragging = true;
                dragMoved = false;
                downY = lastY = event.getY();
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!dragging) return true;
                float y = event.getY();
                if (!dragMoved && Math.abs(y - downY) < touchSlop) return true;
                if (!dragMoved) {
                    dragMoved = true;
                    lastY = y;
                    return true;
                }
                float dy = y - lastY;
                lastY = y;
                float rowH = dp(40);
                float delta = -dy / rowH;
                // 允许边缘轻微越界弹性拖动（最多 0.65 格），松手回弹
                float maxPos = totalWeeks - 1;
                boolean outOfBounds = (scrollPos < 0f && delta < 0f) || (scrollPos > maxPos && delta > 0f);
                if (outOfBounds) delta *= 0.28f;
                scrollPos = Math.max(-0.65f, Math.min(maxPos + 0.65f, scrollPos + delta));
                updateWeek(true);
                invalidate();
                return true;
            }
            case MotionEvent.ACTION_UP: {
                dragging = false;
                velocityTracker.computeCurrentVelocity(1000);
                float vy = velocityTracker.getYVelocity();
                velocityTracker.recycle();
                velocityTracker = null;
                float rowH = dp(40);
                if (!dragMoved && Math.abs(event.getY() - downY) < touchSlop) {
                    int tapped = Math.max(1, Math.min(totalWeeks,
                            Math.round(scrollPos + (event.getY() - getHeight() / 2f) / rowH) + 1));
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    setWeek(tapped);
                    if (pickListener != null) pickListener.onPicked(week);
                    return true;
                }
                float maxPos = totalWeeks - 1;
                boolean overscrolled = scrollPos < 0f || scrollPos > maxPos;
                float target;
                if (overscrolled) {
                    target = scrollPos < 0f ? 0f : maxPos;
                } else {
                    float flingPx = vy * 0.30f;
                    target = Math.max(0f, Math.min(maxPos, Math.round(scrollPos - flingPx / rowH)));
                }
                animateTo(target, overscrolled);
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                dragging = false;
                if (velocityTracker != null) { velocityTracker.recycle(); velocityTracker = null; }
                float clamped = Math.max(0f, Math.min(totalWeeks - 1, Math.round(scrollPos)));
                animateTo(clamped, scrollPos < 0f || scrollPos > totalWeeks - 1);
                return true;
        }
        return super.onTouchEvent(event);
    }

    private void updateWeek(boolean haptic) {
        int w = Math.max(1, Math.min(totalWeeks, Math.round(scrollPos) + 1));
        if (w != week) {
            week = w;
            if (haptic) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            notifyChanged();
        }
    }

    private void animateTo(float target, boolean bounce) {
        if (fling != null) fling.cancel();
        if (!ValueAnimator.areAnimatorsEnabled()) {
            scrollPos = target;
            updateWeek(false);
            invalidate();
            return;
        }
        final float start = scrollPos;
        float dist = Math.abs(target - start);
        long dur = dist < 0.01f ? 0 : Math.round(160 + 180 * Math.sqrt(Math.min(1f, dist / 6f)));
        fling = ValueAnimator.ofFloat(0f, 1f);
        fling.setDuration(dur);
        fling.setInterpolator(bounce ? new OvershootInterpolator(1.35f) : new DecelerateInterpolator(2.0f));
        fling.addUpdateListener(a -> {
            scrollPos = start + (target - start) * (float) a.getAnimatedValue();
            updateWeek(true);
            invalidate();
        });
        fling.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator a) {
                fling = null;
                scrollPos = target;
                updateWeek(false);
                invalidate();
            }
        });
        fling.start();
    }
}
