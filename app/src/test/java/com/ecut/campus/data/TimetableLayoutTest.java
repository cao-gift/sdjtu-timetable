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

    @Test
    public void blockPlanUsesRemainingHeightForLongCourseNamesBeforeEllipsizing() {
        float[] fiveLineTitle = {14f, 28f, 42f, 56f, 70f};
        // 高度充足（120px）：5 行课名 + 教室 + 教师全部完整保留
        int[] roomy = TimetableLayout.blockPlan(120f, fiveLineTitle, 3f, 20f, 0f, 20f, 0f);
        assertEquals(5, roomy[0]);
        assertEquals(1 | 4, roomy[1]);
        // 高度不足（68px）：优先保教室，课名按剩余空间截断到 3 行
        int[] tight = TimetableLayout.blockPlan(68f, fiveLineTitle, 3f, 20f, 0f, 20f, 0f);
        assertEquals(3, tight[0]);
        assertEquals(1, tight[1]);
    }

    @Test
    public void mergeConsecutiveActiveCombinesSameCourseSameRoomAndSkipsLunchOrDifferentRoom() {
        ScheduleSettings sched = ScheduleSettings.createDefault();
        Timetable.Course plan = new Timetable.Course();
        plan.name = "交通规划";
        plan.teacher = "老师A";
        plan.colorIndex = 2;
        Timetable.Session p1 = session(1, 2);
        p1.location = "无影山1号教学楼1102";
        Timetable.Session p2 = session(3, 4);
        p2.location = "无影山1号教学楼1102";
        Timetable.Session p3 = session(5, 6);
        p3.location = "无影山1号教学楼1102"; // 跨午休 105 分钟，不合并
        Timetable.Session p4 = session(7, 8);
        p4.location = "无影山1号教学楼1202"; // 不同教室，不合并

        java.util.List<TimetableLayout.DisplayBlock> merged = TimetableLayout.mergeConsecutiveActive(
                Arrays.asList(
                        new TimetableLayout.DisplayBlock(plan, p1, p1),
                        new TimetableLayout.DisplayBlock(plan, p2, p2),
                        new TimetableLayout.DisplayBlock(plan, p3, p3),
                        new TimetableLayout.DisplayBlock(plan, p4, p4)),
                14, sched);

        assertEquals(3, merged.size());
        assertEquals(1, merged.get(0).displaySession.startSection);
        assertEquals(4, merged.get(0).displaySession.endSection);
        assertEquals(p1, merged.get(0).primarySession);
        // 原始 Session 不被修改
        assertEquals(2, p1.endSection);
        assertEquals(5, merged.get(1).displaySession.startSection);
        assertEquals(6, merged.get(1).displaySession.endSection);
        assertEquals(7, merged.get(2).displaySession.startSection);
        assertEquals(8, merged.get(2).displaySession.endSection);
    }

    @Test
    public void planOverlapDeckKeepsPartialOverlapsFullWidthInUncoveredZones() {
        ScheduleSettings sched = ScheduleSettings.createDefault();
        Timetable.Session longCourse = session(1, 4);  // 交通规划 1-4节
        Timetable.Session shortLate = session(3, 4);   // 运筹学 3-4节
        Timetable.Session standalone = session(5, 6);

        Map<Timetable.Session, TimetableLayout.OverlapCard> deck = new IdentityHashMap<>();
        java.util.List<Timetable.Session> order = TimetableLayout.planOverlapDeck(
                Arrays.asList(longCourse, shortLate, standalone), sched, deck);

        assertEquals(3, order.size());
        TimetableLayout.OverlapCard cLong = deck.get(longCourse);
        TimetableLayout.OverlapCard cShort = deck.get(shortLate);
        TimetableLayout.OverlapCard cSolo = deck.get(standalone);

        // 长课垫底（保留 1-4 节全高），文字区自动落到未被遮挡的 1-2 节
        assertEquals(0, cLong.depth);
        assertEquals(2, cLong.clusterSize);
        assertEquals(0f, cLong.visualStart, 0.01f);
        assertEquals(4f, cLong.visualEnd, 0.01f);
        assertEquals(0f, cLong.textStart, 0.01f);
        assertEquals(2f, cLong.textEnd, 0.01f);

        // 重修冲突短课错层叠放于 3-4 节
        assertEquals(1, cShort.depth);
        assertEquals(2, cShort.clusterSize);
        assertEquals(cLong.clusterId, cShort.clusterId);
        assertEquals(2f, cShort.visualStart, 0.01f);
        assertEquals(4f, cShort.visualEnd, 0.01f);
        assertEquals(2f, cShort.textStart, 0.01f);
        assertEquals(4f, cShort.textEnd, 0.01f);

        // 无冲突课保持独立
        assertEquals(0, cSolo.depth);
        assertEquals(1, cSolo.clusterSize);
    }

    @Test
    public void planOverlapDeckPartitionsIdenticalSlotConflictsIntoSteppedBands() {
        ScheduleSettings sched = ScheduleSettings.createDefault();
        Timetable.Session a = session(7, 8);
        Timetable.Session b = session(7, 8);

        Map<Timetable.Session, TimetableLayout.OverlapCard> deck = new IdentityHashMap<>();
        TimetableLayout.planOverlapDeck(Arrays.asList(a, b), sched, deck);

        TimetableLayout.OverlapCard ca = deck.get(a);
        TimetableLayout.OverlapCard cb = deck.get(b);
        assertEquals(0, ca.depth);
        assertEquals(1, cb.depth);
        assertEquals(2, ca.clusterSize);
        assertEquals(ca.clusterId, cb.clusterId);
        // 底层延伸至簇底 6..8，文字占上半 6..7；上层叠卡占下半 7..8
        assertEquals(6f, ca.visualStart, 0.01f);
        assertEquals(8f, ca.visualEnd, 0.01f);
        assertEquals(6f, ca.textStart, 0.01f);
        assertEquals(7f, ca.textEnd, 0.01f);
        assertEquals(7f, cb.visualStart, 0.01f);
        assertEquals(8f, cb.visualEnd, 0.01f);
        assertEquals(7f, cb.textStart, 0.01f);
        assertEquals(8f, cb.textEnd, 0.01f);
    }

    @Test
    public void planOverlapDeckHandlesEarlyPartialOverlapByMovingLongCourseTextBelow() {
        ScheduleSettings sched = ScheduleSettings.createDefault();
        Timetable.Session longCourse = session(1, 4);
        Timetable.Session shortEarly = session(1, 2);

        Map<Timetable.Session, TimetableLayout.OverlapCard> deck = new IdentityHashMap<>();
        TimetableLayout.planOverlapDeck(Arrays.asList(longCourse, shortEarly), sched, deck);

        TimetableLayout.OverlapCard cLong = deck.get(longCourse);
        TimetableLayout.OverlapCard cShort = deck.get(shortEarly);
        assertEquals(0, cLong.depth);
        assertEquals(1, cShort.depth);
        assertEquals(2f, cLong.textStart, 0.01f);
        assertEquals(4f, cLong.textEnd, 0.01f);
        assertEquals(0f, cShort.textStart, 0.01f);
        assertEquals(2f, cShort.textEnd, 0.01f);
    }

    private static Timetable.Session session(int start, int end) {
        Timetable.Session s = new Timetable.Session();
        s.dayOfWeek = 1;
        s.startSection = start;
        s.endSection = end;
        return s;
    }
}
