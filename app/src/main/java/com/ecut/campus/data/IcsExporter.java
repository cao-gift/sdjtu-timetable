package com.ecut.campus.data;

import com.ecut.campus.ScheduleSettings;
import com.ecut.campus.WeekMath;

import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * 课表 / 考试安排 → ICS（RFC 5545）文本。
 * 统一使用标准 UTC 绝对时间戳（yyyyMMdd'T'HHmmss'Z'），避免国产系统日历对 TZID/VTIMEZONE 解析缺失导致日期丢失或冲突；
 * 全部事件按日期时间升序排列、去重，并生成确定性 UID 避免重复导入产生冲突。
 */
public final class IcsExporter {

    private static final TimeZone CN_TZ = TimeZone.getTimeZone("Asia/Shanghai");
    private static final TimeZone UTC_TZ = TimeZone.getTimeZone("UTC");

    private IcsExporter() {}

    private static final class Event {
        final String ymd;
        final int startMinutes;
        final int endMinutes;
        final String summary;
        final String location;
        final String description;

        Event(String ymd, int startMinutes, int endMinutes, String summary, String location, String description) {
            this.ymd = ymd;
            this.startMinutes = startMinutes;
            this.endMinutes = endMinutes;
            this.summary = summary;
            this.location = location;
            this.description = description;
        }
    }

    /** 生成整学期 ICS 文本；无开学日期时返回 null（由调用方提示）。 */
    public static String export(Timetable t, ScheduleSettings schedule, String semesterLabel) {
        if (t == null || t.semester == null) return null;
        String start = WeekMath.normalizeToMonday(t.semester.startDate);
        if (start.isEmpty() || !Dates.valid(start)) return null;
        int totalWeeks = SemesterMetaPolicy.clampWeeks(t.semester.totalWeeks);
        ScheduleSettings sched = schedule != null ? schedule : ScheduleSettings.createDefault();

        List<Event> events = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int week = 1; week <= totalWeeks; week++) {
            WeekOverlay ov = WeekOverlay.build(t, start, week);
            for (Timetable.Course c : t.courses) {
                for (Timetable.Session s : c.sessions) {
                    if (s.dayOfWeek < 1 || s.dayOfWeek > 7 || !s.isActive(week)) continue;
                    if (ov.cancelled.containsKey(s)) continue;
                    collectEvent(events, seen, start, sched, week, c, s);
                }
            }
            for (WeekOverlay.Extra ex : ov.extras) {
                if (ex.session.dayOfWeek < 1 || ex.session.dayOfWeek > 7) continue;
                collectEvent(events, seen, start, sched, week, ex.course, ex.session);
            }
        }

        Collections.sort(events, Comparator
                .comparing((Event e) -> e.ymd)
                .thenComparingInt(e -> e.startMinutes)
                .thenComparingInt(e -> e.endMinutes)
                .thenComparing(e -> e.summary)
                .thenComparing(e -> e.location));

        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCALENDAR\r\n");
        sb.append("VERSION:2.0\r\n");
        sb.append("PRODID:-//LvLv//Timetable//CN\r\n");
        sb.append("CALSCALE:GREGORIAN\r\n");
        sb.append("METHOD:PUBLISH\r\n");
        sb.append("X-WR-CALNAME:").append(escape(semesterLabel)).append("\r\n");
        sb.append("X-WR-TIMEZONE:Asia/Shanghai\r\n");

        String stamp = nowUtcStamp();
        String semTag = safeUidPart(t.semester.id);
        Map<String, Integer> uidCounts = new HashMap<>();

