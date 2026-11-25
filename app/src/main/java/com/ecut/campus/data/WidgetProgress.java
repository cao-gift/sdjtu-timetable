package com.ecut.campus.data;

import java.util.List;

/**
 * 2×2「下一节课倒计时」与 4×1「今日进度条」桌面小组件的纯 Java 计算模型。
 */
public final class WidgetProgress {

    public static final int MODE_LIVE = 1;
    public static final int MODE_NEXT = 2;
    public static final int MODE_TOMORROW = 3;
    public static final int MODE_DONE = 4;
    public static final int MODE_EMPTY = 5;
    public static final int MODE_SEMESTER = 6;

    public static final class Snapshot {
        public int mode = MODE_EMPTY;
        public String badge = "TODAY";
        public String title = "今日无课";
        public String detail = "";
        public String primaryReadout = "—";
        public String ratioLabel = "0/0";
        /** 当日课程完成进度 0..100（用于 4×1 今日进度条）。 */
        public int dayPercent = 0;
        /** 当前正在上课的进度 0..100（非上课中为 -1）。 */
        public int livePercent = -1;
        /** 若需 Chronometer 倒计时，目标分钟（当天分钟数），否则 -1。 */
        public int countdownMin = -1;
        public int completedCount = 0;
        public int totalCount = 0;
    }

    private WidgetProgress() {}

    public static Snapshot build(List<DayPlan.Item> today,
                                 List<DayPlan.Item> tomorrow,
                                 int nowMin,
                                 String examHint) {
        return build(today, tomorrow, nowMin, examHint, "", WidgetDay.OK, "", "", null);
    }

