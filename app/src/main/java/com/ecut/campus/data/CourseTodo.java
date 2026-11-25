package com.ecut.campus.data;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 课程作业与待办（纯 Java，无 Android 依赖）：
 * 按规范化课程名关联（与配色/缺勤规则一致），重新同步教务课表后不会丢失；
 * 支持截止日期、备注、图片附件文件名、截止提醒调度与小组件临近截止提示。
 */
public final class CourseTodo {

    public static final int SCHEMA_VERSION = 1;
    public static final long ONE_DAY_MS = 24L * 60 * 60 * 1000;

    public static final class Item {
        public String id = "";
        public String courseKey = "";
        public String courseName = "";
        public String title = "";
        public String note = "";
        public String dueDate = "";
        public final List<String> photos = new ArrayList<>();
        public boolean done = false;
        public boolean remindBeforeClass = true;
        public long createdAt = 0L;

        public JSONObject toJson() throws JSONException {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("courseKey", courseKey);
            o.put("courseName", courseName);
            o.put("title", title);
            if (note != null && !note.isEmpty()) o.put("note", note);
            if (dueDate != null && !dueDate.isEmpty()) o.put("dueDate", dueDate);
            if (!photos.isEmpty()) {
                JSONArray arr = new JSONArray();
                for (String p : photos) if (p != null && !p.isEmpty()) arr.put(p);
                if (arr.length() > 0) o.put("photos", arr);
            }
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
            it.note = o.optString("note", "");
            it.dueDate = o.optString("dueDate", "");
            JSONArray ph = o.optJSONArray("photos");
            if (ph != null) {
                for (int i = 0; i < ph.length(); i++) {
                    String f = ph.optString(i, "").trim();
                    if (!f.isEmpty()) it.photos.add(f);
                }
            }
            it.done = o.optBoolean("done", false);
            it.remindBeforeClass = o.optBoolean("remindBeforeClass", true);
            it.createdAt = o.optLong("createdAt", 0L);
            if (it.courseKey.isEmpty() && !it.courseName.isEmpty()) {
                it.courseKey = normalizeCourseKey(it.courseName);
            }
            return it;
        }
    }

    public static final class DeadlineTrigger {
        public final Item item;
        public final long fireAtMillis;
        public final String leadLabel;

        public DeadlineTrigger(Item item, long fireAtMillis, String leadLabel) {
            this.item = item;
            this.fireAtMillis = fireAtMillis;
            this.leadLabel = leadLabel;
        }
    }

    public final List<Item> items = new ArrayList<>();

    public static String normalizeCourseKey(String courseName) {
        return CourseColorAssigner.normalizeName(courseName);
    }

    public Item add(String courseName, String title, String dueDate) {
        return add(courseName, title, dueDate, "", null);
    }

    public Item add(String courseName, String title, String dueDate, String note, List<String> photoFiles) {
        String cleanTitle = title == null ? "" : title.trim();
        if (cleanTitle.isEmpty()) return null;
        Item it = new Item();
        it.id = "td_" + UUID.randomUUID().toString().substring(0, 8);
        it.courseName = courseName == null ? "" : courseName.trim();
        it.courseKey = normalizeCourseKey(it.courseName);
        it.title = cleanTitle;
        it.note = note == null ? "" : note.trim();
        it.dueDate = dueDate == null ? "" : dueDate.trim();
        if (photoFiles != null) {
            for (String p : photoFiles) {
                if (p != null && !p.trim().isEmpty()) it.photos.add(p.trim());
            }
        }
        it.done = false;
        it.remindBeforeClass = true;
        it.createdAt = System.currentTimeMillis();
        items.add(it);
        return it;
    }

    public Item findById(String id) {
        if (id == null || id.isEmpty()) return null;
        for (Item it : items) if (id.equals(it.id)) return it;
        return null;
    }

    public boolean addPhoto(String id, String filename) {
        if (filename == null || filename.trim().isEmpty()) return false;
        Item it = findById(id);
        if (it == null) return false;
        String clean = filename.trim();
        if (!it.photos.contains(clean)) it.photos.add(clean);
        return true;
    }

    public boolean removePhoto(String id, String filename) {
        Item it = findById(id);
        return it != null && it.photos.remove(filename);
    }

