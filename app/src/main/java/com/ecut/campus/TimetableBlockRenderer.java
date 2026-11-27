package com.ecut.campus;

import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

import com.ecut.campus.data.AbsenceBook;
import com.ecut.campus.data.ClassroomLabel;
import com.ecut.campus.data.CourseTodo;
import com.ecut.campus.data.RainForecast;
import com.ecut.campus.data.Timetable;
import com.ecut.campus.data.TimetableLayout;

import java.util.List;
import java.util.Map;
import java.util.Set;

import androidx.annotation.Nullable;

/**
 * 课表课块、文字排版、现在线与空白画课虚框渲染器（从 TimetableView 拆出）。
 */
final class TimetableBlockRenderer {

    private final Paint blockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clipPath = new Path();
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint bpSub = new TextPaint();
    private final TextPaint bpMono = new TextPaint();
    private final TextPaint bpTagCn = new TextPaint();
    private final TextPaint bpTagNum = new TextPaint();

    private final TimetablePainter painter;
    private final float density;
    private final float scaledDensity;

    TimetableBlockRenderer(TimetablePainter painter, float density, float scaledDensity) {
        this.painter = painter;
        this.density = density;
        this.scaledDensity = scaledDensity;
    }

    private float dp(float v) { return v * density; }
    private float sp(float v) { return v * scaledDensity; }

