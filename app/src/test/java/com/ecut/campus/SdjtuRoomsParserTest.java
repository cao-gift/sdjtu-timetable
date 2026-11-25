package com.ecut.campus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

/** 脱敏空教室与培养方案快照校验测试（张三 / 2024000000 规范，无真实个人信息）。 */
public class SdjtuRoomsParserTest {

    @Test
    public void validatesRoomSnapshotAndRejectsMalformedEntries() throws JSONException {
        JSONArray rooms = new JSONArray()
                .put(new JSONObject().put("name", "长清教学楼A-101").put("state", "free"))
                .put(new JSONObject().put("name", "长清教学楼A-102").put("state", "busy"))
                .put(new JSONObject().put("name", "长清教学楼A-103").put("state", "unknown"));
        JSONObject cond = new JSONObject()
                .put("campus", "1")
                .put("week", "14")
                .put("building", "");
        JSONObject valid = new JSONObject()
                .put("summary", "长清校区 · 第14周 · 全部教学楼")
                .put("rooms", rooms)
                .put("roomConditions", cond);

        assertTrue(SdjtuStudyStore.valid(SdjtuAcademicPages.Feature.ROOMS, valid));

        JSONObject emptyName = new JSONObject().put("rooms",
                new JSONArray().put(new JSONObject().put("name", "").put("state", "free")));
        assertFalse(SdjtuStudyStore.valid(SdjtuAcademicPages.Feature.ROOMS, emptyName));
        assertFalse(SdjtuStudyStore.valid(SdjtuAcademicPages.Feature.ROOMS, new JSONObject()));
    }

    @Test
    public void validatesPlanSnapshotWithSanitizedCourses() throws JSONException {
        JSONArray courses = new JSONArray()
                .put(new JSONObject()
                        .put("name", "高等数学A")
                        .put("code", "0000001")
                        .put("term", "1")
                        .put("credit", "5.0")
                        .put("hours", "80")
                        .put("system", "公共基础课")
                        .put("status", "已修"))
                .put(new JSONObject()
                        .put("name", "大学英语")
                        .put("code", "0000002")
                        .put("term", "2")
                        .put("credit", "3.0")
                        .put("system", "公共基础课"));
        JSONObject plan = new JSONObject()
                .put("planChoice", "2024级示例专业培养方案")
                .put("courses", courses);

        assertTrue(SdjtuStudyStore.valid(SdjtuAcademicPages.Feature.PLAN, plan));
        assertEquals(2, plan.getJSONArray("courses").length());
    }
}
