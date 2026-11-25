package com.ecut.campus;

import static org.junit.Assert.assertEquals;

import com.ecut.campus.data.Grades;

import org.junit.Test;

public class HistorySyncMathTest {

    private static Grades info() { return new Grades(); }

    @Test
    public void descendingListTakesLastElement() {
        Grades g = info();
        g.semesters.add(sem("2025-2026-1"));
        g.semesters.add(sem("2025-2026-2"));
        g.semesters.add(sem("2024-2025-1"));
        g.semesters.add(sem("2024-2025-2"));
        assertEquals(2024, HistorySync.earliestGradeYear(g));
    }

    @Test
    public void ascendingListTakesLastElementToo() {
        Grades g = info();
        g.semesters.add(sem("2024-2025-1"));
        g.semesters.add(sem("2025-2026-1"));
        assertEquals(2025, HistorySync.earliestGradeYear(g));
    }

    @Test
    public void singleEntryList() {
        Grades g = info();
        g.semesters.add(sem("2026-2027-1"));
        assertEquals(2026, HistorySync.earliestGradeYear(g));
    }

    @Test
    public void emptyOrInvalidReturnsMinusOne() {
        assertEquals(-1, HistorySync.earliestGradeYear(info()));
        assertEquals(-1, HistorySync.earliestGradeYear(null));
        Grades bad = info();
        bad.semesters.add(sem("garbage"));
        assertEquals(-1, HistorySync.earliestGradeYear(bad));
    }

    @Test
    public void firstSemesterStopsAtTermOneInsteadOfPullingTermTwo() {
        assertEquals(java.util.Arrays.asList(
                "2024-2025-1", "2024-2025-2",
                "2025-2026-1", "2025-2026-2",
                "2026-2027-1"),
                HistorySync.plannedTerms(2024, 2026, 11));
        // 次年 1 月仍属第一学期，不应提前拉第二学期
        assertEquals(java.util.Arrays.asList(
                "2025-2026-1", "2025-2026-2",
                "2026-2027-1"),
                HistorySync.plannedTerms(2025, 2027, 1));
        assertEquals(java.util.Collections.singletonList("2026-2027-1"),
                HistorySync.plannedTerms(2026, 2026, 9));
    }

    @Test
    public void secondSemesterIncludesTermTwoAndStops() {
        assertEquals(java.util.Arrays.asList(
                "2025-2026-1", "2025-2026-2",
                "2026-2027-1", "2026-2027-2"),
                HistorySync.plannedTerms(2025, 2027, 3));
        assertEquals(java.util.Arrays.asList("2026-2027-1", "2026-2027-2"),
                HistorySync.plannedTerms(2026, 2027, 2));
        assertEquals(java.util.Arrays.asList("2026-2027-1", "2026-2027-2"),
                HistorySync.plannedTerms(2026, 2027, 6));
    }

    @Test
    public void invalidOrFutureStartYearPlansNothing() {
        assertEquals(java.util.Collections.emptyList(), HistorySync.plannedTerms(-1, 2026, 11));
        assertEquals(java.util.Collections.emptyList(), HistorySync.plannedTerms(2027, 2026, 11));
    }

    @Test
    public void currentTermIdMatchesAcademicCalendar() {
        assertEquals("2026-2027-1", HistorySync.currentTermId(2026, 7));
        assertEquals("2026-2027-1", HistorySync.currentTermId(2026, 11));
        assertEquals("2026-2027-1", HistorySync.currentTermId(2027, 1));
        assertEquals("2026-2027-2", HistorySync.currentTermId(2027, 2));
        assertEquals("2026-2027-2", HistorySync.currentTermId(2027, 6));
    }

    @Test
    public void summaryMessageReflectsSavedAndSkippedTerms() {
        assertEquals("历史学期同步完成", HistorySync.summaryMessage(0, 0));
        assertEquals("历史学期同步完成：已归档 3 个学期", HistorySync.summaryMessage(3, 0));
        assertEquals("历史学期同步完成：跳过 2 个未匹配学期", HistorySync.summaryMessage(0, 2));
        assertEquals("历史学期同步完成：已归档 2 个，跳过 1 个", HistorySync.summaryMessage(2, 1));
    }

    @Test
    public void busiestWeekPicksWeekWithMostSessions() {
        com.ecut.campus.data.Timetable t = new com.ecut.campus.data.Timetable();
        t.courses.add(courseWithWeeks("1-16周", 2));
        t.courses.add(courseWithWeeks("1-16周", 2));
        t.courses.add(courseWithWeeks("3-18周", 1));
        assertEquals(3, com.ecut.campus.data.TimetableStats.busiestWeek(t));
    }

    @Test
    public void busiestWeekTiePrefersEarlier() {
        com.ecut.campus.data.Timetable t = new com.ecut.campus.data.Timetable();
        t.courses.add(courseWithWeeks("5-8周", 1));
        t.courses.add(courseWithWeeks("9-12周", 1));
        assertEquals(5, com.ecut.campus.data.TimetableStats.busiestWeek(t));
    }

    @Test
    public void busiestWeekEmptyTimetableReturnsOne() {
        assertEquals(1, com.ecut.campus.data.TimetableStats.busiestWeek(new com.ecut.campus.data.Timetable()));
        assertEquals(1, com.ecut.campus.data.TimetableStats.busiestWeek(null));
    }

    private static com.ecut.campus.data.Timetable.Course courseWithWeeks(
            String weeksRaw, int sessions) {
        com.ecut.campus.data.Timetable.Course c = new com.ecut.campus.data.Timetable.Course();
        for (int i = 0; i < sessions; i++) {
            com.ecut.campus.data.Timetable.Session s = new com.ecut.campus.data.Timetable.Session();
            s.dayOfWeek = 1;
            s.startSection = 1;
            s.endSection = 2;
            s.weeksRaw = weeksRaw;
            s.weeks.addAll(com.ecut.campus.data.WeekRange.parse(weeksRaw).weeks);
            c.sessions.add(s);
        }
        return c;
    }

    private static Grades.SemesterGrades sem(String id) {
        Grades.SemesterGrades s = new Grades.SemesterGrades();
        s.id = id;
        return s;
    }
}
