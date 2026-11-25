package com.ecut.campus.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Set;

/** 小玩意的纯逻辑：翻牌批语、每节课价格、缺勤上限。 */
public class FunStatsTest {

    static Timetable.Session ses(int day, int start, int end, int fromWeek, int toWeek, String loc) {
        Timetable.Session s = new Timetable.Session();
        s.dayOfWeek = day;
        s.startSection = start;
        s.endSection = end;
        for (int w = fromWeek; w <= toWeek; w++) s.weeks.add(w);
        s.location = loc;
        return s;
    }

    static Timetable.Course course(String name, Timetable.Session... ss) {
        Timetable.Course c = new Timetable.Course();
        c.name = name;
        c.key = "k_" + name + "_" + ss.length;
        c.sessions.addAll(Arrays.asList(ss));
        return c;
    }

    // ---------- GradeVerdict ----------
    @Test
    public void verdictBandsNumeric() {
        assertEquals(GradeVerdict.Band.TOP, GradeVerdict.band("95", 95.0, true));
        assertEquals(GradeVerdict.Band.TOP, GradeVerdict.band("90", null, true));
        assertEquals(GradeVerdict.Band.GOOD, GradeVerdict.band("89.5", 89.5, true));
        assertEquals(GradeVerdict.Band.MID, GradeVerdict.band("70", 70.0, true));
        assertEquals(GradeVerdict.Band.PASS, GradeVerdict.band("60", 60.0, true));
        assertEquals(GradeVerdict.Band.FAIL, GradeVerdict.band("59", 59.0, true));
        assertEquals(GradeVerdict.Band.FAIL, GradeVerdict.band("75", 75.0, false));
        assertEquals(GradeVerdict.Band.GOOD, GradeVerdict.band("", 85.0, true));
    }

    @Test
    public void verdictBandsLevelsAndPassFail() {
        assertEquals(GradeVerdict.Band.TOP, GradeVerdict.band("优秀", 95.0, true));
        assertEquals(GradeVerdict.Band.GOOD, GradeVerdict.band("良好", null, true));
        assertEquals(GradeVerdict.Band.MID, GradeVerdict.band("中等", null, true));
        assertEquals(GradeVerdict.Band.PASS, GradeVerdict.band("及格", null, true));
        assertEquals(GradeVerdict.Band.FAIL, GradeVerdict.band("不及格", null, true));
        assertEquals(GradeVerdict.Band.PASSED, GradeVerdict.band("合格", null, true));
        assertEquals(GradeVerdict.Band.PASSED, GradeVerdict.band("通过", 85.0, true));
        assertEquals(GradeVerdict.Band.FAIL, GradeVerdict.band("不合格", null, false));
        assertEquals(GradeVerdict.Band.GOOD, GradeVerdict.band("b+", null, true));
        assertNull(GradeVerdict.band("", null, true));
        assertNull(GradeVerdict.band("缓考", 0.0, true));
        assertNull(GradeVerdict.band("", null, false));
    }

    @Test
    public void verdictPickIsFromBandAndEveryBandHasSeveral() {
        for (GradeVerdict.Band b : GradeVerdict.Band.values()) {
            String[] lines = GradeVerdict.lines(b);
            assertTrue(lines.length >= 3);
            String p = GradeVerdict.pick(b, 7);
            assertTrue(Arrays.asList(lines).contains(p));
            assertTrue(Arrays.asList(lines).contains(GradeVerdict.pick(b, -123456789L)));
            for (String l : lines) assertTrue(l, l.length() <= 14);
        }
        assertEquals("", GradeVerdict.pick(null, 1));
    }

    @Test
    public void verdictSkipsExamStatusAndIsStable() {
        // 缓考/缺考没给通过标记，也不能被说成挂科
        assertNull(GradeVerdict.band("缓考", null, false));
        assertNull(GradeVerdict.band("缺考", 0.0, false));
        assertNull(GradeVerdict.band("旷考", null, false));
        assertNull(GradeVerdict.band("作弊", 0.0, false));
        assertEquals(GradeVerdict.Band.FAIL, GradeVerdict.band("45", 45.0, false));
        long seed = java.util.Objects.hash("高等数学", "92");
        assertEquals(GradeVerdict.pick(GradeVerdict.Band.TOP, seed), GradeVerdict.pick(GradeVerdict.Band.TOP, seed));
    }

    // ---------- ClassPrice ----------
    @Test
    public void totalSectionsCountsRealOccurrences() {
        Timetable t = new Timetable();
        t.courses.add(course("高数", ses(1, 1, 2, 1, 16, "A101"), ses(3, 3, 4, 1, 8, "A101")));
        t.courses.add(course("实验", ses(5, 5, 7, 2, 4, "B2")));
        assertEquals(2 * 16 + 2 * 8 + 3 * 3, ClassPrice.totalSections(t, 20));
        assertEquals(2 * 4 + 2 * 4 + 3 * 3, ClassPrice.totalSections(t, 4));
        assertEquals(0, ClassPrice.totalSections(new Timetable(), 20));
        assertEquals(0, ClassPrice.totalSections(null, 20));
        // 同一时段重叠的课（口令留下的两条、手动重复加的）只算一次
        t.courses.add(course("高数重复", ses(1, 2, 3, 1, 16, "A101")));
        assertEquals(2 * 16 + 16 + 2 * 8 + 3 * 3, ClassPrice.totalSections(t, 20));
    }

