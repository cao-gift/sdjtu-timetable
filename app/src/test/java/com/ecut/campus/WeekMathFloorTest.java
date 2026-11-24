package com.ecut.campus;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class WeekMathFloorTest {
    @Test public void firstDayIsWeekOne() {
        assertEquals(1, WeekMath.weekOfDate("2026-09-07", "2026-09-07"));
        assertEquals(1, WeekMath.weekOfDate("2026-09-07", "2026-09-13"));
        assertEquals(2, WeekMath.weekOfDate("2026-09-07", "2026-09-14"));
    }

    @Test public void calibrationUsesTheMondayOfTheReportedWeek() {
        assertEquals("2026-08-24", WeekMath.startDateForWeek("2026-11-23", 14));
        assertEquals("2026-08-24", WeekMath.startDateForWeek("2026-11-29", 14));
        assertEquals(14, WeekMath.weekOfDate("2026-08-24", "2026-11-29"));
        assertEquals(15, WeekMath.weekOfDate("2026-08-24", "2026-11-30"));
        assertEquals("2025-12-29", WeekMath.startDateForWeek("2026-01-04", 1));
    }

    @Test public void calibrationRejectsInvalidDateOrWeek() {
        assertEquals("", WeekMath.startDateForWeek("2026-02-30", 14));
        assertEquals("", WeekMath.startDateForWeek("2026-11-23", 0));
        assertEquals("", WeekMath.startDateForWeek("2026-11-23", 100));
    }

    @Test public void beforeSemesterIsNotWeekOne() {
        assertEquals(0, WeekMath.weekOfDate("2026-09-07", "2026-09-06"));
        assertEquals(0, WeekMath.weekOfDate("2026-09-07", "2026-08-31"));
        assertEquals(-1, WeekMath.weekOfDate("2026-09-07", "2026-08-30"));
    }
}
