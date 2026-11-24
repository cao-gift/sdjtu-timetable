package com.ecut.campus;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** 学期首周周一归一与周日期计算：标题、日期表头、当前周共用同一个起点。 */
public class EffectiveStartTest {

    @Test
    public void normalizeToMondayAlignsMidweekDates() {
        assertEquals("2026-08-24", WeekMath.normalizeToMonday("2026-08-26"));
        assertEquals("2026-08-24", WeekMath.normalizeToMonday("2026-08-30"));
        assertEquals("2026-09-07", WeekMath.normalizeToMonday("2026-09-09"));
    }

    @Test
    public void emptyOrInvalidStartStaysEmpty() {
        assertEquals("", WeekMath.normalizeToMonday(""));
        assertEquals("", WeekMath.normalizeToMonday(null));
        assertEquals("", WeekMath.normalizeToMonday("not-a-date"));
    }

    @Test
    public void week14DatesMatchSdjtuFall2026Calibration() {
        String start = WeekMath.startDateForWeek("2026-11-23", 14);
        assertEquals("2026-08-24", start);
        String[] d = WeekMath.weekDates(start, 14);
        assertEquals("11/23", d[0]);
        assertEquals("11/29", d[6]);
        assertEquals(14, WeekMath.weekOfDate(start, "2026-11-24"));
    }
}
