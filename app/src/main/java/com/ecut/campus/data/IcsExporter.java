package com.ecut.campus.data;

import com.ecut.campus.ScheduleSettings;
import com.ecut.campus.WeekMath;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 课表 → ICS（RFC 5545）文本。全部展开为具体日期的 VEVENT，
 * 每节课一个全天事件外的 timed event，RRULE 不使用（学期内逐周展开）。
 */
public final class IcsExporter {

    private IcsExporter() {}

    /** 生成整学期 ICS 文本；无开学日期时返回 null（由调用方提示）。 */
    public static String export(Timetable t, ScheduleSettings schedule, String semesterLabel) {
        if (t == null || t.semester == null) return null;
        String start = WeekMath.normalizeToMonday(t.semester.startDate);
        if (start.isEmpty()) return null;
        int totalWeeks = SemesterMetaPolicy.clampWeeks(t.semester.totalWeeks);
        ScheduleSettings sched = schedule != null ? schedule : ScheduleSettings.createDefault();

        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCALENDAR\r\n");
        sb.append("VERSION:2.0\r\n");
        sb.append("PRODID:-//LvLv//Timetable//CN\r\n");
        sb.append("CALSCALE:GREGORIAN\r\n");
        sb.append("X-WR-CALNAME:").append(escape(semesterLabel)).append("\r\n");

        SimpleDateFormat stampFmt = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        stampFmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        String stamp = stampFmt.format(new Date());
        int uidSeed = 0;

        Calendar startCal = WeekMath.parseDate(start);
        if (startCal == null) return null;
        SimpleDateFormat ymdFmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);

        for (int week = 1; week <= totalWeeks; week++) {
            WeekOverlay ov = WeekOverlay.build(t, start, week);
            for (Timetable.Course c : t.courses) {
                for (Timetable.Session s : c.sessions) {
                    if (s.dayOfWeek < 1 || s.dayOfWeek > 7 || !s.isActive(week)) continue;
                    if (ov.cancelled.containsKey(s)) continue;
                    uidSeed = appendEvent(sb, t, startCal, ymdFmt, sched, stamp, week, c, s, uidSeed);
                }
            }
            for (WeekOverlay.Extra ex : ov.extras) {
                if (ex.session.dayOfWeek < 1 || ex.session.dayOfWeek > 7) continue;
                uidSeed = appendEvent(sb, t, startCal, ymdFmt, sched, stamp, week,
                        ex.course, ex.session, uidSeed);
            }
        }
        sb.append("END:VCALENDAR\r\n");
        return sb.toString();
    }

    private static int appendEvent(StringBuilder sb, Timetable t, Calendar startCal,
                                   SimpleDateFormat ymdFmt, ScheduleSettings sched, String stamp,
                                   int week, Timetable.Course c, Timetable.Session s, int uidSeed) {
        Calendar day = (Calendar) startCal.clone();
        day.add(Calendar.DAY_OF_YEAR, (week - 1) * 7 + (s.dayOfWeek - 1));
        String ymd = ymdFmt.format(day.getTime());

        int secStart = Math.max(1, Math.min(ScheduleSettings.MAX_SECTIONS, s.startSection));
        int secEnd = Math.max(secStart, Math.min(ScheduleSettings.MAX_SECTIONS, s.endSection));
        int startMinutes = s.hasCustomTime()
                ? Timetable.Session.minutesOf(s.timeStart) : sched.startMinutes(secStart);
        int endMinutes = s.hasCustomTime()
                ? Timetable.Session.minutesOf(s.timeEnd) : sched.endMinutes(secEnd);

        String dtStart = ymd.replace("-", "") + "T" + hm(startMinutes) + "00";
        String dtEnd = ymd.replace("-", "") + "T" + hm(endMinutes) + "00";

        sb.append("BEGIN:VEVENT\r\n");
        sb.append("UID:lvlv-").append(System.identityHashCode(t))
                .append('-').append(uidSeed++).append("@ecut.campus\r\n");
        sb.append("DTSTAMP:").append(stamp).append("\r\n");
        sb.append("DTSTART;TZID=Asia/Shanghai:").append(dtStart).append("\r\n");
        sb.append("DTEND;TZID=Asia/Shanghai:").append(dtEnd).append("\r\n");
        sb.append("SUMMARY:").append(escape(c.name)).append("\r\n");
        String loc = s.location == null ? "" : s.location.trim();
        if (!loc.isEmpty()) sb.append("LOCATION:").append(escape(loc)).append("\r\n");
        String teacher = s.teacherFor(week, c.teacher).trim();
        if (!teacher.isEmpty()) {
            sb.append("DESCRIPTION:").append(escape(teacher)).append("\r\n");
        }
        sb.append("END:VEVENT\r\n");
        return uidSeed;
    }

    private static String hm(int minutes) {
        int m = Math.max(0, Math.min(24 * 60 - 1, minutes));
        return String.format(Locale.US, "%02d%02d", m / 60, m % 60);
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
        StringBuilder sb = new StringBuilder();
        sb.append("BEGIN:VCALENDAR\r\n");
        sb.append("VERSION:2.0\r\n");
        sb.append("PRODID:-//LvLv//Exams//CN\r\n");
        sb.append("CALSCALE:GREGORIAN\r\n");
        sb.append("X-WR-CALNAME:").append(escape(calendarName == null || calendarName.isEmpty()
                ? "考试安排 · 驴驴课表" : calendarName)).append("\r\n");

        SimpleDateFormat stampFmt = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        stampFmt.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        String stamp = stampFmt.format(new Date());
        int count = 0;
        for (ExamCountdown.Entry e : exams) {
            if (!e.hasDate()) continue;
            int st = e.startMin >= 0 ? e.startMin : 9 * 60;
            int en = e.endMin > st ? e.endMin : Math.min(23 * 60 + 55, st + 120);
            String d = e.date.replace("-", "");
            sb.append("BEGIN:VEVENT\r\n");
            sb.append("UID:lvlv-exam-").append(d).append('-').append(count++).append("@ecut.campus\r\n");
            sb.append("DTSTAMP:").append(stamp).append("\r\n");
            sb.append("DTSTART;TZID=Asia/Shanghai:").append(d).append('T').append(hm(st)).append("00\r\n");
            sb.append("DTEND;TZID=Asia/Shanghai:").append(d).append('T').append(hm(en)).append("00\r\n");
            sb.append("SUMMARY:").append(escape("[考试] " + e.name)).append("\r\n");
            if (!e.room.isEmpty()) sb.append("LOCATION:").append(escape(e.room)).append("\r\n");
            StringBuilder desc = new StringBuilder();
            if (!e.seat.isEmpty()) desc.append("座位：").append(e.seat);
            if (!e.code.isEmpty()) {
                if (desc.length() > 0) desc.append(" · ");
                desc.append("课程号：").append(e.code);
            }
            if (desc.length() > 0) sb.append("DESCRIPTION:").append(escape(desc.toString())).append("\r\n");
            // 考前一天提醒
            sb.append("BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-P1D\r\nDESCRIPTION:")
                    .append(escape("明天考试：" + e.name)).append("\r\nEND:VALARM\r\n");
            // 考前一小时提醒
            sb.append("BEGIN:VALARM\r\nACTION:DISPLAY\r\nTRIGGER:-PT1H\r\nDESCRIPTION:")
                    .append(escape("1小时后考试：" + e.name)).append("\r\nEND:VALARM\r\n");
            sb.append("END:VEVENT\r\n");
        }
        sb.append("END:VCALENDAR\r\n");
        return count > 0 ? sb.toString() : null;
    }
}
