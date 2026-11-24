package com.ecut.campus;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.*;

public class SdjtuExamScheduleTest {
    private static final String TERM = "2026-2027-1";
    private static JSONObject exam(String name, String time, String room, String seat) throws JSONException {
        return new JSONObject().put("name", name).put("time", time).put("room", room).put("seat", seat);
    }

    private static JSONObject page(JSONObject... items) throws JSONException {
        JSONArray arr = new JSONArray();
        for (JSONObject item : items) arr.put(item);
        return new JSONObject().put("term", TERM).put("exams", arr);
    }

    @Test public void mergesMatchingSittingsWithoutLeakingSourceOrPersonalColumns() throws JSONException {
        JSONObject first = exam("示例运输", "2026年12月1日 9:00—10:00", "示例101", "")
                .put("category","期末").put("studentId","2024000000");
        JSONObject second = exam("示例运输", "2026/12/01 09：00~10:00", "示例101", "18").put("code","D01");
        JSONObject data = SdjtuExamSchedule.merge(page(first), page(second));
        assertEquals(TERM, data.getString("term"));
        JSONArray rows = data.getJSONArray("exams");
        assertEquals(1, rows.length());
        JSONObject row = rows.getJSONObject(0);
        assertEquals("18", row.getString("seat"));
        assertEquals("D01", row.getString("code"));
        assertFalse(row.has("category"));
        assertFalse(row.has("studentId"));
        assertEquals("", first.getString("seat"));
    }

    @Test public void differentSittingsSurviveAndUnknownTimesStayInOriginalOrder() throws JSONException {
        JSONObject late = exam("示例运输","2026-12-03 11:30","示例101","19");
        JSONObject a = exam("示例运输","2026-12-03 8:00","示例201","2");
        JSONObject b = exam("示例运输","2026-12-03 8:00","示例202","3");
        JSONObject pending = exam("示例课程甲","时间待定","","");
        JSONObject pending2 = exam("示例课程乙","","","");
        JSONObject earlier = exam("示例课程丙","2026-12-02 15:20","示例201","");
        JSONArray rows = SdjtuExamSchedule.merge(page(late,a,pending),page(b,a,earlier,pending2)).getJSONArray("exams");
        assertEquals(6, rows.length());
        assertEquals("示例课程丙", rows.getJSONObject(0).getString("name"));
        assertEquals("示例201", rows.getJSONObject(1).getString("room"));
        assertEquals("示例202", rows.getJSONObject(2).getString("room"));
        assertEquals("19", rows.getJSONObject(3).getString("seat"));
        assertEquals("示例课程甲", rows.getJSONObject(4).getString("name"));
        assertEquals("示例课程乙", rows.getJSONObject(5).getString("name"));
    }

    @Test public void ambiguousRoomAndConflictingSeatAreKeptSeparate() throws JSONException {
        String t = "2027-01-05 08:45";
        JSONObject a = exam("示例一", t, "A", ""), b = exam("示例一", t, "B", "");
        assertEquals(3, SdjtuExamSchedule.merge(page(a,b),page(exam("示例一",t,"","16"))).getJSONArray("exams").length());
        assertEquals(2, SdjtuExamSchedule.merge(page(exam("示例一",t,"A","1")),page(exam("示例一",t,"A","2")))
                .getJSONArray("exams").length());
    }

    @Test public void sameNameAndMissingSittingDetailsCannotEstablishADuplicate() throws JSONException {
        JSONObject pending = exam("示例运输", "待定", "", "");
        JSONObject timeOnly = exam("示例运输", "2026-12-03 08:30", "", "");
        JSONArray rows = SdjtuExamSchedule.merge(page(pending,pending,timeOnly),page(pending,timeOnly)).getJSONArray("exams");
        assertEquals(5, rows.length());
        assertEquals("待定", rows.getJSONObject(4).getString("time"));
    }

    @Test public void acceptsExplicitEmptyAndSingleSourceButRejectsDifferentOrMissingTerms() throws JSONException {
        assertEquals(0, SdjtuExamSchedule.merge(page(),page()).getJSONArray("exams").length());
        assertEquals(1, SdjtuExamSchedule.merge(null,page(exam("示例课程","","",""))).getJSONArray("exams").length());
        assertThrows(IllegalArgumentException.class, () -> SdjtuExamSchedule.merge(null,null));
        assertThrows(IllegalArgumentException.class, () -> SdjtuExamSchedule.merge(page(),page().put("term","2026-2027-2")));
        assertFalse(SdjtuStudyStore.valid(SdjtuAcademicPages.Feature.EXAMS, page().put("term"," ")));
        assertFalse(SdjtuStudyStore.valid(SdjtuAcademicPages.Feature.EXAMS, new JSONObject().put("term",TERM)));
        assertFalse(SdjtuStudyStore.valid(SdjtuAcademicPages.Feature.EXAMS, page(exam("示例课程","","","").put("time",12))));
        assertThrows(IllegalArgumentException.class, () -> SdjtuExamSchedule.merge(page(),page(exam("  ","","",""))));
    }
}
