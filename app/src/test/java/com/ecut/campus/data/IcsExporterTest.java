package com.ecut.campus.data;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.ecut.campus.ScheduleSettings;

import org.junit.Test;

public class IcsExporterTest {

    private static Timetable timetableWithOneCourse() {
        Timetable t = new Timetable();
        t.semester.id = "2026-2027-1";
        t.semester.name = "2026-2027 学年第一学期";
        t.semester.startDate = "2026-09-07"; // 周一
        t.semester.totalWeeks = 20;
        Timetable.Course c = new Timetable.Course();
        c.name = "矿床学";
        c.teacher = "教师A";
        Timetable.Session s = new Timetable.Session();
        s.dayOfWeek = 2; // 周二
        s.startSection = 1;
        s.endSection = 2;
        s.weeksRaw = "1-16周";
        s.weeks.addAll(com.ecut.campus.data.WeekRange.parse("1-16周").weeks);
        s.location = "4-620";
        c.sessions.add(s);
        t.courses.add(c);
        return t;
    }

    @Test
    public void generatesValidIcsWithWeeklyEvents() {
        String ics = IcsExporter.export(timetableWithOneCourse(),
                ScheduleSettings.createDefault(), "测试学期");
        assertNotNull(ics);
        assertTrue(ics.startsWith("BEGIN:VCALENDAR"));
        assertTrue(ics.trim().endsWith("END:VCALENDAR"));
        // 1-16 周 → 16 个事件
        int events = ics.split("BEGIN:VEVENT", -1).length - 1;
        assertTrue(events == 16);
        assertTrue(ics.contains("METHOD:PUBLISH"));
        assertTrue(ics.contains("X-WR-TIMEZONE:Asia/Shanghai"));
        // 第 1 周周二 = 2026-09-08，东八区 08:30–10:00 → UTC 00:30Z–02:00Z
        assertTrue(ics.contains("DTSTART:20260908T003000Z"));
        assertTrue(ics.contains("DTEND:20260908T020000Z"));
        assertTrue(ics.contains("SUMMARY:矿床学"));
        assertTrue(ics.contains("LOCATION:4-620"));
        assertTrue(ics.contains("DESCRIPTION:第 1 周 周二 · 教师A"));
        // 东八区 07:30 跨前一日 UTC 23:30Z
        assertTrue("20260831T233000Z".equals(IcsExporter.toUtcStamp("2026-09-01", 7 * 60 + 30)));
    }

    @Test
    public void sortsEventsChronologicallyAndUsesDeterministicUid() {
        Timetable t = timetableWithOneCourse();
        t.semester.totalWeeks = 2;
        t.courses.get(0).sessions.get(0).weeks.clear();
        t.courses.get(0).sessions.get(0).weeks.add(1);
        // 加一门排在后面的周一课，应排在周二课之前
        Timetable.Course mon = new Timetable.Course();
        mon.name = "高等数学";
        Timetable.Session ms = new Timetable.Session();
        ms.dayOfWeek = 1;
        ms.startSection = 1;
        ms.endSection = 2;
        ms.weeks.add(1);
        ms.location = "1-101";
        mon.sessions.add(ms);
        t.courses.add(mon);
        // 重复的同一时段记录应自动去重
        t.courses.add(mon);

        String ics1 = IcsExporter.export(t, ScheduleSettings.createDefault(), "排序测试");
        String ics2 = IcsExporter.export(t, ScheduleSettings.createDefault(), "排序测试");
        assertNotNull(ics1);
        assertTrue(ics1.split("BEGIN:VEVENT", -1).length - 1 == 2);
        assertTrue(ics1.indexOf("20260907T003000Z") < ics1.indexOf("20260908T003000Z"));
        // 两次导出的 UID 保持一致，避免重复导入产生冲突
        String uid1 = ics1.substring(ics1.indexOf("UID:"), ics1.indexOf("\r\n", ics1.indexOf("UID:")));
        String uid2 = ics2.substring(ics2.indexOf("UID:"), ics2.indexOf("\r\n", ics2.indexOf("UID:")));
        assertTrue(uid1.equals(uid2));
    }

    @Test
    public void skipsWeeksOutsideRange() {
        Timetable t = timetableWithOneCourse();
        t.courses.get(0).sessions.get(0).weeks.clear();
        t.courses.get(0).sessions.get(0).weeksRaw = "5-8周";
        t.courses.get(0).sessions.get(0).weeks.addAll(
                WeekRange.parse("5-8周").weeks);
        String ics = IcsExporter.export(t, ScheduleSettings.createDefault(), "x");
        assertNotNull(ics);
        assertTrue(ics.split("BEGIN:VEVENT", -1).length - 1 == 4);
    }

    @Test
    public void nullWhenNoStartDate() {
        Timetable t = timetableWithOneCourse();
        t.semester.startDate = "";
        assertNull(IcsExporter.export(t, ScheduleSettings.createDefault(), "x"));
    }

    @Test
    public void appliesCancelAndMoveOverlay() {
        Timetable t = timetableWithOneCourse();
        t.semester.startDate = "2026-03-02"; // 周一，春季无内置调休
        t.courses.get(0).sessions.get(0).weeks.clear();
        t.courses.get(0).sessions.get(0).weeksRaw = "1-4周";
        t.courses.get(0).sessions.get(0).weeks.addAll(WeekRange.parse("1-4周").weeks);

        // 第 2 周周二（2026-03-10）停课
        Timetable.Adjust cancel = new Timetable.Adjust();
        cancel.id = "c1";
        cancel.type = Timetable.Adjust.CANCEL;
        cancel.name = "矿床学";
        cancel.week = 2;
        cancel.day = 2;
        cancel.start = 1;
        cancel.end = 2;
        t.adjusts.add(cancel);

        // 第 3 周周二（2026-03-17）调到第 4 周周六（2026-03-28）19:00-20:30 @ 3-201
        Timetable.Adjust move = new Timetable.Adjust();
        move.id = "m1";
        move.type = Timetable.Adjust.MOVE;
        move.name = "矿床学";
        move.week = 3;
        move.day = 2;
        move.start = 1;
        move.end = 2;
        move.toWeek = 4;
        move.toDay = 6;
        move.toTimeStart = "19:00";
        move.toTimeEnd = "20:30";
        move.toLocation = "3-201";
        t.adjusts.add(move);

        String ics = IcsExporter.export(t, ScheduleSettings.createDefault(), "测试");
        assertNotNull(ics);
        // 原 4 节 - 1 停课 - 1 调走 + 1 调来 = 3 个事件
        assertTrue(ics.split("BEGIN:VEVENT", -1).length - 1 == 3);
        assertTrue(!ics.contains("20260310T003000Z"));
        assertTrue(!ics.contains("20260317T003000Z"));
        // 2026-03-28 东八区 19:00–20:30 → UTC 11:00Z–12:30Z
        assertTrue(ics.contains("DTSTART:20260328T110000Z"));
        assertTrue(ics.contains("DTEND:20260328T123000Z"));
        assertTrue(ics.contains("LOCATION:3-201"));
    }
}
