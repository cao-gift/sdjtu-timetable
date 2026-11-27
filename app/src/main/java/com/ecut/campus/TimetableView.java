package com.ecut.campus;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.animation.ValueAnimator;
import android.view.animation.PathInterpolator;
import android.widget.OverScroller;

import androidx.annotation.Nullable;

import com.ecut.campus.data.ClassroomLabel;
import com.ecut.campus.data.Timetable;
import com.ecut.campus.data.TimetableLayout;
import com.ecut.campus.data.SemesterMetaPolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 原生课表网格：8–16 节（随作息设置）、一屏 8 节、固定双节块、七天/五天列、左右滑周。
 *
 * 手势方向锁：横向滑动切周，纵向滑动查看后面的节次，互不抢占。
 */
public class TimetableView extends View {

    public static final class ConflictItem {
        public final Timetable.Course course;
        public final Timetable.Session displaySession;
        public final Timetable.Session primarySession;
        public final String label;
        public final String adjustId;
        public final boolean extra;

        public ConflictItem(Timetable.Course course, Timetable.Session displaySession,
                            Timetable.Session primarySession, String label, String adjustId, boolean extra) {
            this.course = course;
            this.displaySession = displaySession;
            this.primarySession = primarySession;
            this.label = label;
            this.adjustId = adjustId == null ? "" : adjustId;
            this.extra = extra;
        }
    }

    public interface OnSessionClickListener {
        void onSessionClick(Timetable.Course course, Timetable.Session session, boolean activeThisWeek);
        void onEmptySlotClick(int dayOfWeek, int startSection);
        /** 在空白格长按后往下拖，画出一段节次；松手时回调。默认当作点了起始格。 */
        default void onEmptyRangeDrawn(int dayOfWeek, int startSection, int endSection) {
            onEmptySlotClick(dayOfWeek, startSection);
        }
        void onWeekSwipe(int delta);
        /** 松手判定翻页的瞬间回调，用于立即更新周数标题。 */
        default void onWeekSettling(int delta) {}

        /** 一句轻提示（比如长按到被占的格子）。 */
        default void onHint(String message) {}
        /** 点到带标记的块：停课/放假/已调走（原课程）或补课/调课（本周临时加的）。 */
        default void onMarkedClick(Timetable.Course course, Timetable.Session session,
                                   String label, String adjustId, boolean extra) {}
        /** 长按课块。 */
        default void onSessionLongPress(Timetable.Course course, Timetable.Session session, boolean active,
                                        String label, String adjustId, boolean extra, int week) {}
        /** 点击叠加在课表中的考试卡片。 */
        default void onExamClick(com.ecut.campus.data.ExamCountdown.Entry exam) {}
        /** 点击重修/时间冲突课程叠卡簇时回调，展示同簇全部冲突课程。 */
        default void onConflictClick(List<ConflictItem> items, Timetable.Course tappedCourse, int week) {
            if (!items.isEmpty()) onSessionClick(items.get(0).course, items.get(0).primarySession, true);
        }
        /** 点击课块右下角作业与待办胶囊时直达对应课程待办专页。 */
        default void onTodoBadgeClick(Timetable.Course course) {}
    }

    public interface OnVerticalScrollListener {
        void onVerticalScrollEnd();
    }

    /**
     * 24 色色板（背景色）。前 12 色就是 1.2.3 以前的老色板，顺序不变，
     * 老用户的课升级后颜色不会变；后 12 色是补充色，一个学期超过 12 门不同课才会用到。
     */
    public static final int[] PALETTE_BG = {
            0xFFEFE2CC, 0xFFDDE5D6, 0xFFF0D9CF, 0xFFDCE1E6, 0xFFE4DEEC, 0xFFD6E6E3,
            0xFFF1DCDF, 0xFFE4E2DC, 0xFFE9E3C9, 0xFFD9E0D0, 0xFFE8D5CC, 0xFFD8DCE4,
            0xFFE3B5B5, 0xFFE3D0B5, 0xFFD4E3B5, 0xFFB5E3B5, 0xFFC0E8CD, 0xFFC0E8E8,
            0xFFB5D8E3, 0xFFB5B5E3, 0xFFC9C7E1, 0xFFE0B5E3, 0xFFECCBEC, 0xFFDBBDCC
    };

    /** 24 色色板（文字与强调色） */
    public static final int[] PALETTE_FG = {
            0xFF6B4A1E, 0xFF3E5236, 0xFF7A3A24, 0xFF384756, 0xFF4E4364, 0xFF2D5650,
            0xFF7A3A47, 0xFF4F4D46, 0xFF5C5222, 0xFF46553A, 0xFF6E3E2C, 0xFF3D4558,
            0xFF6D2C2C, 0xFF6D522C, 0xFF576D2C, 0xFF2C6D2C, 0xFF2C6D42, 0xFF2C6D6D,
            0xFF2C5D6D, 0xFF2C2C6D, 0xFF322C6D, 0xFF672C6D, 0xFF6D2C6D, 0xFF6D2C4D
    };

    /** 磨砂玻璃用的课块底色：同色相、饱和度更高，叠在半透明柔光底上也分得清。 */
    public static final int[] PALETTE_GLASS = {
            0xFFEBD4AC, 0xFFCAE7B1, 0xFFECC2AF, 0xFFBBCFE3, 0xFFCFB8EE, 0xFFB0E9DE,
            0xFFEFBAC2, 0xFFDFD7BD, 0xFFEADDA5, 0xFFCAE2AA, 0xFFEABCA6, 0xFFB5C5E3,
            0xFFE58F8F, 0xFFE5C28F, 0xFFC9E58F, 0xFF8FE58F, 0xFF9CE8B5, 0xFF9CE8E8,
            0xFF8FD0E5, 0xFF8F8FE5, 0xFFA29CE8, 0xFFDF8FE5, 0xFFEBA9EB, 0xFFE58FBA
    };

    private static final int[] LIGHT_BG = PALETTE_BG.clone();
    private static final int[] LIGHT_FG = PALETTE_FG.clone();

    /**
     * 磨砂玻璃课块的字色：跟纸墨一样取课色本身的深浅，不再一律近黑。
     * 日间 = 纸墨深色再压 18% 黑（叠在半透明玻璃底上最差约 5.4:1）；夜间 = 玻璃色提亮 35%（最差约 6.1:1）。
     */
    static int glassInk(int idx) {
        return G.NIGHT ? G.mix(PALETTE_GLASS[idx], 0xFFFFFFFF, 0.35f) : G.mix(LIGHT_FG[idx], 0xFF000000, 0.18f);
    }

    /** 指定课程色在日/夜模式下的字色与色条色（供桌面小组件共用）。 */
    public static int courseFgColor(int idx, boolean night) {
        int i = Math.abs(idx) % LIGHT_FG.length;
        return night ? G.mix(LIGHT_BG[i], 0xFFFFFFFF, 0.10f) : LIGHT_FG[i];
    }

