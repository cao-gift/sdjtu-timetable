package com.ecut.campus.data;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.ecut.campus.ScheduleSettings;

import org.junit.Test;

import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.Map;

public class TimetableLayoutTest {

    @Test
    public void assignLanesSplitsOverlappingClusterAndReusesFreeLane() {
        ScheduleSettings sched = ScheduleSettings.createDefault();
        Timetable.Session a = session(1, 2);
        Timetable.Session b = session(1, 4);
        Timetable.Session c = session(3, 4);
        Timetable.Session d = session(5, 6);

        Map<Timetable.Session, int[]> lanes = new IdentityHashMap<>();
        TimetableLayout.assignLanes(Arrays.asList(a, b, c, d), sched, lanes);

        assertArrayEquals(new int[]{0, 2}, lanes.get(a));
        assertArrayEquals(new int[]{1, 2}, lanes.get(b));
        assertArrayEquals(new int[]{0, 2}, lanes.get(c)); // 复用 a 释放的第 0 列
        assertArrayEquals(new int[]{0, 1}, lanes.get(d)); // 独立簇只占 1 列
    }

    @Test
    public void smoothNowPosInterpolatesInsideSectionAndBreak() {
        ScheduleSettings sched = ScheduleSettings.createDefault();
        int s1Start = sched.startMinutes(1);
        int s1End = sched.endMinutes(1);
        int s2Start = sched.startMinutes(2);

        assertEquals(0.5f, TimetableLayout.smoothNowPos((s1Start + s1End) / 2f, sched), 0.01f);
        float inBreak = TimetableLayout.smoothNowPos((s1End + s2Start) / 2f, sched);
        assertTrue("break pos near section 1 boundary", inBreak > 0.94f && inBreak <= 1.0f);
        assertEquals(-1f, TimetableLayout.smoothNowPos(s1Start - 60f, sched), 0.001f);
    }

    @Test
    public void endingSoonMatchesLastFiveMinutesOnly() {
        assertFalse(TimetableLayout.isEndingSoon(500, 510, 555));
        assertFalse(TimetableLayout.isEndingSoon(540, 510, 555));
        assertTrue(TimetableLayout.isEndingSoon(550, 510, 555));
        assertTrue(TimetableLayout.isEndingSoon(554.5f, 510, 555));
        assertFalse(TimetableLayout.isEndingSoon(555, 510, 555));
    }

    @Test
    public void customTimePositionClampsMinimumHeight() {
        ScheduleSettings sched = ScheduleSettings.createDefault();
        Timetable.Session s = new Timetable.Session();
        s.timeStart = "08:30";
        s.timeEnd = "08:35";
        float[] pos = TimetableLayout.position(s, sched);
        assertTrue(pos[1] - pos[0] >= 0.34f);
    }

    @Test
    public void rubberBandResistsOverscroll() {
        assertEquals(10f, TimetableLayout.rubberBand(50f, 200f, 10f, 200f), 0.01f);
        float resisted = TimetableLayout.rubberBand(-40f, 200f, -20f, 200f);
        assertTrue("outward drag damped", Math.abs(resisted) < 10f && resisted < 0f);
    }

    private static Timetable.Session session(int start, int end) {
        Timetable.Session s = new Timetable.Session();
        s.dayOfWeek = 1;
        s.startSection = start;
        s.endSection = end;
        return s;
    }
}
