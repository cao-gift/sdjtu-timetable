package com.ecut.campus.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;

/** 覆盖本次新增的节假日调休、考试时间轴、作业图片与截止提醒、小组件学期切换逻辑。 */
public class PolishFeaturesDataTest {

    @Test
    public void holidaysProvideMakeupNoticesAndActiveBreakCountdown() {
        // 2026-08-24 第 1 周周一 -> 第 7 周 10/5..10/11（周六 10/10 调休补 10/7 周三）
        Timetable t = new Timetable();
        List<String> notices = Holidays.weekMakeupNotices(t, "2026-08-24", 7);
        assertEquals(1, notices.size());
        assertTrue(notices.get(0).contains("周六"));
        assertTrue(notices.get(0).contains("按周三上课"));
        assertTrue(Holidays.weekNoticeSummary(t, "2026-08-24", 7).startsWith("调休："));

        // 2026 国庆 10/1..10/7（共 7 天），10/3 含今天还剩 5 天
        Holidays.BreakInfo br = Holidays.activeBreak(t, "2026-10-03");
        assertNotNull(br);
        assertEquals("国庆", br.label);
        assertEquals(7, br.totalDays);
        assertEquals(5, br.daysLeft);
        assertNull(Holidays.activeBreak(t, "2026-10-12"));

        // 2027 年五一调休存在
        assertNotNull(Holidays.lookup(t, "2027-05-08"));
        assertFalse(Holidays.lookup(t, "2027-05-08").off);
    }

    @Test
    public void examTimelineOrdersUpcomingFirstAndHighlightsOnlyNearest() throws Exception {
        JSONObject data = new JSONObject()
                .put("term", "2026-2027-1")
                .put("exams", new JSONArray()
                        .put(new JSONObject().put("name", "已考完课").put("time", "2026-11-20 09:00-11:00"))
                        .put(new JSONObject().put("name", "待定课程").put("time", ""))
                        .put(new JSONObject().put("name", "第二场未考").put("time", "2026-12-22 14:00-16:00"))
                        .put(new JSONObject().put("name", "最近一场").put("time", "2026-12-18 09:00-11:00")));
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.DECEMBER, 15, 10, 0, 0);
        long now = c.getTimeInMillis();

        List<ExamCountdown.Entry> sorted = ExamCountdown.sortTimeline(ExamCountdown.parseAll(data), now);
        assertEquals(4, sorted.size());
        assertEquals("最近一场", sorted.get(0).name);
        assertEquals("第二场未考", sorted.get(1).name);
        assertEquals("待定课程", sorted.get(2).name);
        assertEquals("已考完课", sorted.get(3).name);
        assertEquals(0, ExamCountdown.nearestUpcomingIndex(sorted, now));
    }

    @Test
    public void courseTodoSupportsPhotosDeadlinesAndWidgetHints() throws Exception {
        CourseTodo book = new CourseTodo();
        CourseTodo.Item it = book.add("高等数学", "交第3章作业", "2026-11-26", "拍题写过程",
                Arrays.asList("tp_1.jpg", "tp_2.jpg"));
        assertNotNull(it);
        book.addPhoto(it.id, "tp_3.jpg");
        assertTrue(book.removePhoto(it.id, "tp_1.jpg"));

        CourseTodo restored = CourseTodo.fromJson(book.toJson().toString());
        assertEquals(1, restored.items.size());
        assertEquals("拍题写过程", restored.items.get(0).note);
        assertEquals(Arrays.asList("tp_2.jpg", "tp_3.jpg"), restored.items.get(0).photos);

        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(2026, Calendar.NOVEMBER, 25, 14, 0, 0);
        long now = c.getTimeInMillis();
        assertEquals("明天截止", CourseTodo.dueLabel(restored.items.get(0), now));
        CourseTodo.DeadlineTrigger tr = restored.nextDeadlineReminder(now);
        assertNotNull(tr);
        assertEquals("明天截止", tr.leadLabel);
        assertTrue(restored.urgentWidgetHint(now, 2).contains("交第3章作业"));
    }

    @Test
    public void widgetsSwitchForSemesterStartHolidayAndTodoHints() {
        WidgetDay.Card before = WidgetDay.buildWithContext(WidgetDay.BEFORE, "2026-08-20", 0, "2026-08-24",
                Collections.emptyList(), Collections.emptyList(), 540, 3, WidgetDay.WIDE, null, "");
        assertEquals("距开学 4 天", before.title);
        assertEquals("4", before.title.substring(before.numStart, before.numEnd));

        Holidays.BreakInfo br = new Holidays.BreakInfo("国庆", 5, 7);
        WidgetDay.Card holiday = WidgetDay.buildWithContext(WidgetDay.OK, "2026-10-03", 6, "2026-08-24",
                Collections.emptyList(), Collections.emptyList(), 540, 3, WidgetDay.WIDE, br, "");
        assertEquals("国庆假期 · 剩 5 天", holiday.title);
        assertEquals("5", holiday.title.substring(holiday.numStart, holiday.numEnd));

        WidgetProgress.Snapshot preSnap = WidgetProgress.build(Collections.emptyList(), Collections.emptyList(),
                540, "", "", WidgetDay.BEFORE, "2026-08-20", "2026-08-24", null);
        assertEquals(WidgetProgress.MODE_SEMESTER, preSnap.mode);
        assertEquals("4天", preSnap.primaryReadout);

        WidgetProgress.Snapshot holSnap = WidgetProgress.build(Collections.emptyList(), Collections.emptyList(),
                540, "", "", WidgetDay.OK, "2026-10-03", "2026-08-24", br);
        assertEquals(WidgetProgress.MODE_SEMESTER, holSnap.mode);
        assertEquals("剩5天", holSnap.primaryReadout);

        WidgetProgress.Snapshot todoSnap = WidgetProgress.build(Collections.emptyList(), Collections.emptyList(),
                540, "", "作业 · 交习题（明天截止）", WidgetDay.OK, "2026-11-25", "2026-08-24", null);
        assertEquals("作业 · 交习题（明天截止）", todoSnap.title);
    }

    @Test
    public void icsCollectEventsProducesSortedCalendarEvents() {
        Timetable t = new Timetable();
        t.semester.id = "2026-2027-1";
        t.semester.startDate = "2026-08-24";
        t.semester.totalWeeks = 2;
        Timetable.Course c = new Timetable.Course();
        c.name = "高等数学";
        Timetable.Session s = new Timetable.Session();
        s.dayOfWeek = 1; s.startSection = 1; s.endSection = 2; s.location = "长清A-101";
        s.weeks = Arrays.asList(1, 2);
        c.sessions.add(s);
        t.courses.add(c);

        List<IcsExporter.CalendarEvent> events = IcsExporter.collectEvents(t, null);
        assertEquals(2, events.size());
        assertEquals("2026-08-24", events.get(0).ymd);
        assertEquals("2026-08-31", events.get(1).ymd);
        assertTrue(events.get(0).endMillis > events.get(0).startMillis);
    }
}
