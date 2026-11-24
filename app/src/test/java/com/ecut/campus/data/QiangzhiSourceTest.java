package com.ecut.campus.data;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** 强智 xskb_list.do 表格解析（fixture 已脱敏）。 */
public class QiangzhiSourceTest {

    static String fixture(String path) throws Exception {
        try (InputStream in = QiangzhiSourceTest.class.getClassLoader().getResourceAsStream(path)) {
            assertNotNull("缺少 fixture " + path, in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Timetable.Course course(Timetable t, String name) {
        for (Timetable.Course c : t.courses) if (c.name.equals(name)) return c;
        throw new AssertionError("没有课程 " + name + "，实际: " + t.courses.size());
    }

    private static Timetable.Session session(Timetable.Course c, int day) {
        for (Timetable.Session s : c.sessions) if (s.dayOfWeek == day) return s;
        throw new AssertionError(c.name + " 没有周" + day + " 的课");
    }

    @Test
    public void parsesStandardTable() throws Exception {
        QiangzhiSource.Result r = QiangzhiSource.parseHtml(fixture("fixtures/qiangzhi/xskb_list-sample.html"));
        assertTrue(r.warnings.toString(), r.ok());
        assertEquals("2025-2026-1", r.timetable.semester.id);
        assertEquals("2025-2026 学年第1学期", r.timetable.semester.name);
        assertEquals(5, r.courseCount);
        assertEquals(0, r.failedWeekExpr);

        Timetable.Course math = course(r.timetable, "高等数学A");
        assertEquals("张三", math.teacher);
        assertEquals(Timetable.SOURCE_JWGLXT, math.source);
        assertEquals(2, math.sessions.size());
        Timetable.Session mon = session(math, 1);
        assertEquals(1, mon.startSection);
        assertEquals(2, mon.endSection);
        assertEquals(16, mon.weeks.size());
        assertEquals("A101", mon.location);
        assertEquals("1-16(周)", mon.weeksRaw);
        Timetable.Session thu = session(math, 4);
        assertEquals(3, thu.startSection);
        assertEquals(4, thu.endSection);
        assertEquals(Arrays.asList(1, 3, 5, 7, 9, 11, 13, 15), thu.weeks);
        assertEquals("A102", thu.location);
    }

    @Test
    public void splitsMultipleCoursesInOneCellAndIgnoresBriefCopy() throws Exception {
        QiangzhiSource.Result r = QiangzhiSource.parseHtml(fixture("fixtures/qiangzhi/xskb_list-sample.html"));
        Timetable.Course en = course(r.timetable, "大学英语");
        Timetable.Course la = course(r.timetable, "线性代数");
        assertEquals(1, en.sessions.size());
        assertEquals(1, la.sessions.size());
        assertEquals(3, en.sessions.get(0).dayOfWeek);
        assertEquals(Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8), en.sessions.get(0).weeks);
        assertEquals("B202", en.sessions.get(0).location);
        assertEquals("王五", la.teacher);
        assertEquals(9, (int) la.sessions.get(0).weeks.get(0));
        assertEquals("C303", la.sessions.get(0).location);
    }

    @Test
    public void handlesEvenWeeksAndFourSectionBlocks() throws Exception {
        QiangzhiSource.Result r = QiangzhiSource.parseHtml(fixture("fixtures/qiangzhi/xskb_list-sample.html"));
        Timetable.Session phy = session(course(r.timetable, "大学物理"), 2);
        assertEquals(Arrays.asList(2, 4, 6, 8, 10, 12, 14, 16), phy.weeks);
        Timetable.Session lab = session(course(r.timetable, "程序设计实验"), 5);
        assertEquals(5, lab.startSection);
        assertEquals(8, lab.endSection);
        assertEquals(Arrays.asList(3, 4, 5, 6, 8, 9, 10, 11, 12), lab.weeks);
        assertEquals("实验楼E505", lab.location);
    }

    @Test
    public void usesHeaderForDaysAndRowLabelForSections() throws Exception {
        QiangzhiSource.Result r = QiangzhiSource.parseHtml(fixture("fixtures/qiangzhi/xskb_list-sunday-first.html"));
        assertEquals(3, r.courseCount);
        assertEquals("", r.timetable.semester.id);
        Timetable.Course pol = course(r.timetable, "思想政治理论");
        assertEquals("孙八", pol.teacher);
        Timetable.Session s = session(pol, 1);
        assertEquals(1, s.startSection);
        assertEquals(2, s.endSection);
        assertEquals("F606", s.location);
        assertEquals(6, course(r.timetable, "周末讲座").sessions.get(0).dayOfWeek);
        Timetable.Session vol = course(r.timetable, "志愿服务").sessions.get(0);
        assertEquals(7, vol.dayOfWeek);
        assertEquals(3, vol.startSection);
        assertEquals(4, vol.endSection);
    }

    @Test
    public void sdjtuDetailedCellsKeepNameAndNumberSeparate() {
        String metadata = "<br><font title='老师'>教师甲</font><br>"
                + "<font title='周次(节次)'>2-9(周)[01-02节]</font><br>"
                + "<font title='教室'>无影山1号教学楼1102</font>";
        String html = "<table id='kbtable'><tr><th>节次</th><th>星期一</th></tr>"
                + "<tr><th>第一大节</th><td><div class='kbcontent1'>004714-330001Y</div>"
                + "<div class='kbcontent'>004714-330001Y<br>俄语等级课程（口语）<br>（外）（5）"
                + metadata + "<br>-----------------<br>004720-050209R<br>"
                + "<font title='课程名称'>交通规划</font>" + metadata + "</div></td></tr></table>";
        QiangzhiSource.Result result = QiangzhiSource.parseHtml(html);
        assertEquals(2, result.courseCount);
        Timetable.Course russian = course(result.timetable, "俄语等级课程（口语）（外）（5）");
        assertEquals("004714-330001Y", russian.code);
        assertEquals("qiangzhi_004714-330001Y_教师甲", russian.key);
        assertEquals("无影山1号教学楼1102", session(russian, 1).location);
        assertEquals(8, session(russian, 1).weeks.size());
        Timetable.Course planning = course(result.timetable, "交通规划");
        assertEquals("004720-050209R", planning.code);
        assertEquals("qiangzhi_004720-050209R_教师甲", planning.key);
    }

    @Test
    public void usesLabeledNumberAndPreservesGenuineAlphanumericTitles() {
        String metadata = "<br><font title='教师'>教师乙</font><br><font title='周次'>1-2(周)</font>";
        String html = "<table id='kbtable'><tr><th>节次</th><th>星期一</th></tr>"
                + "<tr><th>第一大节</th><td><div class='kbcontent'>"
                + "数据库基础<br><span title='课程代码'>DB-202</span>" + metadata
                + "<br>---------<br>CS101" + metadata
                + "<br>---------<br>004722-J1HR0401B<br><font color='black'>Transport Logistics</font>"
                + metadata + "</div></td></tr></table>";
        QiangzhiSource.Result result = QiangzhiSource.parseHtml(html);
        assertEquals(3, result.courseCount);
        Timetable.Course db = course(result.timetable, "数据库基础");
        assertEquals("DB-202", db.code);
        assertEquals("qiangzhi_数据库基础_教师乙", db.key);
        Timetable.Course cs = course(result.timetable, "CS101");
        assertEquals("", cs.code);
        assertEquals("qiangzhi_CS101_教师乙", cs.key);
        assertEquals("004722-J1HR0401B", course(result.timetable, "Transport Logistics").code);
    }

    @Test
    public void separatesSameTitleAndTeacherWithDistinctNumbers() {
        String metadata = "<br><font title='老师'>教师丙</font><br><font title='周次'>1-5(周)</font>";
        String html = "<table id='kbtable'><tr><th>节次</th><th>星期一</th></tr>"
                + "<tr><th>第一大节</th><td><div class='kbcontent'>"
                + "交通运输<br><font title='课程号'>Z900123</font>" + metadata
                + "<br>----------<br>交通运输<br><font title='课程号'>Z900124</font>" + metadata
                + "<br>----------<br>Z900125<br>Science<br>of Materials" + metadata
                + "</div></td></tr></table>";
        QiangzhiSource.Result result = QiangzhiSource.parseHtml(html);
        assertEquals(3, result.courseCount);
        for (Timetable.Course c : result.timetable.courses) assertEquals("qiangzhi_" + c.code + "_教师丙", c.key);
        assertEquals("Z900123", result.timetable.courses.get(0).code);
        assertEquals("Z900124", result.timetable.courses.get(1).code);
        assertEquals("Z900125", course(result.timetable, "Science of Materials").code);
    }

    @Test
    public void unlabelledLegacyMetadataDoesNotBecomePartOfTheName() {
        String html = "<table id='kbtable'><tr><th>第一大节</th><td><div class='kbcontent'>"
                + "大学物理<br><font>教师丁</font><br><font>1-4(周)</font><br><font>A101</font>"
                + "</div></td></tr></table>";
        Timetable.Course c = course(QiangzhiSource.parseHtml(html).timetable, "大学物理");
        assertEquals("", c.code);
        assertEquals(Arrays.asList(1, 2, 3, 4), c.sessions.get(0).weeks);
    }

    @Test
    public void emptyOrErrorInputIsNotOk() {
        assertFalse(QiangzhiSource.parseHtml("").ok());
        assertFalse(QiangzhiSource.parseHtml(null).ok());
        assertFalse(QiangzhiSource.parseHtml("NOTABLE").ok());
        assertFalse(QiangzhiSource.parseHtml("<html><body>请先登录</body></html>").ok());
    }

    @Test
    public void helpers() {
        assertArrayEquals(new int[]{1, 4}, QiangzhiSource.sectionsFrom("01-02-03-04节"));
        assertArrayEquals(new int[]{9, 11}, QiangzhiSource.sectionsFrom("9-11节"));
        assertArrayEquals(new int[]{1, 2}, QiangzhiSource.rowLabelSections("第一大节 0102", 1));
        assertArrayEquals(new int[]{1, 2}, QiangzhiSource.rowLabelSections("第一大节 08:00-10:10", 1));
        assertArrayEquals(new int[]{3, 4}, QiangzhiSource.rowLabelSections("第二大节(03,04小节)", 2));
        assertArrayEquals(new int[]{5, 6}, QiangzhiSource.rowLabelSections("第三大节", 9));
        assertArrayEquals(new int[]{7, 8}, QiangzhiSource.rowLabelSections("", 4));
        assertEquals(3, QiangzhiSource.dayFromId("8A1F3C-3-2"));
        assertEquals(0, QiangzhiSource.dayFromId("kb"));
        assertEquals(7, QiangzhiSource.dayFromText("星期日"));
        assertEquals(1, QiangzhiSource.dayFromText("周一"));
    }

    @Test
    public void fallsBackToWeekdayTableWhenKbtableMissing() {
        String html = "<html><body>"
                + "<table><tr><td>查询条件</td><td><select><option>2026-2027-1</option></select></td></tr></table>"
                + "<table><tr><th>节次</th><th>星期一</th><th>星期二</th></tr>"
                + "<tr><th>第一大节 08:00-09:40</th>"
                + "<td><div class=\"kbcontent\">高等数学<br><font title=\"老师\">张三</font><br>"
                + "<font title=\"周次(节次)\">1-16(周)</font><br><font title=\"教室\">A101</font></div></td>"
                + "<td></td></tr></table>"
                + "</body></html>";
        QiangzhiSource.Result r = QiangzhiSource.parseHtml(html);
        assertTrue(r.warnings.toString(), r.ok());
        assertEquals(1, r.courseCount);
        Timetable.Session s = r.timetable.courses.get(0).sessions.get(0);
        assertEquals(1, s.dayOfWeek);
        assertEquals(1, s.startSection);
        assertEquals(2, s.endSection);
        assertEquals("A101", s.location);
    }

    @Test
    public void extractionJsTargetsHistoricTerm() {
        String js = QiangzhiSource.extractionJs("2024", "12");
        assertTrue(js.contains("2024-2025-2"));
        assertTrue(js.contains("xskb/xskb_list.do"));
        assertTrue(js.contains("withTerm"));
        assertEquals("2024-2025-1", QiangzhiSource.termId("2024", "3"));
        assertEquals("2024-2025-2", QiangzhiSource.termId("2024", "12"));
        assertEquals("", QiangzhiSource.termId("", "3"));
        assertEquals("", QiangzhiSource.termId("bad", "3"));
    }

    @Test
    public void parsesHiddenTermInputWhenSelectIsAbsent() {
        String html = "<input type=\"hidden\" name=\"xnxq01id\" value=\"2024-2025-2\">"
                + "<table id=\"kbtable\"><tr><th>节次</th><th>星期一</th></tr>"
                + "<tr><th>第一大节</th><td><div class=\"kbcontent\">高等数学<br>"
                + "<font title=\"老师\">张三</font><br><font title=\"周次(节次)\">1-8(周)[01-02节]</font>"
                + "</div></td></tr></table>";
        QiangzhiSource.Result r = QiangzhiSource.parseHtml(html);
        assertTrue(r.ok());
        assertEquals("2024-2025-2", r.timetable.semester.id);
        assertEquals("2024-2025 学年第2学期", r.timetable.semester.name);
    }

    @Test
    public void historySyncSkipsWhenTermMissingOrMismatched() {
        QiangzhiSource.SyncTarget ok = QiangzhiSource.resolveSyncTarget(
                "2024", "3", "2024-2025-1", "2026-2027-1", "2026-2027-1");
        assertEquals(QiangzhiSource.SyncTarget.Action.SAVE_ARCHIVE, ok.action);
        assertEquals("2024-2025-1", ok.semesterId);

        // 教务忽略请求参数、返回当前学期时必须跳过归档
        QiangzhiSource.SyncTarget ignored = QiangzhiSource.resolveSyncTarget(
                "2024", "3", "2026-2027-1", "2026-2027-1", "2026-2027-1");
        assertEquals(QiangzhiSource.SyncTarget.Action.SKIP, ignored.action);

        // 教务返回的课表缺少 xnxq01id 时必须跳过归档
        QiangzhiSource.SyncTarget missing = QiangzhiSource.resolveSyncTarget(
                "2024", "12", "", "2026-2027-1", "2026-2027-1");
        assertEquals(QiangzhiSource.SyncTarget.Action.SKIP, missing.action);

        QiangzhiSource.SyncTarget badParam = QiangzhiSource.resolveSyncTarget(
                "bad", "3", "2026-2027-1", "2026-2027-1", "2026-2027-1");
        assertEquals(QiangzhiSource.SyncTarget.Action.SKIP, badParam.action);
    }

    @Test
    public void normalSyncProtectsHistoricSemesterWhenTermMissingOrDifferent() {
        // 普通同步返回当前学期，即使当前停留在历史学期也写入返回的学期而不覆盖历史
        QiangzhiSource.SyncTarget normalNew = QiangzhiSource.resolveSyncTarget(
                null, null, "2026-2027-1", "2025-2026-2", "2026-2027-1");
        assertEquals(QiangzhiSource.SyncTarget.Action.SAVE_ACTIVE, normalNew.action);
        assertEquals("2026-2027-1", normalNew.semesterId);

        // 普通同步缺少 xnxq01id：当前就是本学期允许沿用
        QiangzhiSource.SyncTarget missingOnCurrent = QiangzhiSource.resolveSyncTarget(
                null, null, "", "2026-2027-1", "2026-2027-1");
        assertEquals(QiangzhiSource.SyncTarget.Action.SAVE_ACTIVE, missingOnCurrent.action);
        assertEquals("2026-2027-1", missingOnCurrent.semesterId);

        // 普通同步缺少 xnxq01id：停留在历史学期或自定义学期或空归档时禁止覆盖
        assertEquals(QiangzhiSource.SyncTarget.Action.SKIP,
                QiangzhiSource.resolveSyncTarget(null, null, "", "2025-2026-2", "2026-2027-1").action);
        assertEquals(QiangzhiSource.SyncTarget.Action.SKIP,
                QiangzhiSource.resolveSyncTarget(null, null, "", "custom_123", "2026-2027-1").action);
        assertEquals(QiangzhiSource.SyncTarget.Action.SKIP,
                QiangzhiSource.resolveSyncTarget(null, null, "", "", "2026-2027-1").action);
    }
}