    /** 绘制单个课块背景（含正在上课进度、雨效、倒计时、错层叠卡切边与非本周虚框），返回最终文字色。 */
    int drawBlockChrome(Canvas canvas, RectF rect, int colorIdx, boolean isActive, String tag,
                        int stackDepth, float progress, boolean endingSoon, int rainLv, int minutesUntil) {
        int bgColor = TimetableView.PALETTE_BG[colorIdx];
        int fgColor = TimetableView.PALETTE_FG[colorIdx];
        float radius = G.GLASS ? dp(10f) : dp(3f);
        int textColor = isActive ? fgColor : G.MUTE;
        boolean live = progress >= 0f;

        if (isActive && stackDepth > 0) {
            // 错层冲突上层叠卡：先描一圈纸底/柔白切缝光环，与底层课程块清晰剥离
            blockPaint.setStyle(Paint.Style.STROKE);
            blockPaint.setStrokeWidth(dp(2.2f));
            blockPaint.setColor(G.GLASS ? (G.NIGHT ? 0xDD141413 : 0xEBFFFFFF) : G.CARD);
            canvas.drawRoundRect(rect, radius, radius, blockPaint);
            blockPaint.setStyle(Paint.Style.FILL);
        }

        if (rainLv > RainForecast.NONE) {
            painter.drawRainOuterGlow(canvas, rect, radius, rainLv, live);
        }
        if (isActive) {
            if (live) {
                int fill = bgColor;
                if (G.GLASS) {
                    int glass = TimetableView.PALETTE_GLASS[colorIdx];
                    fill = G.NIGHT ? G.alpha(G.mix(glass, 0xFF000000, 0.58f), 0xC8) : G.alpha(glass, 0xD6);
                    textColor = TimetableView.glassInk(colorIdx);
                }
                blockPaint.setStyle(Paint.Style.FILL);
                blockPaint.setColor(fill);
                canvas.drawRoundRect(rect, radius, radius, blockPaint);
                if (rainLv > RainForecast.NONE) {
                    painter.drawRainInnerMist(canvas, rect, radius, rainLv, true);
                }
                canvas.save();
                clipPath.reset();
                clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW);
                canvas.clipPath(clipPath);
                blockPaint.setColor(G.alpha(G.alarm(), endingSoon ? 0x26 : 0x18));
                float split = rect.top + rect.height() * progress;
                canvas.drawRect(rect.left, rect.top, rect.right, split, blockPaint);
                blockPaint.setColor(G.alpha(G.alarm(), 0xB8));
                float hl = Math.max(1f, dp(0.8f));
                canvas.drawRect(rect.left, split - hl / 2f, rect.right, split + hl / 2f, blockPaint);
                if (endingSoon) {
                    blockPaint.setColor(G.alpha(G.alarm(), 0x38));
                    canvas.drawRect(rect.left, rect.bottom - dp(5f), rect.right, rect.bottom, blockPaint);
                }
                accentPaint.setStyle(Paint.Style.FILL);
                accentPaint.setColor(G.alpha(G.alarm(), 0x38));
                canvas.drawRect(rect.left, rect.top, rect.left + dp(3f), rect.bottom, accentPaint);
                accentPaint.setColor(G.alarm());
                canvas.drawRect(rect.left, rect.top, rect.left + dp(3f), split, accentPaint);
                canvas.restore();
                if (G.GLASS) {
                    blockPaint.setStyle(Paint.Style.STROKE);
                    blockPaint.setStrokeWidth(Math.max(1f, dp(0.8f)));
                    blockPaint.setColor(G.alpha(0xFFFFFFFF, G.NIGHT ? 0x26 : 0xCC));
                    canvas.drawRoundRect(rect, radius, radius, blockPaint);
                }
                float sw = dp(2f), in = sw / 2f + (G.GLASS ? dp(0.8f) : 0f);
                blockPaint.setStyle(Paint.Style.STROKE);
                blockPaint.setStrokeWidth(sw);
                blockPaint.setColor(G.alarm());
                canvas.drawRoundRect(new RectF(rect.left + in, rect.top + in, rect.right - in, rect.bottom - in),
                        Math.max(0f, radius - in), Math.max(0f, radius - in), blockPaint);
                blockPaint.setStyle(Paint.Style.FILL);
            } else if (G.GLASS) {
                blockPaint.setStyle(Paint.Style.FILL);
                int glass = TimetableView.PALETTE_GLASS[colorIdx];
                blockPaint.setColor(G.NIGHT ? G.alpha(G.mix(glass, 0xFF000000, 0.58f), 0xC8) : G.alpha(glass, 0xD6));
                canvas.drawRoundRect(rect, radius, radius, blockPaint);
                if (rainLv > RainForecast.NONE) {
                    painter.drawRainInnerMist(canvas, rect, radius, rainLv, false);
                }
                blockPaint.setStyle(Paint.Style.STROKE);
                blockPaint.setStrokeWidth(Math.max(1f, dp(0.8f)));
                blockPaint.setColor(G.alpha(0xFFFFFFFF, G.NIGHT ? 0x26 : 0xCC));
                canvas.drawRoundRect(rect, radius, radius, blockPaint);
                textColor = TimetableView.glassInk(colorIdx);
            } else {
                blockPaint.setStyle(Paint.Style.FILL);
                blockPaint.setColor(bgColor);
                canvas.drawRoundRect(rect, radius, radius, blockPaint);
                if (rainLv > RainForecast.NONE) {
                    painter.drawRainInnerMist(canvas, rect, radius, rainLv, false);
                }
                accentPaint.setStyle(Paint.Style.FILL);
                accentPaint.setColor(fgColor);
                canvas.drawRect(rect.left, rect.top, rect.left + dp(2f), rect.bottom, accentPaint);
            }

            if (minutesUntil >= 0 && minutesUntil <= 15 && !live) {
                painter.drawCountdown(canvas, rect, minutesUntil, accentPaint, subTextPaint);
            }
        } else {
            blockPaint.setStyle(Paint.Style.STROKE);
            blockPaint.setStrokeWidth(Math.max(1f, dp(0.7f)));
            blockPaint.setColor(tag != null ? G.alpha(G.ACCENT, 0x66) : G.alpha(G.INK, 0x24));
            blockPaint.setPathEffect(new DashPathEffect(new float[]{dp(3), dp(2.5f)}, 0));
            canvas.drawRoundRect(rect, radius, radius, blockPaint);
            blockPaint.setPathEffect(null);
        }
        return textColor;
    }

    /** 绘制考试卡片底框。 */
    void drawExamChrome(Canvas canvas, RectF rect) {
        float radius = G.GLASS ? dp(10f) : dp(3f);
        blockPaint.setStyle(Paint.Style.FILL);
        blockPaint.setColor(G.alpha(G.alarm(), G.NIGHT ? 0x30 : 0x1E));
        canvas.drawRoundRect(rect, radius, radius, blockPaint);
        blockPaint.setStyle(Paint.Style.STROKE);
        blockPaint.setStrokeWidth(dp(1.5f));
        blockPaint.setColor(G.alarm());
        canvas.drawRoundRect(rect, radius, radius, blockPaint);
    }

    /** 绘制课块内文字、雨丝、冲突叠卡标签、待办胶囊、备注点与缺勤警告线。 */
    void drawBlockText(Canvas canvas, RectF rect, RectF textRect, float headerHeight,
                       Timetable.Course course, Timetable.Session s, int currentWeek,
                       boolean isActive, int textColor, String tag, int liveLeft, int rainLv,
                       boolean showCourseCode, List<String> hiddenLocationPhrases,
                       Map<String, Integer> todoCounts, Set<String> urgentTodoCourses,
                       Set<String> warnCourses, int stackDepth, int clusterSize,
                       @Nullable RectF outTodoHitRect) {
        int todoN = 0;
        String normCourseKey = "";
        if (isActive && course.name != null) {
            normCourseKey = CourseTodo.normalizeCourseKey(course.name);
            if (!todoCounts.isEmpty()) {
                Integer cnt = todoCounts.get(normCourseKey);
                if (cnt != null) todoN = cnt;
            }
        }
        boolean showConflictTag = isActive && clusterSize > 1 && stackDepth > 0;
        boolean hasBottomPill = todoN > 0 || showConflictTag;
        float footerReserve = (hasBottomPill && textRect.height() >= dp(46f)) ? dp(15f) : 0f;

        float innerLeft = textRect.left + (isActive ? dp(6.0f) : dp(4.0f));
        float innerRight = textRect.right - dp(2.4f);
        float innerWidth = Math.max(dp(16), innerRight - innerLeft);
        float innerTop = textRect.top + dp(4.8f);
        float innerBottom = textRect.bottom - dp(3.8f) - footerReserve;
        if (innerBottom < innerTop + dp(12f)) {
            innerBottom = textRect.bottom - dp(3.2f);
        }

        canvas.save();
        canvas.clipRect(textRect.left + dp(2), Math.max(textRect.top + dp(2), headerHeight),
                textRect.right - dp(2), textRect.bottom - dp(2));

        textPaint.setTypeface(Typeface.DEFAULT_BOLD);
        textPaint.setTextSize(sp(10.0f));
        textPaint.setColor(textColor);
        textPaint.setTextAlign(Paint.Align.LEFT);

        String title = course.name != null ? course.name : "";
        if (tag != null) title = tag + title;
        else if (!isActive) title = "[非本周] " + title;
        int w = Math.round(innerWidth);
        float available = Math.max(dp(11f), innerBottom - innerTop);
        int maxTitleLines = Math.max(2, (int) Math.ceil(available / Math.max(1f, textPaint.getTextSize())) + 2);
        StaticLayout titleLayout = textLayout(title, textPaint, w, maxTitleLines, 1.02f);

        TextPaint sub = bpSub;
        sub.set(subTextPaint);
        sub.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        sub.setTextSize(sp(8.2f));
        sub.setColor(textColor);
        sub.setAlpha(isActive ? 215 : 165);
        sub.setTextAlign(Paint.Align.LEFT);
        TextPaint mono = bpMono;
        mono.set(sub);
        mono.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        mono.setAlpha(isActive ? 235 : 165);

        String loc = ClassroomLabel.compact(s.location, hiddenLocationPhrases);
        String tl = s.hasCustomTime() ? s.timeStart + "–" + s.timeEnd : "";
        String teacher = s.teacherFor(currentWeek, course.teacher).trim();
        float gap = dp(3f);
        boolean hasCode = showCourseCode && course.code != null && !course.code.isEmpty()
                && !course.code.equals(course.name);
        float locationSpace = available - titleLayout.getHeight() - gap;
        if (hasCode) locationSpace -= gap + sub.getFontSpacing();
        float minRoomReserve = Math.min(Math.max(0f, available - titleLayout.getLineBottom(0) - gap),
                sub.getFontSpacing() * 2f);
        if (locationSpace < minRoomReserve) locationSpace = minRoomReserve;
        StaticLayout locL = locationLayout(loc, sub, w, locationSpace);
        StaticLayout timeL = tl.isEmpty() ? null : textLayout(tl, mono, w, 2, 1f);
        StaticLayout teacherL = teacher.isEmpty() ? null : textLayout(teacher, sub, w, 2, 1f);

        TextPaint tagCn = bpTagCn;
        tagCn.set(sub);
        tagCn.setTypeface(Typeface.DEFAULT_BOLD);
        tagCn.setColor(G.alarm());
        tagCn.setAlpha(255);
        TextPaint tagNum = bpTagNum;
        tagNum.set(tagCn);
        tagNum.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        String tagPre = null;
        if (liveLeft > 0) {
            String n = String.valueOf(liveLeft);
            boolean soonEnd = liveLeft <= TimetableLayout.ENDING_SOON_MINUTES;
            String longPre = soonEnd ? "即将下课 · 剩" : "● 进行中 · 剩";
            if (tagWidth(longPre, n, tagCn, tagNum) <= innerWidth) tagPre = longPre;
            else if (tagWidth("剩", n, tagCn, tagNum) <= innerWidth) tagPre = "剩";
        }
        float tagH = tagPre == null ? 0f : tagCn.getFontMetrics().descent - tagCn.getFontMetrics().ascent;

        int titleLines = titleLayout.getLineCount();
        float[] titleCum = new float[titleLines];
        for (int i = 0; i < titleLines; i++) titleCum[i] = titleLayout.getLineBottom(i);
        float roomHeight = locL == null ? 0f : locL.getHeight();
        StaticLayout codeL = null;
        if (hasCode) {
            StaticLayout measured = textLayout(course.code, sub, w, 3, 1f);
            float codeSpace = available - gap - (roomHeight > 0 ? gap + roomHeight : 0f);
            if (titleCum[0] + measured.getLineBottom(0) <= codeSpace) {
                int lines = measured.getLineCount();
                while (lines > 1 && titleCum[titleLines - 1] + measured.getLineBottom(lines - 1) > codeSpace) lines--;
                codeL = lines == measured.getLineCount() ? measured : textLayout(course.code, sub, w, lines, 1f);
            }
        }
        float codeHeight = codeL == null ? 0f : gap + codeL.getHeight();
        int[] plan = TimetableLayout.blockPlan(available - codeHeight, titleCum, gap,
                roomHeight,
                timeL == null ? 0f : timeL.getHeight(),
                teacherL == null ? 0f : teacherL.getHeight(),
                tagH);
        if (plan[0] < titleLines) titleLayout = textLayout(title, textPaint, w, plan[0], 1.02f);

        if (rainLv > RainForecast.NONE) {
            float liveTagW = (plan[1] & 8) != 0 && tagPre != null
                    ? tagWidth(tagPre, String.valueOf(liveLeft), tagCn, tagNum) : 0f;
            painter.drawRainStreaks(canvas, textRect, course, s, textColor, rainLv,
                    innerLeft, innerTop, titleLayout.getHeight() + codeHeight, plan[1], gap,
                    timeL, teacherL, tagH, liveTagW, locL);
        }

        canvas.translate(innerLeft, innerTop);
        titleLayout.draw(canvas);
        float y = titleLayout.getHeight();
        if (codeL != null) y = drawAt(canvas, codeL, y + gap);
        if ((plan[1] & 2) != 0) y = drawAt(canvas, timeL, y + gap);
        if ((plan[1] & 4) != 0) y = drawAt(canvas, teacherL, y + gap);
        if ((plan[1] & 8) != 0) {
            float base = y + gap - tagCn.getFontMetrics().ascent;
            float x = 0f;
            canvas.drawText(tagPre, x, base, tagCn);
            x += tagCn.measureText(tagPre);
            String n = String.valueOf(liveLeft);
            canvas.drawText(n, x, base, tagNum);
            x += tagNum.measureText(n);
            canvas.drawText("分", x, base, tagCn);
            y += gap + tagH;
        }
        if ((plan[1] & 1) != 0) drawAt(canvas, locL, y + gap);

        canvas.restore();

        float todoLeftEdge = textRect.right;
        if (todoN > 0 && textRect.bottom > headerHeight + dp(14f)) {
            boolean urgent = urgentTodoCourses != null && urgentTodoCourses.contains(normCourseKey);
            todoLeftEdge = drawTodoPill(canvas, textRect, todoN, urgent, textColor, outTodoHitRect);
        } else if (isActive && course.note != null && !course.note.isEmpty() && textRect.bottom > headerHeight + dp(10f)) {
            drawNoteIndicator(canvas, textRect, textColor);
        }

        if (showConflictTag && textRect.bottom > headerHeight + dp(14f)) {
            drawConflictPill(canvas, textRect, innerLeft, todoLeftEdge, clusterSize, textColor);
        }

        if (isActive && !warnCourses.isEmpty() && warnCourses.contains(AbsenceBook.key(course.name))) {
            badgePaint.setStyle(Paint.Style.FILL);
            badgePaint.setColor(G.alarm());
            canvas.drawRect(rect.left, rect.top + dp(2f), rect.left + dp(2.4f), rect.bottom - dp(2f), badgePaint);
        }
    }

    /**
     * 绘制课程右下角作业与待办胶囊：
     * 使用矢量清单图标 + 等宽数字，与课块同色相墨水/半透明玻璃融合，替代原先突兀的「□1」纯红方块；
     * 仅在存在今日截止或已逾期作业时点亮暖朱砂描边与提示色（遵循 DESIGN.md「朱砂只给现在」）。
     */
    private float drawTodoPill(Canvas canvas, RectF bounds, int count, boolean urgent,
                               int textColor, @Nullable RectF outHitRect) {
        String numStr = count > 99 ? "99+" : String.valueOf(count);
        subTextPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        subTextPaint.setTextSize(sp(8.0f));
        subTextPaint.setTextAlign(Paint.Align.LEFT);
        float numW = subTextPaint.measureText(numStr);

        float pillH = dp(13.2f);
        float padX = dp(4.5f);
        float iconW = dp(6.4f);
        float iconGap = dp(2.6f);
        float pillW = Math.max(pillH, padX + iconW + iconGap + numW + padX);
        float inset = G.GLASS ? dp(3.2f) : dp(2.5f);
        if (pillW > bounds.width() - inset * 2f) {
            pillW = Math.max(dp(12f), bounds.width() - inset * 2f);
        }

        float right = bounds.right - inset;
        float left = right - pillW;
        float bottom = bounds.bottom - inset;
        float top = bottom - pillH;
        RectF pill = new RectF(left, top, right, bottom);
        float pillRadius = G.GLASS ? pillH / 2f : dp(3.0f);

        int fg;
        int fill;
        int stroke;
        if (urgent) {
            fg = G.NIGHT ? G.mix(G.alarm(), 0xFFFFFFFF, 0.22f) : G.alarm();
            fill = G.NIGHT ? G.alpha(G.mix(G.alarm(), 0xFF171716, 0.64f), 0xEE)
                    : G.alpha(G.mix(G.alarm(), 0xFFFFFFFF, 0.84f), 0xF4);
            stroke = G.alpha(G.alarm(), G.NIGHT ? 0x8C : 0x6E);
        } else {
            fg = textColor;
            if (G.GLASS) {
                fill = G.NIGHT ? G.alpha(0xFF121211, 0x76) : G.alpha(0xFFFFFFFF, 0xBE);
                stroke = G.NIGHT ? G.alpha(0xFFFFFFFF, 0x26) : G.alpha(textColor, 0x2C);
            } else {
                fill = G.alpha(textColor, G.NIGHT ? 0x2C : 0x15);
                stroke = G.alpha(textColor, 0x34);
            }
        }

        badgePaint.setStyle(Paint.Style.FILL);
        badgePaint.setColor(fill);
        canvas.drawRoundRect(pill, pillRadius, pillRadius, badgePaint);

        badgePaint.setStyle(Paint.Style.STROKE);
        badgePaint.setStrokeWidth(Math.max(1f, dp(0.75f)));
        badgePaint.setColor(stroke);
        canvas.drawRoundRect(pill, pillRadius, pillRadius, badgePaint);
        badgePaint.setStyle(Paint.Style.FILL);

        // 矢量双行待办清单图标（圆点 + 圆头短横线，任何系统字体下都不变形）
        float cy = pill.centerY();
        float ix = pill.left + padX;
        iconPaint.setColor(fg);
        iconPaint.setStrokeWidth(Math.max(1f, dp(1.1f)));
        iconPaint.setStrokeCap(Paint.Cap.ROUND);
        float rDot = dp(0.78f);
        float rowOff = dp(1.75f);
        iconPaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(ix + rDot, cy - rowOff, rDot, iconPaint);
        canvas.drawCircle(ix + rDot, cy + rowOff, rDot, iconPaint);
        iconPaint.setStyle(Paint.Style.STROKE);
        canvas.drawLine(ix + dp(2.5f), cy - rowOff, ix + iconW, cy - rowOff, iconPaint);
        canvas.drawLine(ix + dp(2.5f), cy + rowOff, ix + iconW - dp(1.3f), cy + rowOff, iconPaint);

        subTextPaint.setColor(fg);
        subTextPaint.setAlpha(255);
        Paint.FontMetrics fm = subTextPaint.getFontMetrics();
        float textBase = cy - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(numStr, ix + iconW + iconGap, textBase, subTextPaint);

        if (outHitRect != null) {
            outHitRect.set(
                    Math.max(bounds.left, pill.left - dp(7f)),
                    Math.max(bounds.top, pill.top - dp(7f)),
                    bounds.right,
                    bounds.bottom);
        }
        return pill.left;
    }

    /** 绘制冲突叠卡左下角柔和微胶囊（叠卡矢量图标 + 数量），不遮挡课名且不用刺眼纯红。 */
    private void drawConflictPill(Canvas canvas, RectF bounds, float innerLeft, float maxRight,
                                  int clusterSize, int textColor) {
        float inset = G.GLASS ? dp(3.2f) : dp(2.5f);
        float availW = maxRight - dp(3f) - innerLeft;
        if (availW < dp(15f)) return;

        subTextPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        subTextPaint.setTextSize(sp(7.6f));
        subTextPaint.setTextAlign(Paint.Align.LEFT);

        String longLbl = "冲突" + clusterSize;
        String shortLbl = "×" + clusterSize;
        float padX = dp(4.0f);
        float iconW = dp(6.0f);
        float gap = dp(2.2f);
        String label = (padX * 2 + iconW + gap + subTextPaint.measureText(longLbl) <= availW)
                ? longLbl : shortLbl;
        float lblW = subTextPaint.measureText(label);
        float pillW = padX + iconW + gap + lblW + padX;
        if (pillW > availW) return;

        float pillH = dp(12.8f);
        float bottom = bounds.bottom - inset;
        RectF pill = new RectF(innerLeft, bottom - pillH, innerLeft + pillW, bottom);
        float pillRadius = G.GLASS ? pillH / 2f : dp(3.0f);

        int fill = G.GLASS
                ? (G.NIGHT ? G.alpha(0xFF121211, 0x72) : G.alpha(0xFFFFFFFF, 0xB8))
                : G.alpha(textColor, G.NIGHT ? 0x2A : 0x14);
        int stroke = G.GLASS
                ? (G.NIGHT ? G.alpha(0xFFFFFFFF, 0x24) : G.alpha(textColor, 0x2A))
                : G.alpha(textColor, 0x32);

        badgePaint.setStyle(Paint.Style.FILL);
        badgePaint.setColor(fill);
        canvas.drawRoundRect(pill, pillRadius, pillRadius, badgePaint);
        badgePaint.setStyle(Paint.Style.STROKE);
        badgePaint.setStrokeWidth(Math.max(1f, dp(0.75f)));
        badgePaint.setColor(stroke);
        canvas.drawRoundRect(pill, pillRadius, pillRadius, badgePaint);
        badgePaint.setStyle(Paint.Style.FILL);

        // 矢量双层叠卡小图标
        float ix = pill.left + padX;
        float cy = pill.centerY();
        iconPaint.setStyle(Paint.Style.STROKE);
        iconPaint.setStrokeWidth(Math.max(1f, dp(0.95f)));
        iconPaint.setColor(textColor);
        RectF back = new RectF(ix, cy - dp(2.7f), ix + dp(4.0f), cy + dp(1.3f));
        RectF front = new RectF(ix + dp(1.8f), cy - dp(1.1f), ix + dp(5.8f), cy + dp(2.8f));
        canvas.drawRoundRect(back, dp(0.9f), dp(0.9f), iconPaint);
        badgePaint.setColor(fill);
        canvas.drawRoundRect(front, dp(0.9f), dp(0.9f), badgePaint);
        canvas.drawRoundRect(front, dp(0.9f), dp(0.9f), iconPaint);

        subTextPaint.setColor(textColor);
        subTextPaint.setAlpha(245);
        Paint.FontMetrics fm = subTextPaint.getFontMetrics();
        float textBase = cy - (fm.ascent + fm.descent) / 2f;
        canvas.drawText(label, ix + iconW + gap, textBase, subTextPaint);
    }

    /** 课程备注角标：右下角温润同色系小圆点 + 外环，不与朱砂抢色。 */
    private void drawNoteIndicator(Canvas canvas, RectF bounds, int textColor) {
        float cx = bounds.right - dp(6.2f);
        float cy = bounds.bottom - dp(6.2f);
        badgePaint.setStyle(Paint.Style.FILL);
        badgePaint.setColor(G.alpha(textColor, 0x26));
        canvas.drawCircle(cx, cy, dp(3.8f), badgePaint);
        badgePaint.setColor(G.alpha(textColor, 0xC8));
        canvas.drawCircle(cx, cy, dp(1.9f), badgePaint);
    }

    /** 绘制左轨当前时刻胶囊与今日现在线。 */
    void drawNowLine(Canvas canvas, float y, float timeColWidth, float colWidth,
                     int todayDayOfWeek, int nowMinutes, boolean skipDayLine) {
        String hm = Timetable.Session.formatHm(Math.max(0, Math.min(24 * 60 - 1, nowMinutes)));
        float pillH = dp(13f);
        float pillW = timeColWidth - dp(6f);
        RectF pill = new RectF(dp(3f), y - pillH / 2f, dp(3f) + pillW, y + pillH / 2f);
        accentPaint.setStyle(Paint.Style.FILL);
        accentPaint.setColor(G.alarm());
        canvas.drawRoundRect(pill, dp(3f), dp(3f), accentPaint);
        subTextPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        subTextPaint.setTextSize(sp(7.8f));
        subTextPaint.setColor(G.ON_INK);
        subTextPaint.setAlpha(255);
        subTextPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText(hm, pill.centerX(), pill.centerY() + dp(2.7f), subTextPaint);

        if (skipDayLine) return;
        float x0 = timeColWidth + (todayDayOfWeek - 1) * colWidth;
        accentPaint.setStyle(Paint.Style.FILL);
        accentPaint.setColor(G.alarm());
        canvas.drawRect(x0, y - dp(0.6f), x0 + colWidth, y + dp(0.6f), accentPaint);
        canvas.drawCircle(x0 + dp(1), y, dp(3), accentPaint);
    }

    /** 绘制长按空白处画课时的虚线选框。 */
    void drawGhost(Canvas canvas, RectF g, int ghostStart, int ghostEnd) {
        float rad = G.GLASS ? dp(10) : dp(3);
        blockPaint.setStyle(Paint.Style.FILL);
        blockPaint.setColor(G.alpha(G.INK, 0x14));
        canvas.drawRoundRect(g, rad, rad, blockPaint);
        blockPaint.setStyle(Paint.Style.STROKE);
        blockPaint.setStrokeWidth(dp(1.2f));
        blockPaint.setColor(G.INK);
        blockPaint.setPathEffect(new DashPathEffect(new float[]{dp(4), dp(3)}, 0));
        canvas.drawRoundRect(g, rad, rad, blockPaint);
        blockPaint.setPathEffect(null);
        textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        textPaint.setTextSize(sp(10f));
        textPaint.setColor(G.INK);
        textPaint.setTextAlign(Paint.Align.CENTER);
        String label = ghostStart == ghostEnd ? ghostStart + "节" : ghostStart + "–" + ghostEnd;
        canvas.drawText(label, g.centerX(), g.top + dp(16), textPaint);
    }

    private static StaticLayout textLayout(String t, TextPaint p, int width, int maxLines, float mult) {
        return StaticLayout.Builder.obtain(t, 0, t.length(), p, Math.max(1, width))
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, mult)
                .setIncludePad(false)
                .setMaxLines(Math.max(1, maxLines))
                .setEllipsize(TextUtils.TruncateAt.END)
                .build();
    }

    private static StaticLayout locationLayout(String location, TextPaint paint, int width, float height) {
        if (location.isEmpty()) return null;
        int maxLines = Math.max(1, (int) Math.ceil(height / paint.getTextSize()) + 1);
        StaticLayout layout = textLayout(location, paint, width, maxLines, 1f);
        int lines = layout.getLineCount();
        while (lines > 1 && layout.getLineBottom(lines - 1) > height) lines--;
        return lines == layout.getLineCount() ? layout : textLayout(location, paint, width, lines, 1f);
    }

    private static float drawAt(Canvas canvas, StaticLayout l, float y) {
        canvas.save();
        canvas.translate(0, y);
        l.draw(canvas);
        canvas.restore();
        return y + l.getHeight();
    }

    private static float tagWidth(String pre, String num, TextPaint cn, TextPaint mono) {
        return cn.measureText(pre) + mono.measureText(num) + cn.measureText("分");
    }
}
