package com.ecut.campus.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** v1.2.4 自查修复的回归测试。 */
public class ReviewFixesTest {

    private static String cell(String weeks) {
        return "<table id=\"kbtable\"><tr><th></th><th>星期一</th><th>星期二</th><th>星期三</th>"
                + "<th>星期四</th><th>星期五</th><th>星期六</th><th>星期日</th></tr>"
                + "<tr><th>第一大节</th><td><div class=\"kbcontent\" id=\"X-1-2\">测试课程<br>"
                + "<font title=\"老师\">教师甲</font><br><font title=\"周次(节次)\">" + weeks + "</font>"
                + "<br><font title=\"教室\">B201</font></div></td>"
                + "<td></td><td></td><td></td><td></td><td></td><td></td></tr></table>";
    }

    @Test
    public void qiangzhiWeekBracketIsNotSections() {
        Timetable.Session s = QiangzhiSource.parseHtml(cell("1-16[周][03-04节]"))
                .timetable.courses.get(0).sessions.get(0);
        assertEquals(3, s.startSection);
        assertEquals(4, s.endSection);
        assertEquals(16, s.weeks.size());

        s = QiangzhiSource.parseHtml(cell("[2-9]周[05-06]节")).timetable.courses.get(0).sessions.get(0);
        assertEquals(5, s.startSection);
        assertEquals(6, s.endSection);
        assertEquals(8, s.weeks.size());

        s = QiangzhiSource.parseHtml(cell("1-8,10-16(周)[01-02-03-04节]")).timetable.courses.get(0).sessions.get(0);
        assertEquals(1, s.startSection);
        assertEquals(4, s.endSection);
        assertEquals(15, s.weeks.size());
        assertEquals("1-8,10-16(周)", s.weeksRaw);

        // 没有「节」字的老写法照旧
        s = QiangzhiSource.parseHtml(cell("1-15(单周)[07-08]")).timetable.courses.get(0).sessions.get(0);
        assertEquals(7, s.startSection);
        assertEquals(8, s.endSection);
        assertEquals(8, s.weeks.size());

        // 只有周次括号：节次按行表头
        s = QiangzhiSource.parseHtml(cell("1-16[周]")).timetable.courses.get(0).sessions.get(0);
        assertEquals(1, s.startSection);
        assertEquals(2, s.endSection);
        assertEquals(16, s.weeks.size());
    }

    @Test
    public void pageSampleStripsSessionIdAndSpacedPhone() {
        String out = PageSample.sanitize("{\"path\":\"/jsxsd/framework/xsMain.jsp;jsessionid=AB12CD34EF\","
                + "\"labels\":{\"138 1234 5678\":1,\"139-8765-4321\":1}}");
        assertFalse(out, out.contains("AB12CD34EF"));
        assertFalse(out, out.contains("1234"));
        assertFalse(out, out.contains("8765"));
        assertTrue(out, out.contains("/jsxsd/framework/xsMain.jsp"));
    }
}