    /** 深色模式下把暖色课块换成「深底 + 浅字」，色相保持一致。 */
    public static void applyPalette(boolean night) {
        for (int k = 0; k < PALETTE_BG.length; k++) {
            if (night) {
                PALETTE_BG[k] = G.mix(LIGHT_FG[k], 0xFF0E0E0D, 0.70f);
                PALETTE_FG[k] = G.mix(LIGHT_BG[k], 0xFFFFFFFF, 0.10f);
            } else {
                PALETTE_BG[k] = LIGHT_BG[k];
                PALETTE_FG[k] = LIGHT_FG[k];
            }
        }
    }

    /** 朱砂：只用于「现在」——今天、现在线、冲突角标。 */
    private final int ACCENT = G.ACCENT;
    private final int INK = G.INK;
    private final int INK_2 = G.SUB;
    private final int INK_3 = G.MUTE;
    private final int PAPER = G.CARD;
    private final int HAIRLINE = G.HAIR;

    private static final String[] DAY_NAMES = {"一", "二", "三", "四", "五", "六", "日"};
    private static final int DEFAULT_VISIBLE_SECTIONS = 8;
    /** 一屏显示几节：默认 8 节（可上下滚），打开「一屏显示全部节次」后压缩课块高度一屏放下。 */
    private int visibleSections = DEFAULT_VISIBLE_SECTIONS;
    /** 纵向惯性滚动 + 到头回弹。 */
    private OverScroller scroller;

    private Timetable timetable = new Timetable();
    private ScheduleSettings schedule = ScheduleSettings.createDefault();
    private String semesterStartDate = "";
    private int currentWeek = 1;
    private int totalWeeks = 30;
    private int displayDays = 7;
    private int todayDayOfWeek = 1;
    private boolean showInactiveCourses = false;
    private boolean showCourseCode;
    private List<String> hiddenLocationPhrases = Collections.emptyList();
    private OnSessionClickListener listener;
    private OnVerticalScrollListener verticalScrollListener;

    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint blockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint badgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint subTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TimetablePainter painter;
    private final TimetableBlockRenderer blockRenderer;

    private final List<HitBlock> hitBlocks = new ArrayList<>();
    /** 每周的停课/调课/放假叠加层，按周缓存，课表或开学日期变了就清空。 */
    private final java.util.Map<Integer, com.ecut.campus.data.WeekOverlay> overlays = new java.util.HashMap<>();
    private com.ecut.campus.data.WeekOverlay overlay = com.ecut.campus.data.WeekOverlay.empty(0);
    private final Runnable longPressRunnable = this::fireLongPress;
    private boolean longPressFired;
    private final int touchSlop;

    private float headerHeight;
    private float timeColWidth;
    private float rowHeight;
    private float scrollOffset;
    private float maxScrollOffset;
    private float bottomInset;

    private float downX;
    private float downY;
    private float lastY;
    private float dragDx;
    private int gestureMode = 0; // 0=未判定 1=横向切周 2=纵向滚动
    private boolean moved;
    private int activePointerId = MotionEvent.INVALID_POINTER_ID;
    private VelocityTracker velocityTracker;
    private ValueAnimator pageAnimator;
    private boolean pageAnimating;
    /** 正在吸附、尚未提交的翻页方向；0 表示无。快速连续滑动时由新手势立即提交。 */
    private int pendingCommit;
    @Nullable
    private com.ecut.campus.data.RainForecast rainPreviewOverride;

    private static final class HitBlock {
        final RectF rect;
        final Timetable.Course course;
        final Timetable.Session session;
        Timetable.Session hitSession;
        final boolean active;
        String label;      // 非空 = 带标记（停课/放假/已调走/补课/调课）
        String adjustId = "";
        boolean extra;
        int clusterKey;
        int clusterSize = 1;
        @Nullable RectF todoHitRect;
        com.ecut.campus.data.ExamCountdown.Entry exam;

        HitBlock(RectF rect, Timetable.Course course, Timetable.Session session, boolean active) {
            this.rect = rect;
            this.course = course;
            this.session = session;
            this.hitSession = session;
            this.active = active;
        }
    }

    public TimetableView(Context context) {
        this(context, null);
    }

    public TimetableView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        headerHeight = dp(52);
        timeColWidth = dp(48);
        rowHeight = dp(54);
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        scroller = new OverScroller(context, new android.view.animation.DecelerateInterpolator(1.6f));

        gridPaint.setColor(HAIRLINE);
        gridPaint.setStrokeWidth(Math.max(1f, dp(0.6f)));
        gridPaint.setStyle(Paint.Style.STROKE);

        android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
        painter = new TimetablePainter(dm.density, dm.scaledDensity);
        blockRenderer = new TimetableBlockRenderer(painter, dm.density, dm.scaledDensity);

