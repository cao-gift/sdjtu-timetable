package com.ecut.campus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import com.ecut.campus.data.Timetable;

import java.util.Arrays;

public class ScheduleSettingsTest {

    @Test
    public void defaultsMatchConfirmedTimetable() {
        ScheduleSettings s = ScheduleSettings.createDefault();

        assertEquals("08:30", s.startTime(1));
        assertEquals("09:15", s.endTime(1));
        assertEquals("09:20", s.startTime(2));
        assertEquals("10:00", s.endTime(2));
        assertEquals("10:15", s.startTime(3));
        assertEquals("11:00", s.endTime(3));
        assertEquals("11:05", s.startTime(4));
        assertEquals("11:45", s.endTime(4));
        assertEquals("13:30", s.startTime(5));
        assertEquals("14:15", s.endTime(5));
        assertEquals("14:20", s.startTime(6));
        assertEquals("15:00", s.endTime(6));
        assertEquals("15:15", s.startTime(7));
        assertEquals("16:00", s.endTime(7));
        assertEquals("16:05", s.startTime(8));
        assertEquals("16:45", s.endTime(8));
        assertEquals("19:00", s.startTime(9));
        assertEquals("19:45", s.endTime(9));
        assertEquals("19:50", s.startTime(10));
        assertEquals("20:35", s.endTime(10));
        assertEquals("20:45", s.startTime(11));
        assertEquals("21:30", s.endTime(11));
        assertEquals("21:40", s.startTime(12));
        assertEquals("22:25", s.endTime(12));

        int[] expectedDurations = {45, 40, 45, 40, 45, 40, 45, 40, 45, 45};
        for (int section = 1; section <= s.sectionCount(); section++) {
            assertEquals("第 " + section + " 节时长不符",
                    expectedDurations[section - 1], s.section(section).durationMinutes);
        }
    }

    @Test
    public void durationOverrideShiftsFollowingSections() {
        ScheduleSettings s = ScheduleSettings.createDefault();
        s.section(3).durationMinutes = 40;

        assertEquals("10:15", s.startTime(3));
        assertEquals("10:55", s.endTime(3));
        assertEquals("11:00", s.startTime(4));
        assertEquals("13:25", s.startTime(5));
    }

    @Test
    public void lunchBreakOverrideKeepsAfternoonStart() {
        ScheduleSettings s = ScheduleSettings.createDefault();
        s.section(4).breakAfterMinutes = 120;

        assertEquals("13:45", s.startTime(5));
        assertEquals("11:45", s.endTime(4));
    }

    @Test
    public void resetRestoresEveryDefault() {
        ScheduleSettings s = ScheduleSettings.createDefault();
        s.firstClassStart = "09:30";
        s.section(2).durationMinutes = 80;
        s.section(4).breakAfterMinutes = 5;

        s.resetToDefaults();

        assertEquals("08:30", s.firstClassStart);
        assertEquals(40, s.section(2).durationMinutes);
        assertEquals(105, s.section(4).breakAfterMinutes);
        assertEquals("20:35", s.endTime(10));
    }

    @Test
    public void jsonRoundTripKeepsOverrides() throws Exception {
        ScheduleSettings s = ScheduleSettings.createDefault();
        s.firstClassStart = "08:10";
        s.section(3).durationMinutes = 40;
        s.section(4).breakAfterMinutes = 100;

        ScheduleSettings restored = ScheduleSettings.fromJson(new JSONObject(s.toJson().toString()));

        assertEquals("08:10", restored.firstClassStart);
        assertEquals(40, restored.section(3).durationMinutes);
        assertEquals(100, restored.section(4).breakAfterMinutes);
        assertEquals(s.startTime(5), restored.startTime(5));
    }

    @Test
    public void malformedJsonFallsBackToDefaults() throws Exception {
        ScheduleSettings s = ScheduleSettings.fromJson(new JSONObject());
        assertEquals("08:30", s.startTime(1));
        assertEquals(5, s.section(1).breakAfterMinutes);
    }

