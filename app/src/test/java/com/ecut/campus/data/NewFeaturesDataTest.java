package com.ecut.campus.data;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Arrays;

public class NewFeaturesDataTest {

    private static Timetable sample() {
        Timetable t = new Timetable();
        t.semester.id = "2026-2027-1";
        t.semester.startDate = "2026-09-07";
        Timetable.Course c = new Timetable.Course();
        c.name = "构造地质学";
        Timetable.Session s = new Timetable.Session();
        s.dayOfWeek = 2; s.startSection = 3; s.endSection = 4;
        s.weeks = TimetableShare.list(1, 2, 3, 4, 5, 6, 7, 8);
        s.location = "地学楼 A201";
        c.sessions.add(s);
        t.courses.add(c);
        return t;
    }

    @Test
    public void shareCodeRoundTrips() throws Exception {
        String code = TimetableShare.encode(sample(), "小董");
        assertTrue(code.startsWith(TimetableShare.PREFIX));
        TimetableShare.Shared sh = TimetableShare.decode("快导入我的课表 " + code + " ✓");
        assertNotNull(sh);
        assertEquals("小董", sh.owner);
        assertEquals("2026-09-07", sh.startDate);
        Timetable.Session s = sh.timetable.courses.get(0).sessions.get(0);
        assertEquals("构造地质学", sh.timetable.courses.get(0).name);
        assertEquals(2, s.dayOfWeek);
        assertEquals(4, s.endSection);
        assertEquals(8, s.weeks.size());
        assertEquals("地学楼 A201", s.location);
    }

    @Test
    public void garbageShareCodeIsNull() {
        assertNull(TimetableShare.decode("hello"));
        assertNull(TimetableShare.decode(TimetableShare.PREFIX + "@@@"));
    }

    @Test
    public void base64RoundTrip() {
        for (int n = 0; n < 40; n++) {
            byte[] b = new byte[n];
            for (int i = 0; i < n; i++) b[i] = (byte) (i * 37 + 11);
            assertArrayEquals(b, TimetableShare.B64.decode(TimetableShare.B64.encode(b)));
        }
    }

    @Test
    public void commonFreeExcludesAnyonesClass() {
        Timetable a = sample();
        Timetable b = new Timetable();
        boolean[][] free = FreeTime.commonFree(Arrays.asList(a, b), 3, 10);
        assertFalse(free[1][2]);
        assertFalse(free[1][3]);
        assertTrue(free[1][4]);
        boolean[][] week9 = FreeTime.commonFree(Arrays.asList(a, b), 9, 10);
        assertTrue(week9[1][2]);
    }

    @Test
    public void gradeDiffFindsOnlyNew() {
        Grades local = new Grades();
        Grades.SemesterGrades s = new Grades.SemesterGrades();
        s.id = "2025-2026-2";
        Grades.CourseGrade a = new Grades.CourseGrade(); a.code = "A1"; a.name = "高数";
        s.courses.add(a);
        local.semesters.add(s);
        Grades remote = new Grades();
        Grades.SemesterGrades s2 = new Grades.SemesterGrades();
        s2.id = "2025-2026-2";
        Grades.CourseGrade a2 = new Grades.CourseGrade(); a2.code = "A1"; a2.name = "高数";
        Grades.CourseGrade b2 = new Grades.CourseGrade(); b2.code = "B2"; b2.name = "矿床学";
        s2.courses.add(a2); s2.courses.add(b2);
        remote.semesters.add(s2);
        assertEquals(1, GradeDiff.newGrades(local, remote).size());
        assertEquals("矿床学", GradeDiff.newGrades(local, remote).get(0).name);
        assertTrue(GradeDiff.newGrades(new Grades(), remote).isEmpty());
    }
}