        todayDayOfWeek = WeekMath.todayDayOfWeek();
        setWillNotDraw(false);
    }

    public void setSchedule(ScheduleSettings settings) {
        this.schedule = settings == null ? ScheduleSettings.createDefault() : settings;
        applyVisibleSections();
        invalidate();
    }

    public void setSemesterStartDate(String startDate) {
        this.semesterStartDate = startDate == null ? "" : startDate;
        overlays.clear();
        invalidate();
    }

    public void setDisplayDays(int days) {
        this.displayDays = days == 5 ? 5 : 7;
        invalidate();
    }

    public int getDisplayDays() {
        return displayDays;
    }

    public void setTimetable(Timetable t, int week) {
        this.timetable = (t != null) ? t : new Timetable();
        overlays.clear();
        this.currentWeek = clampWeek(week);
        this.todayDayOfWeek = WeekMath.todayDayOfWeek();
        cancelPageAnimation();
        dragDx = 0;
        pageAnimating = false;
        requestLayout();
        invalidate();
    }

    private java.util.Set<String> warnCourses = java.util.Collections.emptySet();
    private java.util.Map<String, Integer> todoCounts = java.util.Collections.emptyMap();
    private java.util.Set<String> urgentTodoCourses = java.util.Collections.emptySet();
    private List<com.ecut.campus.data.ExamCountdown.Entry> examEntries = Collections.emptyList();

    /** 缺勤快到上限的课（规范化课名），课块左缘描朱砂。 */
    public void setWarnCourses(java.util.Set<String> keys) {
        warnCourses = keys == null ? java.util.Collections.emptySet() : keys;
        invalidate();
    }

    /** 各课未完成作业/待办数量（规范化课名 → 数量），在课块右下角绘制胶囊角标。 */
    public void setTodoCounts(java.util.Map<String, Integer> counts) {
        todoCounts = counts == null ? java.util.Collections.emptyMap() : counts;
        invalidate();
    }

    /** 存在今日截止或已逾期作业的课程（规范化课名），用于点亮待办角标的紧急提示色。 */
    public void setUrgentTodoCourses(java.util.Set<String> keys) {
        urgentTodoCourses = keys == null ? java.util.Collections.emptySet() : keys;
        invalidate();
    }

    /** 已同步的考试安排，落在当前查看周次时叠加绘制为朱砂边考试卡片。 */
    public void setExamEntries(List<com.ecut.campus.data.ExamCountdown.Entry> entries) {
        examEntries = entries == null ? Collections.emptyList() : entries;
        invalidate();
    }

    public void setCurrentWeek(int week) {
        this.currentWeek = clampWeek(week);
        cancelPageAnimation();
        dragDx = 0;
        invalidate();
    }

    public int getCurrentWeek() {
        return currentWeek;
    }

    public void setTotalWeeks(int weeks) {
        this.totalWeeks = Math.max(1, Math.min(WeekMath.OPEN_WEEKS_MAX, weeks));
        this.currentWeek = clampWeek(currentWeek);
        invalidate();
    }

    public int getTotalWeeks() {
        return totalWeeks;
    }

    private int clampWeek(int week) {
        return Math.max(1, Math.min(totalWeeks, week));
    }

    public void setShowInactiveCourses(boolean show) {
        this.showInactiveCourses = show;
        invalidate();
    }

    public boolean isShowInactiveCourses() {
        return showInactiveCourses;
    }

    public void setShowCourseCode(boolean show) {
        if (showCourseCode != show) {
            showCourseCode = show;
            invalidate();
        }
    }

    public void setHiddenLocationPhrases(String input) {
        List<String> phrases = ClassroomLabel.parsePhrases(input);
        if (!hiddenLocationPhrases.equals(phrases)) {
            hiddenLocationPhrases = phrases;
            invalidate();
        }
    }

    public void setOnSessionClickListener(OnSessionClickListener l) {
        this.listener = l;
    }

    public void setOnVerticalScrollListener(OnVerticalScrollListener l) {
        this.verticalScrollListener = l;
    }

    /** Dock 覆盖区域高度：课表背景仍延伸其下，但可视 8 节与滚动范围避开遮挡。 */
    /** true = 一屏放下全部节次（课块变矮），false = 默认 8 节可滚动。 */
    public void setFitAllSections(boolean fit) {
        fitAll = fit;
        applyVisibleSections();
        invalidate();
    }

    /** 每天几节由作息设置决定（8–16 节）；不同学校不再钉死 10 节。 */
    private int totalSections() {
        return schedule == null ? ScheduleSettings.DEFAULT_SECTION_COUNT : schedule.sectionCount();
    }

    private boolean fitAll;

    private void applyVisibleSections() {
        int v = fitAll ? totalSections() : Math.min(DEFAULT_VISIBLE_SECTIONS, totalSections());
        if (v != visibleSections && scroller != null) scroller.forceFinished(true);
        visibleSections = v;
        if (getHeight() > 0) updateRowHeight(getHeight());
        if (scrollOffset > maxScrollOffset) scrollOffset = maxScrollOffset;
    }

    /** 拖过头时的阻力：越往外越拉不动，最多拉出约 1/4 屏。 */
    private float rubberBand(float delta) {
        return TimetableLayout.rubberBand(scrollOffset, maxScrollOffset, delta,
                Math.max(dp(80), getHeight() * 0.25f));
    }

    private void springBackIfNeeded() {
        if (scrollOffset < 0 || scrollOffset > maxScrollOffset) {
            scroller.springBack(0, Math.round(scrollOffset), 0, 0, 0, Math.round(maxScrollOffset));
            postInvalidateOnAnimation();
        } else if (verticalScrollListener != null) {
            verticalScrollListener.onVerticalScrollEnd();
        }
    }

    @Override
    public void computeScroll() {
        if (scroller != null && scroller.computeScrollOffset()) {
            scrollOffset = scroller.getCurrY();
            postInvalidateOnAnimation();
            if (scroller.isFinished() && verticalScrollListener != null) verticalScrollListener.onVerticalScrollEnd();
        }
    }

    public void setBottomInset(float px) {
        this.bottomInset = Math.max(0, px);
        if (getHeight() > 0) updateRowHeight(getHeight());
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        setMeasuredDimension(width, height);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateRowHeight(h);
    }

    private void updateRowHeight(int h) {
        float available = Math.max(dp(240), h - headerHeight - bottomInset);
        rowHeight = available / visibleSections;
        maxScrollOffset = Math.max(0, rowHeight * (totalSections() - visibleSections));
        scrollOffset = Math.max(0, Math.min(maxScrollOffset, scrollOffset));
    }

    private float sectionTopY(int section) {
        return headerHeight + (section - 1) * rowHeight - scrollOffset;
    }

    private float sectionBottomY(int section) {
        return headerHeight + section * rowHeight - scrollOffset;
    }

    private int columnCount() {
        return displayDays;
    }

    private float columnWidth(int w) {
        return (w - timeColWidth) / (float) columnCount();
    }

    private float pageWidth() {
        return getWidth();
    }

    private int adjacentWeek(int direction) {
        return clampWeek(currentWeek + direction);
    }

    private boolean canSwipe(int direction) {
        int adjacent = adjacentWeek(direction);
        return direction != 0 && adjacent != currentWeek;
    }

    /**
     * 边缘周也保留约 18% 的跟手余量，松手回弹。
     * 这样手指仍有物理反馈，但不会拖出空白页。
     */
    private float resistDrag(float dx) {
        float width = pageWidth();
        if (width <= 0) return 0;
        if ((dx < 0 && !canSwipe(1)) || (dx > 0 && !canSwipe(-1))) {
            return dx * 0.18f;
        }
        float limit = width * 1.08f;
        return Math.max(-limit, Math.min(limit, dx));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        hitBlocks.clear();

        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (rowHeight <= 0) updateRowHeight(h);

        if (!G.GLASS) {
            bgPaint.setStyle(Paint.Style.FILL);
            bgPaint.setColor(G.CARD);
            canvas.drawRect(0, 0, w, h, bgPaint);
        }
        todayDayOfWeek = WeekMath.todayDayOfWeek();
        nowMinutesFloat = nowMinutesFloatOfDay();
        nowMinutes = (int) nowMinutesFloat;

        int direction = dragDx < 0 ? 1 : (dragDx > 0 ? -1 : 0);
        int neighbor = direction == 0 ? currentWeek : adjacentWeek(direction);
        boolean drawNeighbor = direction != 0 && neighbor != currentWeek;
        float offset = dragDx;

        // 先画当前页，再画从侧边进入的相邻周。时间轴跟随整页移动。
        drawPage(canvas, currentWeek, offset, true);
        if (drawNeighbor) {
            float neighborOffset = offset + direction * pageWidth();
            drawPage(canvas, neighbor, neighborOffset, false);
            drawPageEdge(canvas, direction, neighborOffset, h);
        }
    }

    private void drawPageEdge(Canvas canvas, int direction, float neighborOffset, int h) {
        painter.drawPageEdge(canvas, direction, neighborOffset, pageWidth(), headerHeight, h);
    }

    private void drawPage(Canvas canvas, int week, float xOffset, boolean isPrimary) {
        int w = getWidth();
        int h = getHeight();
        float colWidth = columnWidth(w);
        int savedWeek = currentWeek;
        List<HitBlock> savedHits = isPrimary ? null : new ArrayList<>(hitBlocks);
        if (!isPrimary) hitBlocks.clear();
        currentWeek = week;
        overlay = overlayFor(week);

        canvas.save();
        canvas.translate(xOffset, 0);
        canvas.clipRect(-xOffset, 0, w - xOffset, h);

        canvas.save();
        canvas.clipRect(0, headerHeight, w, h);
        drawTimeColumnAndGrid(canvas, w, colWidth);
        if (showInactiveCourses) drawCourseBlocks(canvas, colWidth, false);
        drawCourseBlocks(canvas, colWidth, true);
        canvas.restore();
        drawWeekHeader(canvas, colWidth, w);
        if (isPrimary) drawScrollHint(canvas, w, h);

        canvas.restore();
        currentWeek = savedWeek;
        if (!isPrimary) {
            hitBlocks.clear();
            if (savedHits != null) hitBlocks.addAll(savedHits);
        }
    }

    private boolean isViewingRealCurrentWeek() {
        if (semesterStartDate == null || semesterStartDate.isEmpty()) return false;
        return currentWeek == WeekMath.currentWeek(semesterStartDate, totalWeeks);
    }

    private void drawWeekHeader(Canvas canvas, float colWidth, int w) {
        if (!G.GLASS) {
            bgPaint.setStyle(Paint.Style.FILL);
            bgPaint.setColor(G.CARD);
            canvas.drawRect(0, 0, w, headerHeight, bgPaint);
        }

        String[] dateLabels = WeekMath.weekDates(semesterStartDate, currentWeek);

        subTextPaint.setColor(G.MUTE);
        subTextPaint.setTextSize(sp(9.5f));
        subTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
        subTextPaint.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("节次", timeColWidth / 2f, headerHeight / 2f + dp(4), subTextPaint);

        boolean highlightToday = isViewingRealCurrentWeek();
        for (int d = 1; d <= columnCount(); d++) {
            float cx = timeColWidth + (d - 0.5f) * colWidth;
            boolean isToday = highlightToday && d == todayDayOfWeek;

            if (isToday && G.GLASS) {
                // 今天：浅色半透明胶囊 + 底部一小段砖红，不再用黑胶囊
                badgePaint.setColor(G.alpha(INK, 0x14));
                RectF pill = new RectF(cx - colWidth / 2f + dp(3), dp(8), cx + colWidth / 2f - dp(3), headerHeight - dp(5));
                canvas.drawRoundRect(pill, dp(12), dp(12), badgePaint);
                badgePaint.setColor(G.alarm());
                canvas.drawRoundRect(new RectF(cx - dp(7), pill.bottom - dp(4.5f), cx + dp(7), pill.bottom - dp(2.5f)),
                        dp(1), dp(1), badgePaint);
            } else if (isToday) {
                badgePaint.setColor(ACCENT);
                canvas.drawRect(cx - dp(9), headerHeight - dp(2.5f), cx + dp(9), headerHeight, badgePaint);
            }
            int todayInk = G.GLASS ? INK : ACCENT;

            textPaint.setTypeface(Typeface.DEFAULT_BOLD);
            textPaint.setTextSize(sp(12.5f));
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setColor(isToday ? todayInk : INK);
            canvas.drawText("周" + DAY_NAMES[d - 1], cx, dp(23), textPaint);

            subTextPaint.setTypeface(Typeface.MONOSPACE);
            subTextPaint.setTextSize(sp(9.5f));
            subTextPaint.setTextAlign(Paint.Align.CENTER);
            subTextPaint.setColor(isToday ? todayInk : INK_3);
            String dayLabel = overlay.dayLabels[d];
            if (dayLabel != null) {
                // 放假 / 补课日：日期换成标签
                subTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
                subTextPaint.setColor(overlay.dayOff[d] ? ACCENT : INK);
                canvas.drawText(dayLabel.length() > 4 ? dayLabel.substring(0, 4) : dayLabel, cx, dp(39), subTextPaint);
            } else {
                canvas.drawText(dateLabels[d - 1], cx, dp(39), subTextPaint);
            }
        }

        if (!G.GLASS) canvas.drawLine(0, headerHeight, w, headerHeight, gridPaint);
    }

    private void drawTimeColumnAndGrid(Canvas canvas, int w, float colWidth) {
        for (int sec = 1; sec <= totalSections(); sec++) {
            float top = sectionTopY(sec);
            float bottom = sectionBottomY(sec);
            float cy = (top + bottom) / 2f;

            if (bottom > headerHeight && top < getHeight()) {
                textPaint.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
                textPaint.setTextSize(sp(11.5f));
                textPaint.setColor(G.INK);
                textPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(String.valueOf(sec), timeColWidth / 2f, cy - dp(9), textPaint);

                subTextPaint.setTypeface(Typeface.MONOSPACE);
                subTextPaint.setTextSize(sp(7.5f));
                subTextPaint.setColor(G.MUTE);
                subTextPaint.setTextAlign(Paint.Align.CENTER);
                canvas.drawText(schedule.startTime(sec), timeColWidth / 2f, cy + dp(2), subTextPaint);
                canvas.drawText(schedule.endTime(sec), timeColWidth / 2f, cy + dp(11), subTextPaint);
            }

            if (!G.GLASS) canvas.drawLine(timeColWidth, bottom, w, bottom, gridPaint);
        }

        if (G.GLASS) return;
        for (int d = 0; d <= columnCount(); d++) {
            float x = timeColWidth + d * colWidth;
            canvas.drawLine(x, headerHeight, x, getHeight(), gridPaint);
        }
    }

    private com.ecut.campus.data.WeekOverlay overlayFor(int week) {
        com.ecut.campus.data.WeekOverlay o = overlays.get(week);
        if (o == null) {
            o = com.ecut.campus.data.WeekOverlay.build(timetable, semesterStartDate, week);
            overlays.put(week, o);
        }
        return o;
    }

    private void drawCourseBlocks(Canvas canvas, float colWidth, boolean activePass) {
        if (timetable == null || timetable.courses == null) return;

        // 本周真正要上的课（去掉停课/放假，加上补课/调来的）先合并同课连堂时段，再按错层叠卡规划冲突。
        java.util.Map<Timetable.Session, TimetableLayout.OverlapCard> deckOf = new java.util.IdentityHashMap<>();
        java.util.Map<Timetable.Session, TimetableLayout.DisplayBlock> blockBySession = new java.util.IdentityHashMap<>();
        java.util.Map<Timetable.Session, com.ecut.campus.data.WeekOverlay.Extra> extraBySession = new java.util.IdentityHashMap<>();
        List<List<TimetableLayout.DisplayBlock>> solidRaw = new ArrayList<>();
        List<List<Timetable.Session>> perDay = new ArrayList<>();
        List<List<Timetable.Session>> drawOrderPerDay = new ArrayList<>();
        for (int d = 0; d <= 7; d++) {
            solidRaw.add(new ArrayList<>());
            perDay.add(new ArrayList<>());
            drawOrderPerDay.add(Collections.emptyList());
        }
        for (Timetable.Course c : timetable.courses) {
            for (Timetable.Session s : c.sessions) {
                if (s.dayOfWeek < 1 || s.dayOfWeek > columnCount()) continue;
                if (s.isActive(currentWeek) && !overlay.cancelled.containsKey(s)) {
                    solidRaw.get(s.dayOfWeek).add(new TimetableLayout.DisplayBlock(c, s, s));
                }
            }
        }
        for (int d = 1; d <= columnCount(); d++) {
            List<TimetableLayout.DisplayBlock> merged =
                    TimetableLayout.mergeConsecutiveActive(solidRaw.get(d), currentWeek, schedule);
            for (TimetableLayout.DisplayBlock b : merged) {
                perDay.get(d).add(b.displaySession);
                blockBySession.put(b.displaySession, b);
            }
        }
        for (com.ecut.campus.data.WeekOverlay.Extra e : overlay.extras) {
            int d = e.session.dayOfWeek;
            if (d >= 1 && d <= columnCount()) {
                perDay.get(d).add(e.session);
                extraBySession.put(e.session, e);
            }
        }
        for (int d = 1; d <= columnCount(); d++) {
            drawOrderPerDay.set(d, TimetableLayout.planOverlapDeck(perDay.get(d), schedule, deckOf));
        }

        if (activePass) {
            for (int d = 1; d <= columnCount(); d++) {
                for (Timetable.Session s : drawOrderPerDay.get(d)) {
                    TimetableLayout.OverlapCard card = deckOf.get(s);
                    TimetableLayout.DisplayBlock b = blockBySession.get(s);
                    if (b != null) {
                        HitBlock hb = drawBlock(canvas, colWidth, b.course, b.displaySession, true,
                                null, card, d);
                        if (hb != null) hb.hitSession = b.primarySession;
                        continue;
                    }
                    com.ecut.campus.data.WeekOverlay.Extra e = extraBySession.get(s);
                    if (e != null) {
                        HitBlock hb = drawBlock(canvas, colWidth, e.course, e.session, true,
                                "[" + e.label + "] ", card, d);
                        if (hb != null) {
                            hb.label = e.label;
                            hb.adjustId = e.adjustId;
                            hb.extra = true;
                        }
                    }
                }
            }
        }

        for (Timetable.Course course : timetable.courses) {
            for (Timetable.Session s : course.sessions) {
                if (s.dayOfWeek < 1 || s.dayOfWeek > columnCount()) continue;
                boolean isActive = s.isActive(currentWeek);
                com.ecut.campus.data.WeekOverlay.Mark mark = isActive ? overlay.cancelled.get(s) : null;
                boolean solid = isActive && mark == null;
                if (solid) continue; // 已在上面的连堂合并分支绘制
                // 被停/放假/调走的课总是画出来（虚线 + 标签），不受「显示非本周」开关影响
                boolean pass = mark != null;
                if (pass != activePass) continue;
                if (overlapsActive(s, perDay.get(s.dayOfWeek))) continue;
                String tag = mark != null ? "[" + mark.label + "] " : null;
                HitBlock hb = drawBlock(canvas, colWidth, course, s, false, tag, null, s.dayOfWeek);
                if (hb != null && mark != null) {
                    hb.label = mark.label;
                    hb.adjustId = mark.adjustId;
                }
            }
        }
        if (activePass) {
            drawExamBlocks(canvas, colWidth);
            drawNowLine(canvas, colWidth);
            drawGhost(canvas, colWidth);
        }
    }

    /** 把落在当前周的考试安排叠加绘制到对应列和时间槽（朱砂描边 + [考试] 标签）。 */
    private void drawExamBlocks(Canvas canvas, float colWidth) {
        if (examEntries.isEmpty() || semesterStartDate == null || semesterStartDate.isEmpty()) return;
        List<com.ecut.campus.data.ExamCountdown.Entry> weekExams =
                com.ecut.campus.data.ExamCountdown.examsInWeek(examEntries, semesterStartDate, currentWeek);
        if (weekExams.isEmpty()) return;
        float padH = dp(1.6f), padV = dp(1.6f);
        for (com.ecut.campus.data.ExamCountdown.Entry ex : weekExams) {
            int day = com.ecut.campus.data.Dates.dayOfWeek(ex.date);
            if (day < 1 || day > columnCount()) continue;
            Timetable.Session s = new Timetable.Session();
            s.dayOfWeek = day;
            int stMin = ex.startMin >= 0 ? ex.startMin : 9 * 60;
            int enMin = ex.endMin > stMin ? ex.endMin : Math.min(23 * 60 + 55, stMin + 120);
            s.timeStart = Timetable.Session.formatHm(stMin);
            s.timeEnd = Timetable.Session.formatHm(enMin);
            s.location = ex.room + (ex.seat.isEmpty() ? "" : " · 座" + ex.seat);
            float[] pos = position(s);
            float left = timeColWidth + (day - 1) * colWidth + padH;
            float right = left + colWidth - padH * 2;
            float top = posY(pos[0]) + padV;
            float bottom = posY(pos[1]) - padV;
            RectF rect = new RectF(left, top, right, bottom);
            if (rect.bottom <= headerHeight || rect.top >= getHeight()) continue;

            blockRenderer.drawExamChrome(canvas, rect);

            Timetable.Course fake = new Timetable.Course();
            fake.name = ex.name;
            fake.key = "exam_" + ex.name;
            blockRenderer.drawBlockText(canvas, rect, rect, headerHeight, fake, s, currentWeek, true, G.INK,
                    "[考试] ", -1, com.ecut.campus.data.RainForecast.NONE, showCourseCode,
                    hiddenLocationPhrases, todoCounts, urgentTodoCourses, warnCourses, 0, 1, null);
            HitBlock hb = new HitBlock(rect, fake, s, true);
            hb.exam = ex;
            hitBlocks.add(hb);
        }
    }

    private boolean overlapsActive(Timetable.Session s, List<Timetable.Session> active) {
        return TimetableLayout.overlapsActive(s, active, schedule);
    }

    /** 画一个课块并登记点击区域；不在可见范围返回 null。 */
    private HitBlock drawBlock(Canvas canvas, float colWidth, Timetable.Course course, Timetable.Session s,
                              boolean isActive, String tag,
                              @Nullable TimetableLayout.OverlapCard card, int dayKey) {
        int colorIdx = Math.abs(course.colorIndex) % PALETTE_BG.length;
        float padH = dp(1.6f);
        float padV = dp(1.6f);
        float[] rawPos = position(s);

        int depth = (isActive && card != null) ? card.depth : 0;
        int clusterSize = (isActive && card != null) ? card.clusterSize : 1;
        float vStart = (isActive && card != null) ? card.visualStart : rawPos[0];
        float vEnd = (isActive && card != null) ? card.visualEnd : rawPos[1];
        float tStart = (isActive && card != null) ? card.textStart : rawPos[0];
        float tEnd = (isActive && card != null) ? card.textEnd : rawPos[1];

        float cellLeft = timeColWidth + (s.dayOfWeek - 1) * colWidth;
        float leftStep = depth > 0 ? Math.min(dp(4.2f) * depth, colWidth * 0.22f) : 0f;
        float left = cellLeft + padH + leftStep;
        float right = cellLeft + colWidth - padH;
        float top = posY(vStart) + padV;
        float bottom = posY(vEnd) - padV;
        RectF rect = new RectF(left, top, right, bottom);
        if (rect.bottom <= headerHeight || rect.top >= getHeight()) return null;

        float textTop = Math.max(rect.top, posY(tStart) + padV);
        float textBottom = Math.min(rect.bottom, posY(tEnd) - padV);
        RectF textRect = (Math.abs(textTop - rect.top) < 0.5f && Math.abs(textBottom - rect.bottom) < 0.5f)
                ? rect : new RectF(left, textTop, right, Math.max(textTop + dp(12f), textBottom));

        float progress = isActive && tag == null ? liveProgress(s) : -1f;
        boolean endingSoon = progress >= 0f
                && TimetableLayout.isEndingSoon(nowMinutesFloat, sessionStartMin(s), sessionEndMin(s));
        int liveLeft = progress >= 0f ? Math.max(1, sessionEndMin(s) - nowMinutes) : -1;
        int rainLv = isActive && tag == null ? resolveRainLevel(s) : com.ecut.campus.data.RainForecast.NONE;
        int until = isActive && tag == null ? minutesUntil(s) : -1;

        int textColor = blockRenderer.drawBlockChrome(canvas, rect, colorIdx, isActive, tag,
                depth, progress, endingSoon, rainLv, until);
        RectF todoHit = new RectF();
        blockRenderer.drawBlockText(canvas, rect, textRect, headerHeight, course, s, currentWeek, isActive, textColor,
                tag, liveLeft, rainLv, showCourseCode, hiddenLocationPhrases,
                todoCounts, urgentTodoCourses, warnCourses, depth, clusterSize, todoHit);
        HitBlock hb = new HitBlock(rect, course, s, isActive);
        hb.clusterSize = clusterSize;
        hb.clusterKey = (card != null && card.clusterId > 0) ? (dayKey * 1000 + card.clusterId) : 0;
        if (todoHit.width() > 0f && todoHit.height() > 0f) {
            hb.todoHitRect = todoHit;
        }
        hitBlocks.add(hb);
        return hb;
    }

    /** 供预览或测试注入指定天气数据；正常运行时传 null 走 RainWeather。 */
    public void setRainPreviewOverride(@Nullable com.ecut.campus.data.RainForecast forecast) {
        this.rainPreviewOverride = forecast;
        invalidate();
    }

    private int resolveRainLevel(Timetable.Session s) {
        if (semesterStartDate == null || semesterStartDate.isEmpty()) {
            return com.ecut.campus.data.RainForecast.NONE;
        }
        com.ecut.campus.data.RainForecast rf = rainPreviewOverride != null
                ? rainPreviewOverride : RainWeather.current(getContext());
        if (rf == null) return com.ecut.campus.data.RainForecast.NONE;
        String ymd = com.ecut.campus.data.Dates.dateOf(semesterStartDate, currentWeek, s.dayOfWeek);
        if (ymd.isEmpty()) return com.ecut.campus.data.RainForecast.NONE;
        return rf.query(ymd, sessionStartMin(s), sessionEndMin(s), System.currentTimeMillis()).level;
    }

    /** 第 p 节边界（0 = 第 1 节顶部）对应的 y。 */
    private float posY(float p) {
        return headerHeight + p * rowHeight - scrollOffset;
    }

    private float[] position(Timetable.Session s) {
        return TimetableLayout.position(s, schedule);
    }

    private void assignLanes(List<Timetable.Session> list, java.util.Map<Timetable.Session, int[]> laneOf) {
        TimetableLayout.assignLanes(list, schedule, laneOf);
    }

    /** 现在线：今天这一列里按作息时间平滑移动的朱砂发丝线 + 圆点，左轨同步标出此刻时间。 */
    private void drawNowLine(Canvas canvas, float colWidth) {
        if (!isViewingRealCurrentWeek()) return;
        if (todayDayOfWeek < 1 || todayDayOfWeek > columnCount()) return;
        float pos = TimetableLayout.smoothNowPos(nowMinutesFloat, schedule);
        if (pos < 0f) return;
        float y = posY(pos);
        if (y <= headerHeight || y >= getHeight()) return;

        boolean skipDayLine = false;
        for (HitBlock hb : hitBlocks) {
            if (hb.active && hb.label == null && hb.session.dayOfWeek == todayDayOfWeek
                    && y >= hb.rect.top && y <= hb.rect.bottom && liveProgress(hb.session) >= 0f) {
                skipDayLine = true;
                break;
            }
        }
        blockRenderer.drawNowLine(canvas, y, timeColWidth, colWidth, todayDayOfWeek, nowMinutes, skipDayLine);
    }

    // ==================== 实时：进度 / 倒计时 ====================

    private int nowMinutes = -1;
    private float nowMinutesFloat = -1f;

    private static float nowMinutesFloatOfDay() {
        java.util.Calendar now = java.util.Calendar.getInstance();
        return now.get(java.util.Calendar.HOUR_OF_DAY) * 60
                + now.get(java.util.Calendar.MINUTE)
                + now.get(java.util.Calendar.SECOND) / 60f;
    }

    private int sessionStartMin(Timetable.Session s) {
        if (s.hasCustomTime()) return Timetable.Session.minutesOf(s.timeStart);
        return schedule.startMinutes(Math.max(1, Math.min(totalSections(), s.startSection)));
    }

    private int sessionEndMin(Timetable.Session s) {
        if (s.hasCustomTime()) return Timetable.Session.minutesOf(s.timeEnd);
        return schedule.endMinutes(Math.max(1, Math.min(totalSections(), s.endSection)));
    }

    private boolean isTodayCell(Timetable.Session s) {
        return isViewingRealCurrentWeek() && s.dayOfWeek == todayDayOfWeek;
    }

    /** 正在上这节课时返回已上比例 0..1，否则 -1。 */
    private float liveProgress(Timetable.Session s) {
        if (nowMinutesFloat < 0f || !isTodayCell(s)) return -1f;
        int a = sessionStartMin(s), b = sessionEndMin(s);
        if (a < 0 || b <= a || nowMinutesFloat < a || nowMinutesFloat >= b) return -1f;
        return TimetableLayout.liveProgressOf(nowMinutesFloat, a, b);
    }

    static float liveProgressOf(int now, int start, int end) {
        return TimetableLayout.liveProgressOf(now, start, end);
    }

    /** 今天还没开始的课，距开始还有几分钟；不是今天或已开始返回 -1。 */
    private int minutesUntil(Timetable.Session s) {
        if (nowMinutes < 0 || !isTodayCell(s)) return -1;
        int a = sessionStartMin(s);
        return a > nowMinutes ? a - nowMinutes : -1;
    }

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            invalidate();
            postDelayed(this, 15_000L);
        }
    };

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        removeCallbacks(ticker);
        postDelayed(ticker, 15_000L);
    }

    // ==================== 空白处画课 ====================

    /** 正在画的那一段：day > 0 表示进入画课模式。 */
    private int ghostDay, ghostStart, ghostEnd;
    /** 长按的那一格：往下拖从它往下长，往上拖从它往上长。 */
    private int ghostAnchor;
    private boolean ghostClipped;

    private void drawGhost(Canvas canvas, float colWidth) {
        if (ghostDay <= 0) return;
        float l = timeColWidth + (ghostDay - 1) * colWidth + dp(1.6f);
        float r = l + colWidth - dp(3.2f);
        RectF g = new RectF(l, sectionTopY(ghostStart) + dp(1.6f), r, sectionBottomY(ghostEnd) - dp(1.6f));
        blockRenderer.drawGhost(canvas, g, ghostStart, ghostEnd);
    }

    /** 这一格（day, sec）有没有本周的课挡着。 */
    private boolean occupied(int day, int sec) {
        float colWidth = columnWidth(getWidth());
        float x = timeColWidth + (day - 0.5f) * colWidth;
        float y = (sectionTopY(sec) + sectionBottomY(sec)) / 2f;
        for (HitBlock b : hitBlocks) if (b.active && b.rect.contains(x, y)) return true;
        return false;
    }

    private int sectionAt(float y) {
        for (int sec = 1; sec <= totalSections(); sec++) {
            if (y >= sectionTopY(sec) && y <= sectionBottomY(sec)) return sec;
        }
        return y < sectionTopY(1) ? 1 : totalSections();
    }

    private void updateGhost(float y) {
        int target = sectionAt(y);
        int a = ghostAnchor, b = ghostAnchor;
        if (target >= ghostAnchor) {
            while (b < target && !occupied(ghostDay, b + 1)) b++;
        } else {
            while (a > target && !occupied(ghostDay, a - 1)) a--;
        }
        // 拖到别的课上：停在它前一节，并轻震一下告诉你「到头了」
        boolean clipped = target > b || target < a;
        if (clipped && !ghostClipped) performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        ghostClipped = clipped;
        if (a != ghostStart || b != ghostEnd) {
            ghostStart = a;
            ghostEnd = b;
            performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK);
            invalidate();
        }
    }

    static int[] blockPlan(float avail, float[] titleCum, float gap, float... items) {
        return TimetableLayout.blockPlan(avail, titleCum, gap, items);
    }

    private void drawScrollHint(Canvas canvas, int w, int h) {
        painter.drawScrollHint(canvas, w, h, headerHeight, scrollOffset, maxScrollOffset,
                visibleSections, totalSections(), blockPaint);
    }

    private void settlePage(int direction, float velocityX) {
        if (direction == 0 || !canSwipe(direction)) {
            animatePageTo(0f, direction, false);
            return;
        }

        float width = Math.max(1f, pageWidth());
        boolean commit = shouldCommitPage(dragDx, velocityX, width);
        if (commit && listener != null) listener.onWeekSettling(direction);
        animatePageTo(commit ? -direction * width : 0f, direction, commit);
    }

    private void animatePageTo(float target, int direction, boolean commit) {
        cancelPageAnimation();
        final float start = dragDx;
        final float distance = Math.abs(target - start);
        pageAnimating = true;
        pendingCommit = commit ? direction : 0;
        pageAnimator = ValueAnimator.ofFloat(0f, 1f);
        pageAnimator.setDuration(pageAnimatorDuration(distance, pageWidth()));
        pageAnimator.setInterpolator(new PathInterpolator(0.2f, 0f, 0f, 1f));
        pageAnimator.addUpdateListener(animation -> {
            float fraction = (float) animation.getAnimatedValue();
            dragDx = start + (target - start) * fraction;
            invalidate();
        });
        pageAnimator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                pageAnimating = false;
                pageAnimator = null;
                pendingCommit = 0;
                dragDx = 0f;
                invalidate();
                if (commit && listener != null) listener.onWeekSwipe(direction);
            }
        });
        pageAnimator.start();
    }

    private void cancelPageAnimation() {
        if (pageAnimator != null) {
            pageAnimator.removeAllListeners();
            pageAnimator.cancel();
            pageAnimator = null;
        }
        pageAnimating = false;
        pendingCommit = 0;
    }

    /** 吸附动画还没走完又开始新手势：立刻提交上一次翻页，避免周数被吞掉。 */
    private void flushPendingCommit() {
        int d = pendingCommit;
        cancelPageAnimation();
        if (d != 0) {
            dragDx = 0f;
            if (listener != null) listener.onWeekSwipe(d);
        }
    }

    static long pageAnimatorDuration(float distance, float pageWidth) {
        return TimetableLayout.pageAnimatorDuration(distance, pageWidth);
    }

    static boolean shouldCommitPage(float dx, float velocityX, float pageWidth) {
        return TimetableLayout.shouldCommitPage(dx, velocityX, pageWidth);
    }

    private static int pairStart(int section) {
        int s = Math.max(1, Math.min(ScheduleSettings.MAX_SECTIONS, section));
        return (s % 2 == 1) ? s : s - 1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
        velocityTracker.addMovement(event);

        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (pageAnimating) flushPendingCommit();
                // 惯性/回弹中按下：停在当前位置接着拖
                if (!scroller.isFinished()) scroller.forceFinished(true);
                activePointerId = event.getPointerId(0);
                downX = event.getX();
                downY = event.getY();
                lastY = downY;
                // 从吸附动画中途接管时，保留当前页位置，继续跟手。
                gestureMode = 0;
                moved = false;
                longPressFired = false;
                removeCallbacks(longPressRunnable);
                postDelayed(longPressRunnable, android.view.ViewConfiguration.getLongPressTimeout());
                return true;

            case MotionEvent.ACTION_MOVE: {
                int pointerIndex = event.findPointerIndex(activePointerId);
                if (pointerIndex < 0) return true;
                float dx = event.getX(pointerIndex) - downX;
                float dy = event.getY(pointerIndex) - downY;
                if (gestureMode == 3) {
                    updateGhost(event.getY(pointerIndex));
                    return true;
                }
                if (gestureMode == 0) {
                    if (Math.abs(dx) > touchSlop && Math.abs(dx) > Math.abs(dy)) {
                        gestureMode = 1;
                        moved = true;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    } else if (Math.abs(dy) > touchSlop && Math.abs(dy) > Math.abs(dx)) {
                        gestureMode = 2;
                        moved = true;
                        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                    }
                }
                if (gestureMode != 0) removeCallbacks(longPressRunnable);
                if (gestureMode == 1) {
                    dragDx = resistDrag(dx);
                    invalidate();
                } else if (gestureMode == 2) {
                    float y = event.getY(pointerIndex);
                    scrollOffset += rubberBand(-(y - lastY));
                    lastY = y;
                    invalidate();
                }
                return true;
            }

            case MotionEvent.ACTION_UP: {
                removeCallbacks(longPressRunnable);
                if (gestureMode == 3) {
                    int d = ghostDay, a = ghostStart, b = ghostEnd;
                    ghostDay = 0;
                    invalidate();
                    finishGesture();
                    gestureMode = 0;
                    if (listener != null) listener.onEmptyRangeDrawn(d, a, b);
                    return true;
                }
                if (gestureMode == 2) {
                    velocityTracker.computeCurrentVelocity(1000, dp(8000));
                    float vy = velocityTracker.getYVelocity(activePointerId);
                    if (scrollOffset >= 0 && scrollOffset <= maxScrollOffset && Math.abs(vy) > dp(250)) {
                        // 惯性滚到头会冲出一小段再弹回
                        scroller.fling(0, Math.round(scrollOffset), 0, Math.round(-vy), 0, 0,
                                0, Math.round(maxScrollOffset), 0, Math.round(dp(36)));
                        postInvalidateOnAnimation();
                    } else {
                        springBackIfNeeded();
                    }
                }
                if (gestureMode == 1) {
                    velocityTracker.computeCurrentVelocity(1000, dp(6000));
                    float velocityX = velocityTracker.getXVelocity(activePointerId);
                    settlePage(dragDx < 0 ? 1 : (dragDx > 0 ? -1 : 0), velocityX);
                } else if (!moved && !longPressFired) {
                    handleTap(event.getX(), event.getY());
                }
                finishGesture();
                gestureMode = 0;
                return true;
            }

            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(longPressRunnable);
                if (gestureMode == 3) { ghostDay = 0; invalidate(); }
                if (gestureMode == 2) springBackIfNeeded();
                if (gestureMode == 1) settlePage(dragDx < 0 ? 1 : (dragDx > 0 ? -1 : 0), 0f);
                finishGesture();
                gestureMode = 0;
                return true;

            case MotionEvent.ACTION_POINTER_UP: {
                int upIndex = event.getActionIndex();
                if (event.getPointerId(upIndex) == activePointerId) {
                    int newIndex = upIndex == 0 ? 1 : 0;
                    activePointerId = event.getPointerId(newIndex);
                    downX = event.getX(newIndex) - dragDx;
                    downY = event.getY(newIndex);
                    lastY = downY;
                }
                return true;
            }
        }
        return super.onTouchEvent(event);
    }

    private void finishGesture() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
        activePointerId = MotionEvent.INVALID_POINTER_ID;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(ticker);
        cancelPageAnimation();
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
        super.onDetachedFromWindow();
    }

    private void handleTap(float x, float y) {
        if (listener == null) return;
        if (y < headerHeight) return;

        for (int i = hitBlocks.size() - 1; i >= 0; i--) {
            HitBlock b = hitBlocks.get(i);
            if (b.rect.contains(x, y)) {
                if (b.exam != null) {
                    listener.onExamClick(b.exam);
                } else if (b.todoHitRect != null && b.todoHitRect.contains(x, y)) {
                    listener.onTodoBadgeClick(b.course);
                } else if (b.clusterSize > 1 && b.clusterKey > 0 && b.active) {
                    List<ConflictItem> items = new ArrayList<>();
                    for (HitBlock other : hitBlocks) {
                        if (other.active && other.clusterKey == b.clusterKey && other.exam == null) {
                            items.add(new ConflictItem(other.course, other.session, other.hitSession,
                                    other.label, other.adjustId, other.extra));
                        }
                    }
                    if (items.size() > 1) {
                        items.sort((u, v) -> {
                            int cmp = Integer.compare(u.displaySession.startSection, v.displaySession.startSection);
                            return cmp != 0 ? cmp : Integer.compare(u.displaySession.endSection, v.displaySession.endSection);
                        });
                        listener.onConflictClick(items, b.course, currentWeek);
                    } else if (b.label != null) {
                        listener.onMarkedClick(b.course, b.hitSession, b.label, b.adjustId, b.extra);
                    } else {
                        listener.onSessionClick(b.course, b.hitSession, b.active);
                    }
                } else if (b.label != null) {
                    listener.onMarkedClick(b.course, b.hitSession, b.label, b.adjustId, b.extra);
                } else {
                    listener.onSessionClick(b.course, b.hitSession, b.active);
                }
                return;
            }
        }

        if (x > timeColWidth) {
            float colWidth = columnWidth(getWidth());
            int day = Math.min(columnCount(), Math.max(1, (int) ((x - timeColWidth) / colWidth) + 1));
            for (int sec = 1; sec <= totalSections(); sec++) {
                if (y >= sectionTopY(sec) && y <= sectionBottomY(sec)) {
                    listener.onEmptySlotClick(day, sec);
                    return;
                }
            }
        }
    }

    private void fireLongPress() {
        if (listener == null || gestureMode != 0 || moved || pageAnimating) return;
        if (downY < headerHeight) return;
        for (int i = hitBlocks.size() - 1; i >= 0; i--) {
            HitBlock b = hitBlocks.get(i);
            if (b.rect.contains(downX, downY)) {
                longPressFired = true;
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                if (b.exam != null) listener.onExamClick(b.exam);
                else listener.onSessionLongPress(b.course, b.hitSession, b.active, b.label,
                        b.adjustId, b.extra, currentWeek);
                return;
            }
        }
        if (downX <= timeColWidth) return;
        float colWidth = columnWidth(getWidth());
        int day = Math.min(columnCount(), Math.max(1, (int) ((downX - timeColWidth) / colWidth) + 1));
        int sec = sectionAt(downY);
        if (occupied(day, sec)) {
            longPressFired = true;
            listener.onHint("这一格被一节课占着，长按那节课可以编辑");
            return;
        }
        longPressFired = true;
        ghostDay = day;
        ghostStart = sec;
        ghostEnd = sec;
        ghostAnchor = sec;
        ghostClipped = false;
        gestureMode = 3;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        invalidate();
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private float sp(float v) {
        return v * getResources().getDisplayMetrics().scaledDensity;
    }
}
