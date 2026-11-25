package com.ecut.campus.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

public class AppBackupTest {

    @Test
    public void buildsValidatesAndSummarizesFullBackup() throws Exception {
        JSONObject arch = new JSONObject()
                .put("schemaVersion", 1)
                .put("activeSemesterId", "2026-2027-1")
                .put("timetables", new JSONArray()
                        .put(new JSONObject().put("courses", new JSONArray().put(new JSONObject().put("name", "高等数学")))));
        JSONObject prefs = new JSONObject()
                .put("displayDays", 5)
                .put("hiddenLocationPhrases", "长清校区");
        JSONArray friends = new JSONArray()
                .put(new JSONObject().put("id", "f1").put("name", "张三").put("code", "TL1:abc"));
        CourseTodo todos = new CourseTodo();
        todos.add("高等数学", "完成习题", "");

        JSONObject built = AppBackup.build("1.3.20", "2026-11-25 18:00",
                arch, new JSONObject(), prefs, friends, todos.toJson(), new JSONObject());
        built.put("password", "should_be_stripped");

        JSONObject parsed = AppBackup.parseAndValidate(built.toString());
        assertFalse(parsed.has("password"));
        assertEquals(5, parsed.getJSONObject("prefs").getInt("displayDays"));
        String summary = AppBackup.summarize(parsed);
        assertTrue(summary.contains("1 个学期（共 1 门课）"));
        assertTrue(summary.contains("1 位同学"));
        assertTrue(summary.contains("1 条作业待办"));
    }

    @Test
    public void rejectsUnknownKindOrNewerSchemaVersion() {
        assertThrows(JSONException.class, () -> AppBackup.parseAndValidate("{}"));
        assertThrows(JSONException.class, () -> AppBackup.parseAndValidate(
                "{\"kind\":\"lvlv_full_backup\",\"schemaVersion\":99}"));
    }
}