    /**
     * 随学期自动切换 + 作业截止提示版：
     * 开学前显示「距开学 N 天」，法定假期 / 学期结束后显示假期倒计时，临近作业截止时在小组件提示。
     */
    public static Snapshot build(List<DayPlan.Item> today,
                                 List<DayPlan.Item> tomorrow,
                                 int nowMin,
                                 String examHint,
                                 String todoHint,
                                 int semesterState,
                                 String ymd,
                                 String startDate,
                                 Holidays.BreakInfo holidayBreak) {
        Snapshot s = new Snapshot();
        String safeTodo = todoHint == null ? "" : todoHint.trim();

        if (semesterState == WidgetDay.BEFORE && Dates.valid(startDate) && Dates.valid(ymd)) {
            int days = (int) Math.max(1L, Dates.epochDay(startDate) - Dates.epochDay(ymd));
            s.mode = MODE_SEMESTER;
            s.badge = "OPENING · 距开学";
            s.primaryReadout = days + "天";
            s.title = "距开学 " + days + " 天";
            s.detail = "第 1 周 " + WidgetDay.dot(startDate) + " 开始";
            s.ratioLabel = "待开学";
            return s;
        }
        if (semesterState == WidgetDay.AFTER) {
            s.mode = MODE_SEMESTER;
            s.badge = "VACATION · 假期中";
            s.primaryReadout = "放假";
            s.title = "本学期课程已结束";
            s.detail = safeTodo.isEmpty() ? "假期愉快 · 新学期自动切换" : safeTodo;
            s.ratioLabel = "假期中";
            return s;
        }
        if (holidayBreak != null && (today == null || today.isEmpty())) {
            s.mode = MODE_SEMESTER;
            s.badge = "HOLIDAY · " + holidayBreak.label + "假期";
            s.primaryReadout = "剩" + holidayBreak.daysLeft + "天";
            s.title = holidayBreak.label + "放假中";
            s.detail = safeTodo.isEmpty()
                    ? "共 " + holidayBreak.totalDays + " 天 · 含今天剩 " + holidayBreak.daysLeft + " 天"
                    : safeTodo;
            s.ratioLabel = "假期中";
            return s;
        }

        int total = today == null ? 0 : today.size();
        s.totalCount = total;

        int done = 0;
        DayPlan.Item live = null;
        DayPlan.Item next = null;
        if (today != null) {
            for (DayPlan.Item it : today) {
                if (it.endMin <= nowMin) {
                    done++;
                } else if (it.startMin <= nowMin) {
                    if (live == null) live = it;
                } else {
                    if (next == null) next = it;
                }
            }
        }
        s.completedCount = done;

        if (live != null) {
            s.mode = MODE_LIVE;
            s.badge = "NOW · 正在上课";
            s.title = live.name;
            String endHm = Timetable.Session.formatHm(live.endMin);
            s.detail = live.location.isEmpty() ? endHm + " 下课" : live.location + " · " + endHm + " 下课";
            int rem = Math.max(1, live.endMin - nowMin);
            s.primaryReadout = rem + "分";
            s.countdownMin = live.endMin;
            int dur = Math.max(1, live.endMin - live.startMin);
            int elapsed = Math.max(0, Math.min(dur, nowMin - live.startMin));
            s.livePercent = Math.round(elapsed * 100f / dur);
            s.dayPercent = total <= 0 ? 100 : Math.min(100, Math.round((done + elapsed / (float) dur) * 100f / total));
            s.ratioLabel = (done + 1) + "/" + total;
            return s;
        }

        if (next != null) {
            s.mode = MODE_NEXT;
            s.badge = "NEXT · 下一节";
            s.title = next.name;
            String startHm = Timetable.Session.formatHm(next.startMin);
            s.detail = next.location.isEmpty() ? startHm + " 上课" : startHm + " · " + next.location;
            int diff = next.startMin - nowMin;
            if (diff <= 180) {
                s.countdownMin = next.startMin;
                s.primaryReadout = diff >= 60 ? (diff / 60) + "h" + (diff % 60) + "m" : diff + "分";
            } else {
                s.primaryReadout = startHm;
            }
            s.dayPercent = total <= 0 ? 0 : Math.round(done * 100f / total);
            s.ratioLabel = done + "/" + total;
            return s;
        }

        if (total > 0) {
            s.mode = MODE_DONE;
            s.badge = "DONE · 今日已上完";
            s.dayPercent = 100;
            s.ratioLabel = total + "/" + total;
            if (examHint != null && !examHint.isEmpty()) {
                s.title = examHint;
                s.detail = "今日 " + total + " 门课已全部结束";
                s.primaryReadout = "考试";
            } else if (!safeTodo.isEmpty()) {
                s.title = safeTodo;
                s.detail = "今日 " + total + " 门课已全部结束";
                s.primaryReadout = "作业";
            } else if (tomorrow != null && !tomorrow.isEmpty()) {
                DayPlan.Item first = tomorrow.get(0);
                s.title = "明天 " + first.name;
                s.detail = Timetable.Session.formatHm(first.startMin)
                        + (first.location.isEmpty() ? "" : " · " + first.location);
                s.primaryReadout = Timetable.Session.formatHm(first.startMin);
            } else {
                s.title = "今日课程已完成";
                s.detail = "明天也没有课，好好休息";
                s.primaryReadout = "100%";
            }
            return s;
        }

        if (examHint != null && !examHint.isEmpty()) {
            s.mode = MODE_TOMORROW;
            s.badge = "EXAM · 近期考试";
            s.title = examHint;
            s.detail = safeTodo.isEmpty() ? "今日无课" : safeTodo;
            s.primaryReadout = "备考";
            s.ratioLabel = "0/0";
            return s;
        }

        if (!safeTodo.isEmpty()) {
            s.mode = MODE_TOMORROW;
            s.badge = "TODO · 作业临近";
            s.title = safeTodo;
            s.detail = tomorrow != null && !tomorrow.isEmpty()
                    ? "明天首节 " + tomorrow.get(0).name : "今日无课";
            s.primaryReadout = "截止";
            s.ratioLabel = "0/0";
            return s;
        }

        if (tomorrow != null && !tomorrow.isEmpty()) {
            DayPlan.Item first = tomorrow.get(0);
            s.mode = MODE_TOMORROW;
            s.badge = "TOMORROW · 明日首节";
            s.title = first.name;
            s.detail = Timetable.Session.formatHm(first.startMin)
                    + (first.location.isEmpty() ? "" : " · " + first.location);
            s.primaryReadout = Timetable.Session.formatHm(first.startMin);
            s.ratioLabel = "0/0";
            return s;
        }

        s.mode = MODE_EMPTY;
        s.badge = "FREE · 今日无课";
        s.title = "今日与明日均无课";
        s.detail = "点击打开驴驴课表";
        s.primaryReadout = "休";
        s.ratioLabel = "0/0";
        return s;
    }
}