    @Test
    public void perSectionAndFormatting() {
        assertEquals(10.0, ClassPrice.perSection(570, 57), 1e-9);
        assertEquals(0, ClassPrice.perSection(0, 57), 1e-9);
        assertEquals(0, ClassPrice.perSection(5000, 0), 1e-9);
        assertEquals("19.8", ClassPrice.yuan(19.84));
        assertEquals("128", ClassPrice.yuan(127.6));
        assertEquals(5500, ClassPrice.parseTuition(" ¥5,500元 "), 1e-9);
        assertEquals(0, ClassPrice.parseTuition("五千"), 1e-9);
        assertEquals(0, ClassPrice.parseTuition(""), 1e-9);
    }

    @Test
    public void earlyEightTease() {
        assertTrue(ClassPrice.isEarlyEight(ses(1, 1, 2, 1, 1, "")));
        assertFalse(ClassPrice.isEarlyEight(ses(1, 3, 4, 1, 1, "")));
        assertTrue(ClassPrice.teaseLine(16, 0).contains("一杯奶茶"));
        assertTrue(ClassPrice.teaseLine(90, 1).contains("2张电影票"));
        assertTrue(ClassPrice.teaseLine(1, 2).contains("一瓶矿泉水"));
        assertEquals("", ClassPrice.teaseLine(0, 0));
        assertEquals(ClassPrice.teaseLine(20, "高数".hashCode()), ClassPrice.teaseLine(20, "高数".hashCode()));
    }

    // ---------- AbsenceBook ----------
    @Test
    public void absenceLimitIsOneThirdOfSessionsAcrossSameNamedCourses() {
        Timetable t = new Timetable();
        t.courses.add(course("大学英语", ses(1, 1, 2, 1, 16, "")));
        t.courses.add(course("大学 英语", ses(3, 1, 2, 1, 8, ""))); // 另一个 key、同名（带空格）
        assertEquals(24, AbsenceBook.sessionCount(t, "大学英语", 20));
        assertEquals(8, AbsenceBook.limit(24));
        assertEquals(0, AbsenceBook.limit(2));
        assertEquals(0, AbsenceBook.sessionCount(t, "高数", 20));
    }

    @Test
    public void absenceWarnAtEightyPercentAndSurvivesJson() {
        AbsenceBook b = new AbsenceBook();
        b.bump("大学英语", 0, 3);
        b.bump("大学英语", 1, 3);
        b.bump("大学英语", 2, 5);
        b.bump("大学英语", 1, -10); // 不会变负
        AbsenceBook.Counts c = b.get("大学　英语");
        assertEquals(3, c.leave);
        assertEquals(0, c.absent);
        assertEquals(5, c.called);
        assertFalse(AbsenceBook.warn(c, 8));   // 3 < 6.4
        b.bump("大学英语", 1, 4);                // 7 ≥ 6.4
        assertTrue(AbsenceBook.warn(b.get("大学英语"), 8));
        assertFalse(AbsenceBook.warn(new AbsenceBook.Counts(), 0));

        AbsenceBook r = AbsenceBook.fromJson(b.toJson());
        assertEquals(7, r.get("大学英语").used());
        assertEquals(5, r.get("大学英语").called);
        assertEquals(0, AbsenceBook.fromJson("not json").get("x").used());

        // 重新导入后课程 key 变了：按课名仍能对上
        Timetable t = new Timetable();
        t.courses.add(course("大学英语", ses(1, 1, 2, 1, 16, ""), ses(3, 1, 2, 1, 8, "")));
        t.courses.add(course("高数", ses(2, 1, 2, 1, 16, "")));
        Set<String> warn = r.warnKeys(t, 20);
        assertTrue(warn.contains("大学英语"));
        assertEquals(1, warn.size());
    }

    @Test
    public void absenceFollowsRename() {
        AbsenceBook b = new AbsenceBook();
        b.bump("高等数学", 1, 2);
        b.bump("高等数学A", 0, 1);
        Timetable t = new Timetable();
        t.courses.add(course("高等数学A", ses(1, 1, 2, 1, 16, "")));
        assertTrue(b.rename("高等数学", "高等数学A", t));
        assertEquals(3, b.get("高等数学A").used());
        assertEquals(0, b.get("高等数学").used());
        assertFalse(AbsenceBook.fromJson(b.toJson()).toJson().contains("高等数学\""));
        // 课表里还有别的课叫旧名：计数留给它
        b.bump("英语", 1, 1);
        t.courses.add(course("英语", ses(2, 1, 2, 1, 16, "")));
        assertFalse(b.rename("英语", "英语听说", t));
        assertEquals(1, b.get("英语").used());
        assertFalse(b.rename("不存在", "x", t));
    }
}
