package com.ecut.campus;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import static com.ecut.campus.SdjtuAcademicPages.Feature.PLAN;
import static com.ecut.campus.SdjtuAcademicPages.Feature.PORTAL;
import static com.ecut.campus.SdjtuAcademicPages.Feature.ROOMS;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SdjtuStudyStoreTest {
    @Test public void acceptsAnExplicitlyEmptyResultForEitherFeature() throws JSONException {
        assertTrue(SdjtuStudyStore.valid(ROOMS, new JSONObject().put("rooms", new JSONArray())));
        assertTrue(SdjtuStudyStore.valid(PLAN, new JSONObject().put("courses", new JSONArray())));
    }

    @Test public void requiresTheArrayForTheRequestedFeature() throws JSONException {
        assertFalse(SdjtuStudyStore.valid(ROOMS, null));
        assertFalse(SdjtuStudyStore.valid(ROOMS, new JSONObject()));
        assertFalse(SdjtuStudyStore.valid(ROOMS, new JSONObject().put("courses", new JSONArray())));
        assertFalse(SdjtuStudyStore.valid(PLAN, new JSONObject().put("courses", JSONObject.NULL)));
        assertFalse(SdjtuStudyStore.valid(PLAN, new JSONObject().put("courses", "[]")));
        assertFalse(SdjtuStudyStore.valid(PORTAL, new JSONObject().put("courses", new JSONArray())));
    }

    @Test public void everyRoomMustHaveANameAndARecognizedState() throws JSONException {
        JSONArray rows = new JSONArray();
        JSONObject result = new JSONObject().put("rooms", rows);
        for (String state : new String[]{"free", "busy", "unknown"}) {
            rows.put(new JSONObject().put("name", "A101").put("state", state));
        }
        assertTrue(SdjtuStudyStore.valid(ROOMS, result));
        JSONObject last = rows.getJSONObject(2);
        last.put("state", "closed");
        assertFalse(SdjtuStudyStore.valid(ROOMS, result));
        last.remove("state");
        assertFalse(SdjtuStudyStore.valid(ROOMS, result));
        last.put("state", "free").put("name", "  ");
        assertFalse(SdjtuStudyStore.valid(ROOMS, result));
    }

    @Test public void rejectsMalformedCourses() throws JSONException {
        JSONArray rows = new JSONArray().put(new JSONObject().put("name", "示例课程"));
        JSONObject result = new JSONObject().put("courses", rows);
        assertTrue(SdjtuStudyStore.valid(PLAN, result));
        for (Object invalid : new Object[]{"", 45, JSONObject.NULL, new JSONObject(),
                new JSONObject().put("name", "  "), new JSONObject().put("name", 18)}) {
            rows.put(1, invalid);
            assertFalse(SdjtuStudyStore.valid(PLAN, result));
        }
    }
}