    @Test
    public void detectsLegacyDefaultWithoutTouchingCustomSchedule() throws Exception {
        ScheduleSettings legacy = ScheduleSettings.fromJson("{"
                + "\"schemaVersion\":1,\"firstClassStart\":\"08:00\",\"sectionCount\":10,"
                + "\"sections\":["
                + "{\"section\":1,\"durationMinutes\":45,\"breakAfterMinutes\":10},"
                + "{\"section\":2,\"durationMinutes\":45,\"breakAfterMinutes\":20},"
                + "{\"section\":3,\"durationMinutes\":45,\"breakAfterMinutes\":10},"
                + "{\"section\":4,\"durationMinutes\":45,\"breakAfterMinutes\":140},"
                + "{\"section\":5,\"durationMinutes\":45,\"breakAfterMinutes\":10},"
                + "{\"section\":6,\"durationMinutes\":45,\"breakAfterMinutes\":20},"
                + "{\"section\":7,\"durationMinutes\":45,\"breakAfterMinutes\":10},"
                + "{\"section\":8,\"durationMinutes\":45,\"breakAfterMinutes\":80},"
                + "{\"section\":9,\"durationMinutes\":45,\"breakAfterMinutes\":10},"
                + "{\"section\":10,\"durationMinutes\":45,\"breakAfterMinutes\":20}"
                + "]}");
        assertTrue(legacy.isLegacyDefault());
        assertFalse(ScheduleSettings.createDefault().isLegacyDefault());
        legacy.section(4).breakAfterMinutes = 120;
        assertFalse(legacy.isLegacyDefault());
    }

    @Test
    public void invalidTimeIsRejected() {
        assertEquals(-1, ScheduleSettings.parseHm("8点"));
        assertEquals(-1, ScheduleSettings.parseHm("25:00"));
        assertEquals(480, ScheduleSettings.parseHm("08:00"));
    }

    @Test
    public void currentWeekUsesSemesterStartDate() {
        String start = WeekMath.todayDateLabel();
        assertEquals(1, WeekMath.currentWeek(start, 20));
        assertTrue(WeekMath.weekRangeLabel(start, 1).contains("/"));
        assertFalse(WeekMath.weekDates("", 1)[0].isEmpty());
    }

    @Test
    public void anyWeekdayNormalizesToMonday() {
        assertEquals("2026-10-19", WeekMath.normalizeToMonday("2026-10-23"));
        assertEquals("2026-10-19", WeekMath.normalizeToMonday("2026-10-19"));
        assertEquals("2026-10-19", WeekMath.normalizeToMonday("2026-10-25"));
        assertEquals("", WeekMath.normalizeToMonday("not-a-date"));
        assertTrue(WeekMath.isMonday("2026-10-19"));
        assertFalse(WeekMath.isMonday("2026-10-23"));
    }

    @Test
    public void weekDatesAlwaysStartOnMonday() {
        assertEquals("10/19", WeekMath.weekDates("2026-10-23", 1)[0]);
        assertEquals("10/25", WeekMath.weekDates("2026-10-23", 1)[6]);
        assertEquals("10/26", WeekMath.weekDates("2026-10-23", 2)[0]);
    }

    @Test
    public void firstCourseDateComesFromEarliestWeekAndDay() {
        Timetable timetable = new Timetable();
        timetable.courses.add(courseWithSession(5, 1));
        timetable.courses.add(courseWithSession(6, 3));

        assertEquals("2026-10-05", WeekMath.firstCourseDate("2026-09-07", timetable));
        assertEquals("10/5 周一", WeekMath.firstCourseLabel("2026-09-07", timetable));
    }

    @Test
    public void firstCourseDateHandlesEmptyTimetable() {
        assertEquals("", WeekMath.firstCourseDate("2026-09-07", new Timetable()));
        assertEquals("暂无可计算的首节课",
                WeekMath.firstCourseLabel("2026-09-07", new Timetable()));
    }

    private static Timetable.Course courseWithSession(int week, int day) {
        Timetable.Course course = new Timetable.Course();
        Timetable.Session session = new Timetable.Session();
        session.dayOfWeek = day;
        session.weeks.addAll(Arrays.asList(week));
        course.sessions.add(session);
        return course;
    }
}
