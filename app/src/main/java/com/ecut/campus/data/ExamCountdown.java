package com.ecut.campus.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 考试倒计时、课表叠加、考前提醒调度（考前一天 + 考前一小时）纯 Java 模型。
 */
public final class ExamCountdown {

    private static final Pattern DATE = Pattern.compile(
            "(?<!\\d)(\\d{4})\\s*[-./年]\\s*(\\d{1,2})\\s*[-./月]\\s*(\\d{1,2})(?!\\d)日?");
    private static final Pattern CLOCK = Pattern.compile("(?<!\\d)([01]?\\d|2[0-3])\\s*[:：]\\s*([0-5]\\d)(?!\\d)");
    public static final long ONE_DAY_MS = 24L * 60 * 60 * 1000;
    public static final long ONE_HOUR_MS = 60L * 60 * 1000;

    private ExamCountdown() {}

    public static final class Entry {
        public String name = "";
        public String code = "";
        public String timeRaw = "";
        public String room = "";
        public String seat = "";
        /** yyyy-MM-dd，无日期时为空。 */
        public String date = "";
        /** 当天起始/结束分钟数；无具体时刻时为 -1。 */
        public int startMin = -1;
        public int endMin = -1;
        /** 考试开始/结束本地时间戳（毫秒）；无日期时为 -1。 */
        public long startMillis = -1L;
        public long endMillis = -1L;

        public boolean hasDate() { return !date.isEmpty() && startMillis > 0; }
        public boolean hasClock() { return hasDate() && startMin >= 0 && endMin > startMin; }
    }

    public static final class ReminderTrigger {
        public final Entry entry;
        public final long fireAtMillis;
        public final String leadLabel;

        public ReminderTrigger(Entry entry, long fireAtMillis, String leadLabel) {
            this.entry = entry;
            this.fireAtMillis = fireAtMillis;
            this.leadLabel = leadLabel;
        }
    }

