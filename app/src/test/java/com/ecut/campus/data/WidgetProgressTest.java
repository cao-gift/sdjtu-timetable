package com.ecut.campus.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class WidgetProgressTest {

    private static DayPlan.Item item(String name, int startMin, int endMin, String loc) {
        DayPlan.Item it = new DayPlan.Item();
        it.name = name;
        it.startMin = startMin;
        it.endMin = endMin;
        it.location = loc;
        return it;
    }

    @Test
    public void liveClassCalculatesInClassAndDayPercent() {
        DayPlan.Item a = item("高等数学", 510, 600, "教A-101"); // 08:30-10:00
        DayPlan.Item b = item("大学英语", 615, 705, "教B-202"); // 10:15-11:45
        WidgetProgress.Snapshot snap = WidgetProgress.build(Arrays.asList(a, b), Collections.emptyList(), 555, "");
        assertEquals(WidgetProgress.MODE_LIVE, snap.mode);
        assertEquals("高等数学", snap.title);
        assertEquals(50, snap.livePercent);
        assertEquals(25, snap.dayPercent);
        assertEquals(600, snap.countdownMin);
        assertEquals("1/2", snap.ratioLabel);
    }

    @Test
    public void upcomingClassShowsCountdownWhenWithinThreeHours() {
        DayPlan.Item a = item("高等数学", 510, 600, "教A-101");
        DayPlan.Item b = item("大学物理", 615, 705, "教C-301");
        WidgetProgress.Snapshot snap = WidgetProgress.build(Arrays.asList(a, b), Collections.emptyList(), 605, "");
        assertEquals(WidgetProgress.MODE_NEXT, snap.mode);
        assertEquals(615, snap.countdownMin);
        assertEquals(50, snap.dayPercent);
        assertEquals("1/2", snap.ratioLabel);
    }

    @Test
    public void doneDayFallsBackToExamBannerWhenPresent() {
        DayPlan.Item a = item("高等数学", 510, 600, "教A-101");
        WidgetProgress.Snapshot snap = WidgetProgress.build(Collections.singletonList(a),
                Collections.emptyList(), 720, "高等数学考试 · 剩 2 天");
        assertEquals(WidgetProgress.MODE_DONE, snap.mode);
        assertEquals(100, snap.dayPercent);
        assertTrue(snap.title.contains("高等数学考试"));
    }
}