        for (Event e : events) {
            String dtStart = toUtcStamp(e.ymd, e.startMinutes);
            String dtEnd = toUtcStamp(e.ymd, e.endMinutes);
            String baseUid = "lvlv-" + semTag + "-" + e.ymd.replace("-", "")
                    + "-" + hm(e.startMinutes) + "-" + hm(e.endMinutes)
                    + "-" + stableHash(e.summary + "|" + e.location);
            int seq = uidCounts.getOrDefault(baseUid, 0);
            uidCounts.put(baseUid, seq + 1);
            String uid = (seq == 0 ? baseUid : baseUid + "-" + seq) + "@ecut.campus";

            sb.append("BEGIN:VEVENT\r\n");
            sb.append("UID:").append(uid).append("\r\n");
            sb.append("DTSTAMP:").append(stamp).append("\r\n");
            sb.append("DTSTART:").append(dtStart).append("\r\n");
            sb.append("DTEND:").append(dtEnd).append("\r\n");
            sb.append("SUMMARY:").append(escape(e.summary)).append("\r\n");
            if (!e.location.isEmpty()) sb.append("LOCATION:").append(escape(e.location)).append("\r\n");
            if (!e.description.isEmpty()) sb.append("DESCRIPTION:").append(escape(e.description)).append("\r\n");
            sb.append("STATUS:CONFIRMED\r\n");
            sb.append("SEQUENCE:0\r\n");
            sb.append("END:VEVENT\r\n");
        }
        sb.append("END:VCALENDAR\r\n");
        return sb.toString();
    }

    private static void collectEvent(List<Event> out, Set<String> seen, String start,
                                     ScheduleSettings sched, int week,
                                     Timetable.Course c, Timetable.Session s) {
        String ymd = Dates.dateOf(start, week, s.dayOfWeek);
        if (!Dates.valid(ymd)) return;

        int secStart = Math.max(1, Math.min(ScheduleSettings.MAX_SECTIONS, s.startSection));
        int secEnd = Math.max(secStart, Math.min(ScheduleSettings.MAX_SECTIONS, s.endSection));
        int startMinutes = s.hasCustomTime()
                ? Timetable.Session.minutesOf(s.timeStart) : sched.startMinutes(secStart);
        int endMinutes = s.hasCustomTime()
                ? Timetable.Session.minutesOf(s.timeEnd) : sched.endMinutes(secEnd);
        if (endMinutes <= startMinutes) {
            endMinutes = Math.min(24 * 60 - 1, startMinutes + 45);
        }

        String summary = c.name == null ? "" : c.name.trim();
        String loc = s.location == null ? "" : s.location.trim();
        String dedupeKey = ymd + "|" + startMinutes + "|" + endMinutes + "|" + summary + "|" + loc;
        if (!seen.add(dedupeKey)) return;

        String teacher = s.teacherFor(week, c.teacher).trim();
        String weekNote = "第 " + week + " 周 " + Dates.cnDay(s.dayOfWeek);
        String desc = teacher.isEmpty() ? weekNote : (weekNote + " · " + teacher);
        out.add(new Event(ymd, startMinutes, endMinutes, summary, loc, desc));
    }

    /** 将东八区 (yyyy-MM-dd, 当日分钟数) 转为 RFC 5545 标准 UTC 时间戳 yyyyMMdd'T'HHmmss'Z'。 */
    static String toUtcStamp(String ymd, int minutes) {
        String[] parts = ymd.split("-");
        int year = Integer.parseInt(parts[0]);
        int month = Integer.parseInt(parts[1]) - 1;
        int day = Integer.parseInt(parts[2]);
        int clamped = Math.max(0, Math.min(24 * 60 - 1, minutes));
        Calendar cal = Calendar.getInstance(CN_TZ, Locale.US);
        cal.clear();
        cal.set(year, month, day, clamped / 60, clamped % 60, 0);
        SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        fmt.setTimeZone(UTC_TZ);
        return fmt.format(cal.getTime());
    }

    private static String nowUtcStamp() {
        SimpleDateFormat stampFmt = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        stampFmt.setTimeZone(UTC_TZ);
        return stampFmt.format(new Date());
    }

    private static String hm(int minutes) {
        int m = Math.max(0, Math.min(24 * 60 - 1, minutes));
        return String.format(Locale.US, "%02d%02d", m / 60, m % 60);
    }

    private static String safeUidPart(String raw) {
        if (raw == null || raw.trim().isEmpty()) return "sem";
        String cleaned = raw.trim().replaceAll("[^A-Za-z0-9_-]", "");
        return cleaned.isEmpty() ? "sem" : cleaned;
    }

    private static String stableHash(String s) {
        long h = 0xcbf29ce484222325L;
        for (byte b : s.getBytes(StandardCharsets.UTF_8)) {
            h ^= (b & 0xffL);
            h *= 0x100000001b3L;
        }
        return Long.toHexString(h & 0xffffffffL);
    }

    static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\r\n", "\\n")
                .replace("\n", "\\n");
    }

    /** 将考试安排导出为标准 ICS 日历文本（带考前一天与考前一小时 VALARM 提醒）。 */
    public static String exportExams(List<ExamCountdown.Entry> exams, String calendarName) {
        if (exams == null || exams.isEmpty()) return null;
        List<ExamCountdown.Entry> valid = new ArrayList<>();
        for (ExamCountdown.Entry e : exams) {
            if (e != null && e.hasDate() && Dates.valid(e.date)) valid.add(e);
        }
        if (valid.isEmpty()) return null;
        Collections.sort(valid, Comparator
                .comparing((ExamCountdown.Entry e) -> e.date)
                .thenComparingInt(e -> e.startMin >= 0 ? e.startMin : 9 * 60)
                .thenComparing(e -> e.name == null ? "" : e.name));

        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCALENDAR\r\n");
        sb.append("VERSION:2.0\r\n");
        sb.append("PRODID:-//LvLv//Exams//CN\r\n");
        sb.append("CALSCALE:GREGORIAN\r\n");
        sb.append("METHOD:PUBLISH\r\n");
        sb.append("X-WR-CALNAME:").append(escape(calendarName == null || calendarName.isEmpty()
                ? "考试安排 · 驴驴课表" : calendarName)).append("\r\n");
        sb.append("X-WR-TIMEZONE:Asia/Shanghai\r\n");

        String stamp = nowUtcStamp();
        Map<String, Integer> uidCounts = new HashMap<>();
        for (ExamCountdown.Entry e : valid) {
            int st = e.startMin >= 0 ? e.startMin : 9 * 60;
            int en = e.endMin > st ? e.endMin : Math.min(23 * 60 + 55, st + 120);
            String d = e.date.replace("-", "");
            String baseUid = "lvlv-exam-" + d + "-" + hm(st) + "-" + stableHash(e.name + "|" + e.room);
            int seq = uidCounts.getOrDefault(baseUid, 0);
            uidCounts.put(baseUid, seq + 1);
            String uid = (seq == 0 ? baseUid : baseUid + "-" + seq) + "@ecut.campus";

            sb.append("BEGIN:VEVENT\r\n");
            sb.append("UID:").append(uid).append("\r\n");
            sb.append("DTSTAMP:").append(stamp).append("\r\n");
            sb.append("DTSTART:").append(toUtcStamp(e.date, st)).append("\r\n");
            sb.append("DTEND:").append(toUtcStamp(e.date, en)).append("\r\n");
            sb.append("SUMMARY:").append(escape("[考试] " + e.name)).append("\r\n");
            if (!e.room.isEmpty()) sb.append("LOCATION:").append(escape(e.room)).append("\r\n");
            StringBuilder desc = new StringBuilder();
            if (!e.seat.isEmpty()) desc.append("座位：").append(e.seat);
            if (!e.code.isEmpty()) {
                if (desc.length() > 0) desc.append(" · ");
                desc.append("课程号：").append(e.code);
            }
            if (desc.length() > 0) sb.append("DESCRIPTION:").append(escape(desc.toString())).append("\r\n");
            sb.append("STATUS:CONFIRMED\r\n");
            sb.append("SEQUENCE:0\r\n");
            // 考前一天提醒
            sb.append("BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-P1D\r\nDESCRIPTION:")
                    .append(escape("明天考试：" + e.name)).append("\r\nEND:VALARM\r\n");
            // 考前一小时提醒
            sb.append("BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT1H\r\nDESCRIPTION:")
                    .append(escape("1小时后考试：" + e.name)).append("\r\nEND:VALARM\r\n");
            sb.append("END:VEVENT\r\n");
        }
        sb.append("END:VCALENDAR\r\n");
        return sb.toString();
    }
}
