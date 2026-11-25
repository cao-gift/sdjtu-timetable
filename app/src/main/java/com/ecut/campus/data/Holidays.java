package com.ecut.campus.data;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置法定节假日与调休（国务院办公厅公布）。
 * 补课日上哪天的课按调休对应关系自动排入；用户手动设置的停课/调休始终优先。
 */
public final class Holidays {

    private Holidays() {}

    public static final class Day {
        public final String date, label, fromDate; // fromDate 仅补课日
        public final boolean off;

        Day(String date, String label, boolean off, String fromDate) {
            this.date = date; this.label = label; this.off = off; this.fromDate = fromDate;
        }
    }

    /** 连续假期信息（供桌面小组件显示假期倒计时）。 */
    public static final class BreakInfo {
        public final String label;
        /** 含今天在内还剩几天假（≥1）。 */
        public final int daysLeft;
        public final int totalDays;

        public BreakInfo(String label, int daysLeft, int totalDays) {
            this.label = label;
            this.daysLeft = daysLeft;
            this.totalDays = totalDays;
        }
    }

    public static final List<Day> DAYS = new ArrayList<>();

    static {
        // 2026 年法定节假日与调休
        off("2026-01-01", "2026-01-03", "元旦");
        makeup("2026-01-04", "元旦调休", "2026-01-02");
        off("2026-02-15", "2026-02-23", "春节");
        makeup("2026-02-14", "春节调休", "2026-02-19");
        makeup("2026-02-28", "春节调休", "2026-02-20");
        off("2026-04-04", "2026-04-06", "清明");
        off("2026-05-01", "2026-05-05", "劳动节");
        makeup("2026-05-09", "劳动节调休", "2026-05-04");
        off("2026-06-19", "2026-06-21", "端午");
        off("2026-09-25", "2026-09-27", "中秋");
        off("2026-10-01", "2026-10-07", "国庆");
        makeup("2026-09-20", "国庆调休", "2026-10-06");
        makeup("2026-10-10", "国庆调休", "2026-10-07");

        // 2027 年法定节假日与调休（按国家常规排期预设，学校另有通知时以用户调整为准）
        off("2027-01-01", "2027-01-03", "元旦");
        off("2027-02-05", "2027-02-12", "春节");
        makeup("2027-02-14", "春节调休", "2027-02-12");
        off("2027-04-03", "2027-04-05", "清明");
        off("2027-05-01", "2027-05-05", "劳动节");
        makeup("2027-05-08", "劳动节调休", "2027-05-04");
        off("2027-06-09", "2027-06-11", "端午");
        off("2027-09-15", "2027-09-17", "中秋");
        off("2027-10-01", "2027-10-07", "国庆");
        makeup("2027-09-26", "国庆调休", "2027-10-06");
        makeup("2027-10-09", "国庆调休", "2027-10-07");
    }

    private static void off(String from, String to, String label) {
        for (String d = from; d.compareTo(to) <= 0; d = Dates.addDays(d, 1)) {
            DAYS.add(new Day(d, label, true, ""));
        }
    }

    private static void makeup(String date, String label, String fromDate) {
        DAYS.add(new Day(date, label, false, fromDate));
    }

    /** 内置 + 课表里的用户安排（用户的优先）。返回 null 表示普通日子。 */
    public static Day lookup(Timetable t, String ymd) {
        if (ymd == null || ymd.isEmpty()) return null;
        if (t != null) {
            for (int i = t.adjusts.size() - 1; i >= 0; i--) {
                Timetable.Adjust a = t.adjusts.get(i);
                if (!ymd.equals(a.date)) continue;
                if (Timetable.Adjust.OFF.equals(a.type)) {
                    return new Day(ymd, a.note.isEmpty() ? "放假" : a.note, true, "");
                }
                if (Timetable.Adjust.MAKEUP.equals(a.type)) {
                    return new Day(ymd, a.note.isEmpty() ? "补课" : a.note, false, a.fromDate);
                }
            }
        }
        for (Day d : DAYS) if (d.date.equals(ymd)) return d;
        return null;
    }

    /** 如果 ymd 当天正在法定/自定义放假中，返回连续假期信息与剩余天数；否则返回 null。 */
    public static BreakInfo activeBreak(Timetable t, String ymd) {
        if (!Dates.valid(ymd)) return null;
        Day today = lookup(t, ymd);
        if (today == null || !today.off) return null;
        int before = 0;
        String cur = Dates.addDays(ymd, -1);
        while (before < 30) {
            Day prev = lookup(t, cur);
            if (prev == null || !prev.off) break;
            before++;
            cur = Dates.addDays(cur, -1);
        }
        int after = 0;
        cur = Dates.addDays(ymd, 1);
        while (after < 30) {
            Day next = lookup(t, cur);
            if (next == null || !next.off) break;
            after++;
            cur = Dates.addDays(cur, 1);
        }
        String name = today.label == null || today.label.isEmpty() ? "放假" : today.label;
        return new BreakInfo(name, after + 1, before + after + 1);
    }

    /** 本周全部法定/自定义调休说明，例如「周六(10/10) 按周三上课」。 */
    public static List<String> weekMakeupNotices(Timetable t, String start, int week) {
        List<String> out = new ArrayList<>();
        if (!Dates.valid(start) || week < 1) return out;
        for (int d = 1; d <= 7; d++) {
            String ymd = Dates.dateOf(start, week, d);
            Day h = lookup(t, ymd);
            if (h == null || h.off || !Dates.valid(h.fromDate)) continue;
            int fd = Dates.dayOfWeek(h.fromDate);
            if (fd < 1 || fd > 7) continue;
            out.add(Dates.cnDay(d) + "(" + Dates.md(ymd) + ") 按" + Dates.cnDay(fd) + "上课");
        }
        return out;
    }

    /** 本周调休单行短摘要（供课表页副标题展示）；本周无调休时返回空串。 */
    public static String weekNoticeSummary(Timetable t, String start, int week) {
        List<String> list = weekMakeupNotices(t, start, week);
        if (list.isEmpty()) return "";
        if (list.size() == 1) return "调休：" + list.get(0);
        return "本周 " + list.size() + " 天调休：" + list.get(0);
    }
}