    /** 从 SdjtuStudyStore 保存的考试数据对象（{term, exams:[...]}）解析出完整列表。 */
    public static List<Entry> parseAll(JSONObject examData) {
        List<Entry> out = new ArrayList<>();
        if (examData == null) return out;
        JSONArray arr = examData.optJSONArray("exams");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject row = arr.optJSONObject(i);
            if (row == null) continue;
            Entry e = parseOne(row);
            if (!e.name.isEmpty()) out.add(e);
        }
        out.sort(Comparator.comparingLong(e -> e.startMillis > 0 ? e.startMillis : Long.MAX_VALUE));
        return out;
    }

    public static Entry parseOne(JSONObject row) {
        Entry e = new Entry();
        if (row == null) return e;
        e.name = row.optString("name", "").trim();
        e.code = row.optString("code", "").trim();
        e.timeRaw = row.optString("time", "").trim();
        e.room = row.optString("room", "").trim();
        e.seat = row.optString("seat", "").trim();
        parseTimeInto(e);
        return e;
    }

    static void parseTimeInto(Entry e) {
        if (e.timeRaw.isEmpty()) return;
        Matcher dm = DATE.matcher(e.timeRaw);
        if (!dm.find()) return;
        int y = Integer.parseInt(dm.group(1));
        int m = Integer.parseInt(dm.group(2));
        int d = Integer.parseInt(dm.group(3));
        if (m < 1 || m > 12 || d < 1 || d > 31) return;
        e.date = String.format(Locale.US, "%04d-%02d-%02d", y, m, d);

        Matcher cm = CLOCK.matcher(e.timeRaw);
        int sMin = -1, eMin = -1;
        if (cm.find(dm.end())) {
            sMin = Integer.parseInt(cm.group(1)) * 60 + Integer.parseInt(cm.group(2));
            if (cm.find()) {
                eMin = Integer.parseInt(cm.group(1)) * 60 + Integer.parseInt(cm.group(2));
            }
            if (eMin <= sMin) eMin = Math.min(23 * 60 + 55, sMin + 120);
        }
        e.startMin = sMin;
        e.endMin = eMin;

        Calendar cal = Calendar.getInstance();
        cal.clear();
        cal.set(y, m - 1, d, sMin >= 0 ? sMin / 60 : 9, sMin >= 0 ? sMin % 60 : 0, 0);
        e.startMillis = cal.getTimeInMillis();
        Calendar endCal = Calendar.getInstance();
        endCal.clear();
        int endM = eMin > 0 ? eMin : (sMin >= 0 ? sMin + 120 : 11 * 60);
        endCal.set(y, m - 1, d, endM / 60, endM % 60, 0);
        e.endMillis = endCal.getTimeInMillis();
    }

    /** 倒计时短标签：已结束 / 进行中 / 剩 X 小时 / 明天 / 剩 N 天 / 待公布。 */
    public static String countdownLabel(Entry e, long nowMillis) {
        if (e == null || !e.hasDate()) return "待公布";
        if (nowMillis >= e.endMillis) return "已结束";
        if (nowMillis >= e.startMillis) return "进行中";
        long diff = e.startMillis - nowMillis;
        if (diff < ONE_HOUR_MS) {
            return "剩 " + Math.max(1, diff / 60_000L) + " 分";
        }
        if (diff < ONE_DAY_MS) {
            long hrs = diff / ONE_HOUR_MS;
            return hrs <= 6 ? "剩 " + hrs + " 小时" : (sameDay(nowMillis, e.startMillis) ? "今天" : "明天");
        }
        int days = calendarDaysBetween(nowMillis, e.startMillis);
        if (days <= 0) return "今天";
        if (days == 1) return "明天";
        return "剩 " + days + " 天";
    }

    /** 小组件顶部/空档展示的近期考试短提示（withinDays 天内）。 */
    public static String upcomingBanner(List<Entry> list, long nowMillis, int withinDays) {
        if (list == null || list.isEmpty()) return "";
        long horizon = nowMillis + Math.max(1, withinDays) * ONE_DAY_MS;
        for (Entry e : list) {
            if (!e.hasDate() || e.endMillis <= nowMillis) continue;
            if (e.startMillis <= horizon) {
                return "考试 · " + e.name + "（" + countdownLabel(e, nowMillis) + "）";
            }
            break;
        }
        return "";
    }

    /** 找出下一次应触发的考试提醒（每场考试支持考前一天与考前一小时各提醒一次）。 */
    public static ReminderTrigger nextReminder(List<Entry> list, long nowMillis) {
        if (list == null || list.isEmpty()) return null;
        ReminderTrigger best = null;
        for (Entry e : list) {
            if (!e.hasDate() || e.startMillis <= nowMillis) continue;
            long dayBefore = e.startMillis - ONE_DAY_MS;
            long hourBefore = e.startMillis - ONE_HOUR_MS;
            if (dayBefore > nowMillis && (best == null || dayBefore < best.fireAtMillis)) {
                best = new ReminderTrigger(e, dayBefore, "明天考试");
            }
            if (hourBefore > nowMillis && (best == null || hourBefore < best.fireAtMillis)) {
                best = new ReminderTrigger(e, hourBefore, "1 小时后考试");
            }
        }
        return best;
    }

    /** 找出落在指定学期第 week 周的考试列表（供课表网格叠加显示）。 */
    public static List<Entry> examsInWeek(List<Entry> list, String semesterStartDate, int week) {
        if (list == null || list.isEmpty() || !Dates.valid(semesterStartDate) || week < 1) {
            return Collections.emptyList();
        }
        List<Entry> out = new ArrayList<>();
        for (Entry e : list) {
            if (!e.hasDate()) continue;
            if (Dates.weekOf(semesterStartDate, e.date) == week) {
                out.add(e);
            }
        }
        return out;
    }

    private static boolean sameDay(long a, long b) {
        Calendar ca = Calendar.getInstance();
        ca.setTimeInMillis(a);
        Calendar cb = Calendar.getInstance();
        cb.setTimeInMillis(b);
        return ca.get(Calendar.YEAR) == cb.get(Calendar.YEAR)
                && ca.get(Calendar.DAY_OF_YEAR) == cb.get(Calendar.DAY_OF_YEAR);
    }

    private static int calendarDaysBetween(long fromMs, long toMs) {
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(fromMs);
        a.set(Calendar.HOUR_OF_DAY, 0);
        a.set(Calendar.MINUTE, 0);
        a.set(Calendar.SECOND, 0);
        a.set(Calendar.MILLISECOND, 0);
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(toMs);
        b.set(Calendar.HOUR_OF_DAY, 0);
        b.set(Calendar.MINUTE, 0);
        b.set(Calendar.SECOND, 0);
        b.set(Calendar.MILLISECOND, 0);
        return (int) Math.round((b.getTimeInMillis() - a.getTimeInMillis()) / (double) ONE_DAY_MS);
    }
}
