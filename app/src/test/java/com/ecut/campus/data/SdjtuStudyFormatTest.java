package com.ecut.campus.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.ecut.campus.ScheduleSettings;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class SdjtuStudyFormatTest {

    @Test
    public void splitsMultilineAndPrefixedRoomLabelsCleanly() {
        SdjtuStudyFormat.RoomLabel a = SdjtuStudyFormat.splitRoomLabel("无影山1号教学楼\n      1414", "无影山1号教学楼");
        assertEquals("无影山1号教学楼", a.building);
        assertEquals("1414", a.room);

        SdjtuStudyFormat.RoomLabel b = SdjtuStudyFormat.splitRoomLabel("无影山1号教学楼 · 1102", "");
        assertEquals("无影山1号教学楼", b.building);
        assertEquals("1102", b.room);

        SdjtuStudyFormat.RoomLabel c = SdjtuStudyFormat.splitRoomLabel("长清2号教学楼A201", "");
        assertEquals("长清2号教学楼", c.building);
        assertEquals("A201", c.room);
    }

    @Test
    public void computesPerPeriodRoomStateAndBadges() throws Exception {
        JSONObject room = new JSONObject()
                .put("name", "无影山1号教学楼 1414")
                .put("state", "busy")
                .put("periods", new JSONArray()
                        .put(new JSONObject().put("period", "第1、2节").put("state", "free"))
                        .put(new JSONObject().put("period", "第3、4节").put("state", "busy"))
                        .put(new JSONObject().put("period", "第5、6节").put("state", "free")));

        assertEquals("free", SdjtuStudyFormat.effectiveState(room, ""));
        assertEquals("free", SdjtuStudyFormat.effectiveState(room, "第1、2节"));
        assertEquals("busy", SdjtuStudyFormat.effectiveState(room, "第3、4节"));
        assertEquals(2, SdjtuStudyFormat.freePeriodCount(room));
        assertEquals("空 2/3 节", SdjtuStudyFormat.roomBadge(room, ""));
        assertEquals("3-4节有课", SdjtuStudyFormat.roomBadge(room, "第3、4节"));
        assertEquals("1-2节", SdjtuStudyFormat.shortPeriodLabel("第01、02节"));
    }

    @Test
    public void computesPlanStatsCorrectly() throws Exception {
        JSONArray courses = new JSONArray()
                .put(new JSONObject().put("name", "高等数学（1）").put("term", "2024-2025-1").put("credit", "2.5").put("nature", "必修"))
                .put(new JSONObject().put("name", "线性代数").put("term", "2024-2025-1").put("credit", "2").put("nature", "必修"))
                .put(new JSONObject().put("name", "通识选修").put("term", "2024-2025-2").put("credit", "1.5").put("nature", "选修"));

        SdjtuStudyFormat.PlanStats st = SdjtuStudyFormat.computePlanStats(courses);
        assertEquals(3, st.courseCount);
        assertEquals(6.0, st.totalCredit, 0.001);
        assertEquals(2, st.requiredCount);
        assertEquals(2, st.termCount);
        assertEquals("6", SdjtuStudyFormat.formatCredit(st.totalCredit));
        assertEquals("6.5", SdjtuStudyFormat.formatCredit(6.5));
    }

    @Test
    public void evaluatesContinuousFreeRecommendationFromNowUntilEndOfDay() throws Exception {
        ScheduleSettings sched = ScheduleSettings.createDefault();
        JSONObject roomUntilNight = new JSONObject()
                .put("name", "长清1号教学楼 1205")
                .put("state", "free")
                .put("periods", new JSONArray()
                        .put(new JSONObject().put("period", "第1、2节").put("state", "busy"))
                        .put(new JSONObject().put("period", "第3、4节").put("state", "free"))
                        .put(new JSONObject().put("period", "第5、6节").put("state", "free"))
                        .put(new JSONObject().put("period", "第7、8节").put("state", "free"))
                        .put(new JSONObject().put("period", "第9、10节").put("state", "free")));

        JSONObject roomNextBusy = new JSONObject()
                .put("name", "长清1号教学楼 1102")
                .put("state", "free")
                .put("periods", new JSONArray()
                        .put(new JSONObject().put("period", "第1、2节").put("state", "free"))
                        .put(new JSONObject().put("period", "第3、4节").put("state", "free"))
                        .put(new JSONObject().put("period", "第5、6节").put("state", "busy"))
                        .put(new JSONObject().put("period", "第7、8节").put("state", "free"))
                        .put(new JSONObject().put("period", "第9、10节").put("state", "free")));

        JSONObject roomBusyNow = new JSONObject()
                .put("name", "长清1号教学楼 1308")
                .put("state", "free")
                .put("periods", new JSONArray()
                        .put(new JSONObject().put("period", "第1、2节").put("state", "free"))
                        .put(new JSONObject().put("period", "第3、4节").put("state", "busy"))
                        .put(new JSONObject().put("period", "第5、6节").put("state", "free"))
                        .put(new JSONObject().put("period", "第7、8节").put("state", "free"))
                        .put(new JSONObject().put("period", "第9、10节").put("state", "free")));

        // 10:20（第 3-4 节上课中）
        int nowMin = 10 * 60 + 20;
        SdjtuStudyFormat.StudyRecommendation r1 = SdjtuStudyFormat.evaluateStudy(roomUntilNight, "", true, nowMin, sched);
        assertTrue(r1.fromNowMode);
        assertTrue(r1.recommended);
        assertTrue(r1.untilDayEnd);
        assertEquals(8, r1.streakSections);
        assertEquals("20:35", r1.untilTime);
        assertEquals("连空至 20:35", r1.badgeOverride);
        assertTrue(r1.hintText.contains("连空 8 节") && r1.hintText.contains("直至 20:35 不必换座"));

        SdjtuStudyFormat.StudyRecommendation r2 = SdjtuStudyFormat.evaluateStudy(roomNextBusy, "", true, nowMin, sched);
        assertFalse(r2.recommended);
        assertEquals(2, r2.streakSections);
        assertEquals("11:45", r2.untilTime);
        assertTrue(r2.hintText.contains("13:30 有课"));

        SdjtuStudyFormat.StudyRecommendation r3 = SdjtuStudyFormat.evaluateStudy(roomBusyNow, "", true, nowMin, sched);
        assertFalse(r3.recommended);
        assertEquals(0, r3.streakSections);
        assertEquals("当前有课", r3.badgeOverride);
        assertTrue(r3.hintText.contains("13:30 起连空 6 节"));

        // 21:00（晚间全部节次结束后）回落为全天最长连空提示
        SdjtuStudyFormat.StudyRecommendation late = SdjtuStudyFormat.evaluateStudy(roomUntilNight, "", true, 21 * 60, sched);
        assertFalse(late.fromNowMode);
        assertEquals(8, late.maxAnyStreakSections);
        assertTrue(late.hintText.contains("最长连空 8 节"));

        assertEquals("无影山1号教学楼", SdjtuStudyFormat.defaultBuildingHintForCampus("无影山校区"));
        assertEquals("1号教学楼", SdjtuStudyFormat.defaultBuildingHintForCampus("长清校区"));
        assertEquals("1号教学楼", SdjtuStudyFormat.defaultBuildingHintForCampus("威海校区"));
    }
}
