package com.ecut.campus.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 课程作业与待办（纯 Java，无 Android 依赖）：
 * 按规范化课程名关联（与配色/缺勤规则一致），重新同步教务课表后不会丢失，可随课前提醒一并推送。
 */
public final class CourseTodo {

    public static final int SCHEMA_VERSION = 1;

    public static final class Item {
        public String id = "";
        public String courseKey = "";
        public String courseName = "";
        public String title = "";
        public String dueDate = "";
        public boolean done = false;
        public boolean remindBeforeClass = true;
        public long createdAt = 0L;

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("courseKey", courseKey);
            o.put("courseName", courseName);
            o.put("title", title);
            if (dueDate != null && !dueDate.isEmpty()) o.put("dueDate", dueDate);
            o.put("done", done);
            o.put("remindBeforeClass", remindBeforeClass);
            o.put("createdAt", createdAt);
            return o;
        }

        public static Item fromJson(JSONObject o) {
            Item it = new Item();
            it.id = o.optString("id", "");
            it.courseKey = o.optString("courseKey", "");
            it.courseName = o.optString("courseName", "");
            it.title = o.optString("title", "");
            it.dueDate = o.optString("dueDate", "");
            it.done = o.optBoolean("done", false);
            it.remindBeforeClass = o.optBoolean("remindBeforeClass", true);
            it.createdAt = o.optLong("createdAt", 0L);
            if (it.courseKey.isEmpty() && !it.courseName.isEmpty()) {
                it.courseKey = normalizeCourseKey(it.courseName);
            }
            return it;
        }
    }

    public final List<Item> items = new ArrayList<>();

    public static String normalizeCourseKey(String courseName) {
        return CourseColorAssigner.normalizeName(courseName);
    }

    public Item add(String courseName, String title, String dueDate) {
        String cleanTitle = title == null ? "" : title.trim();
        if (cleanTitle.isEmpty()) return null;
        Item it = new Item();
        it.id = "td_" + UUID.randomUUID().toString().substring(0, 8);
        it.courseName = courseName == null ? "" : courseName.trim();
        it.courseKey = normalizeCourseKey(it.courseName);
        it.title = cleanTitle;
        it.dueDate = dueDate == null ? "" : dueDate.trim();
        it.done = false;
        it.remindBeforeClass = true;
        it.createdAt = System.currentTimeMillis();
        items.add(it);
        return it;
    }

    public boolean toggle(String id) {
        for (Item it : items) {
            if (it.id.equals(id)) {
                it.done = !it.done;
                return true;
            }
        }
        return false;
    }

    public boolean remove(String id) {
        return items.removeIf(it -> it.id.equals(id));
    }

    public List<Item> forCourse(String courseName) {
        String key = normalizeCourseKey(courseName);
        List<Item> out = new ArrayList<>();
        if (key.isEmpty()) return out;
        for (Item it : items) {
            if (key.equals(it.courseKey)) out.add(it);
        }
        // 未完成排前面，已完成排后面
        out.sort((a, b) -> {
            if (a.done != b.done) return a.done ? 1 : -1;
            return Long.compare(a.createdAt, b.createdAt);
        });
        return out;
    }

    public List<Item> pendingForCourse(String courseName) {
        List<Item> out = new ArrayList<>();
        for (Item it : forCourse(courseName)) {
            if (!it.done) out.add(it);
        }
        return out;
    }

    /** 返回 规范化课名 → 未完成待办数量（供课表卡片绘制角标）。 */
    public Map<String, Integer> pendingCounts() {
        Map<String, Integer> map = new HashMap<>();
        for (Item it : items) {
            if (it.done || it.courseKey.isEmpty()) continue;
            Integer cur = map.get(it.courseKey);
            map.put(it.courseKey, cur == null ? 1 : cur + 1);
        }
        return map;
    }

    /** 课前提醒附加的待办摘要；无未完成待办时返回空串。 */
    public String reminderSummary(String courseName) {
        List<Item> pending = new ArrayList<>();
        for (Item it : pendingForCourse(courseName)) {
            if (it.remindBeforeClass) pending.add(it);
        }
        if (pending.isEmpty()) return "";
        Item first = pending.get(0);
        String text = first.title + (first.dueDate.isEmpty() ? "" : "（" + first.dueDate + "）");
        if (pending.size() == 1) return "待办：" + text;
        return "待办：" + text + " 等 " + pending.size() + " 项";
    }

    public JSONObject toJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", SCHEMA_VERSION);
        JSONArray arr = new JSONArray();
        for (Item it : items) arr.put(it.toJson());
        root.put("items", arr);
        return root;
    }

    public static CourseTodo fromJson(JSONObject root) throws JSONException {
        CourseTodo book = new CourseTodo();
        if (root == null) return book;
        int ver = root.optInt("schemaVersion", 1);
        if (ver > SCHEMA_VERSION) {
            throw new JSONException("待办数据版本过高，请升级 App");
        }
        JSONArray arr = root.optJSONArray("items");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    Item it = Item.fromJson(o);
                    if (!it.title.isEmpty()) book.items.add(it);
                }
            }
        }
        return book;
    }

    public static CourseTodo fromJson(String json) throws JSONException {
        if (json == null || json.trim().isEmpty()) return new CourseTodo();
        return fromJson(new JSONObject(json));
    }
}
