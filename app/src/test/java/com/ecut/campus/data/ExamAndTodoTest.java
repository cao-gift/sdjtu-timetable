package com.ecut.campus.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Calendar;
import java.util.List;
import java.util.Map;

public class ExamAndTodoTest {

    @Test
    public void parsesExamsComputesCountdownAndSchedulesDayAndHourReminders() throws Exception {
        JSONObject data = new JSONObject()
                .put("term", "2026-2027-1")
                .put("exams", new JSONArray()
                        .put(new JSONObject()
                                .put("name", "高等数学A")
                                .put("code", "0000001")
                                .put("time", "2026-12-20 09:00-11:00")
                                .put("room", "长清A-101")
                                .put("seat", "12"))
                        .put(new JSONObject()
                                .put("name", "大学物理")
                                .put("time", "时间待定")));

        List<ExamCountdown.Entry> list = ExamCountdown.parseAll(data);
        assertEquals(2, list.size());
        ExamCountdown.Entry math = list.get(0);
        assertTrue(math.hasClock());
        assertEquals("2026-12-20", math.date);
        assertEquals(9 * 60, math.startMin);
        assertEquals(11 * 60, math.endMin);

        // 2026-12-18 09:00 -> 剩 2 天，下一次提醒为考前一天（12-19 09:00）
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.DECEMBER, 18, 9, 0, 0);
        long now1 = c.getTimeInMillis();
        assertEquals("剩 2 天", ExamCountdown.countdownLabel(math, now1));
        ExamCountdown.ReminderTrigger t1 = ExamCountdown.nextReminder(list, now1);
        assertNotNull(t1);
        assertEquals("明天考试", t1.leadLabel);
        assertEquals(math.startMillis - ExamCountdown.ONE_DAY_MS, t1.fireAtMillis);

        // 2026-12-20 07:00 -> 下一次提醒为考前一小时（12-20 08:00）
        c.set(2026, Calendar.DECEMBER, 20, 7, 0, 0);
        long now2 = c.getTimeInMillis();
        ExamCountdown.ReminderTrigger t2 = ExamCountdown.nextReminder(list, now2);
        assertNotNull(t2);
        assertEquals("1 小时后考试", t2.leadLabel);
        assertEquals(math.startMillis - ExamCountdown.ONE_HOUR_MS, t2.fireAtMillis);

        // 课表第 17 周匹配（2026-08-24 为第 1 周周一 -> 12-20 为第 17 周周日）
        assertEquals(1, ExamCountdown.examsInWeek(list, "2026-08-24", 17).size());
        assertEquals(0, ExamCountdown.examsInWeek(list, "2026-08-24", 16).size());

        // ICS 导出包含 VALARM (-P1D & -PT1H)
        String ics = IcsExporter.exportExams(list, "期末考试");
        assertNotNull(ics);
        assertTrue(ics.contains("SUMMARY:[考试] 高等数学A"));
        assertTrue(ics.contains("TRIGGER:-P1D"));
        assertTrue(ics.contains("TRIGGER:-PT1H"));
    }

    @Test
    public void courseTodoRoundTripsCountsAndBuildsPreClassSummary() throws Exception {
        CourseTodo book = new CourseTodo();
        CourseTodo.Item a = book.add("高等数学 A", "交第三章习题", "2026-12-01");
        CourseTodo.Item b = book.add("高等数学A", "预习第四章", "");
        assertNotNull(a);
        assertNotNull(b);
        assertNull(book.add("高等数学A", "   ", ""));

        Map<String, Integer> counts = book.pendingCounts();
        assertEquals(Integer.valueOf(2), counts.get(CourseTodo.normalizeCourseKey("高等数学A")));
        String sum = book.reminderSummary("高等数学A");
        assertTrue(sum.contains("交第三章习题"));
        assertTrue(sum.contains("等 2 项"));

        book.toggle(a.id);
        assertEquals(1, book.pendingForCourse("高等数学A").size());
        assertEquals("待办：预习第四章", book.reminderSummary("高等数学A"));

        String json = book.toJson().toString();
        CourseTodo restored = CourseTodo.fromJson(json);
        assertEquals(2, restored.items.size());
        assertEquals(1, restored.pendingForCourse("高等数学A").size());
        assertFalse(restored.pendingForCourse("大学物理").size() > 0);
    }
}
