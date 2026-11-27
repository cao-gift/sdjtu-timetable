package com.ecut.campus.data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 重新同步教务后，课表和上次相比变了什么（只看教务课程）。 */
public final class SyncDiff {

    private SyncDiff() {}

    /** 一句话一行；没变化或第一次同步返回空串。 */
    public static String summarize(Timetable before, Timetable after) {
        if (before == null || after == null) return "";
        Map<String, Timetable.Course> old = byName(before), now = byName(after);
        if (old.isEmpty()) return "";
        List<String> lines = new ArrayList<>();
        List<String> added = new ArrayList<>(), removed = new ArrayList<>();
        for (String n : now.keySet()) if (!old.containsKey(n)) added.add(n);
        for (String n : old.keySet()) if (!now.containsKey(n)) removed.add(n);
        if (!added.isEmpty()) lines.add("新增：" + String.join("、", added));
        if (!removed.isEmpty()) lines.add("教务已取消：" + String.join("、", removed));
        for (Map.Entry<String, Timetable.Course> e : now.entrySet()) {
            Timetable.Course o = old.get(e.getKey());
            if (o == null) continue;
            Timetable.Course c = e.getValue();
            String oldRooms = rooms(o), newRooms = rooms(c);
            if (!oldRooms.equals(newRooms)) lines.add(c.name + " 教室：" + oldRooms + " → " + newRooms);
            String oldSlots = slots(o), newSlots = slots(c);
            if (!oldSlots.equals(newSlots)) lines.add(c.name + " 时间：" + oldSlots + " → " + newSlots);
        }
        return String.join("\n", lines);
    }

    /** 变动条目数（按非空行计）。 */
    public static int changeCount(String summary) {
        if (summary == null || summary.trim().isEmpty()) return 0;
        int n = 0;
        for (String line : summary.split("\n")) {
            if (!line.trim().isEmpty()) n++;
        }
        return n;
    }

    /** 摘要首行，用于通知收起态预览。 */
    public static String firstLine(String summary) {
        if (summary == null) return "";
        for (String line : summary.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty()) return t;
        }
        return "";
    }

    private static Map<String, Timetable.Course> byName(Timetable t) {
        Map<String, Timetable.Course> m = new LinkedHashMap<>();
        for (Timetable.Course c : t.courses) {
            if (Timetable.SOURCE_JWGLXT.equals(c.source) && !m.containsKey(c.name)) m.put(c.name, c);
        }
        return m;
    }

    private static String rooms(Timetable.Course c) {
        List<String> r = new ArrayList<>();
        for (Timetable.Session s : c.sessions) if (!s.location.isEmpty() && !r.contains(s.location)) r.add(s.location);
        return r.isEmpty() ? "无" : String.join("/", r);
    }

    private static String slots(Timetable.Course c) {
        List<String> r = new ArrayList<>();
        for (Timetable.Session s : c.sessions) {
            String x = Dates.cnDay(s.dayOfWeek) + s.startSection + "-" + s.endSection + "节 "
                    + WeekRange.weekExpr(s.weeks) + "周";
            if (!r.contains(x)) r.add(x);
        }
        java.util.Collections.sort(r);
        return String.join("，", r);
    }
}