    public boolean toggle(String id) {
        Item it = findById(id);
        if (it == null) return false;
        it.done = !it.done;
        return true;
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
        // 未完成排前面；有截止日期的按日期升序，无截止日期按创建时间排序
        out.sort((a, b) -> {
            if (a.done != b.done) return a.done ? 1 : -1;
            long da = parseDueDayStart(a.dueDate);
            long db = parseDueDayStart(b.dueDate);
            if (da > 0 && db > 0 && da != db) return Long.compare(da, db);
            if (da > 0 != db > 0) return da > 0 ? -1 : 1;
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

    /** 解析 yyyy-MM-dd 为本地当天 00:00 毫秒数；无效返回 -1。 */
    public static long parseDueDayStart(String dueDate) {
        if (!Dates.valid(dueDate)) return -1L;
        String[] p = dueDate.split("-");
        int y = Integer.parseInt(p[0]);
        int m = Integer.parseInt(p[1]);
        int d = Integer.parseInt(p[2]);
        Calendar cal = Calendar.getInstance();
        cal.clear();
        cal.set(y, m - 1, d, 0, 0, 0);
        return cal.getTimeInMillis();
    }

    private static long dayStart(long ms) {
        Calendar cal = Calendar.getInstance();
        cal.setTimeInMillis(ms);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        return cal.getTimeInMillis();
    }

    /** 截止日期相对今天的天数差（0=今天，1=明天，-1=昨天）；非标准日期返回 Integer.MAX_VALUE。 */
    public static int daysUntilDue(Item item, long nowMs) {
        if (item == null) return Integer.MAX_VALUE;
        long due = parseDueDayStart(item.dueDate);
        if (due < 0) return Integer.MAX_VALUE;
        long today = dayStart(nowMs);
        return (int) Math.round((due - today) / (double) ONE_DAY_MS);
    }

    /** 人类可读截止标签：已逾期 / 今天截止 / 明天截止 / 剩 N 天 / 原文。 */
    public static String dueLabel(Item item, long nowMs) {
        if (item == null || item.dueDate == null || item.dueDate.isEmpty()) return "";
        int days = daysUntilDue(item, nowMs);
        if (days == Integer.MAX_VALUE) return item.dueDate;
        if (days < 0) return "已逾期 " + (-days) + " 天";
        if (days == 0) return "今天截止";
        if (days == 1) return "明天截止";
        return "剩 " + days + " 天（" + Dates.md(item.dueDate) + "）";
    }

    /** 找出下一次应触发的作业截止提醒（截止前一天 20:00 与截止当天 08:30）。 */
    public DeadlineTrigger nextDeadlineReminder(long nowMs) {
        DeadlineTrigger best = null;
        for (Item it : items) {
            if (it.done) continue;
            long dueDay = parseDueDayStart(it.dueDate);
            if (dueDay < 0) continue;
            long eveBefore = dueDay - 4L * 60 * 60 * 1000; // 前一天 20:00
            long morningOf = dueDay + 8L * 60 * 60 * 1000 + 30L * 60 * 1000; // 当天 08:30
            if (eveBefore > nowMs && (best == null || eveBefore < best.fireAtMillis)) {
                best = new DeadlineTrigger(it, eveBefore, "明天截止");
            }
            if (morningOf > nowMs && (best == null || morningOf < best.fireAtMillis)) {
                best = new DeadlineTrigger(it, morningOf, "今天截止");
            }
        }
        return best;
    }

    /**
     * 小组件临近截止作业短提示（withinDays 天内到期或今天逾期）。
     * 无临近作业返回空串。
     */
    public String urgentWidgetHint(long nowMs, int withinDays) {
        List<Item> urgent = new ArrayList<>();
        for (Item it : items) {
            if (it.done) continue;
            int d = daysUntilDue(it, nowMs);
            if (d <= withinDays && d >= -1) urgent.add(it);
        }
        if (urgent.isEmpty()) return "";
        urgent.sort((a, b) -> Integer.compare(daysUntilDue(a, nowMs), daysUntilDue(b, nowMs)));
        Item first = urgent.get(0);
        int d = daysUntilDue(first, nowMs);
        String tag = d < 0 ? "已逾期" : (d == 0 ? "今天截止" : (d == 1 ? "明天截止" : "剩" + d + "天"));
        String core = "作业 · " + first.title + "（" + tag + "）";
        return urgent.size() == 1 ? core : core + " +" + (urgent.size() - 1);
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
