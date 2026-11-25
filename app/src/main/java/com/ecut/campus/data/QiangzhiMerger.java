package com.ecut.campus.data;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 强智重叠课程与按周换老师合并逻辑（从 QiangzhiSource 拆出，纯 Java）。 */
final class QiangzhiMerger {

    private QiangzhiMerger() {}

    /** 合并同课名和课号在同节次、同一天且周次重叠的上课安排。 */
    static int mergeOverlaps(List<Timetable.Course> courses) {
        int merged = 0;
        for (int i = 0; i < courses.size(); i++) {
            Timetable.Course a = courses.get(i);
            for (int j = i; j < courses.size(); j++) {
                Timetable.Course b = courses.get(j);
                if (!a.name.equals(b.name) || !a.code.equals(b.code)) continue;
                for (int si = 0; si < a.sessions.size(); si++) {
                    Timetable.Session x = a.sessions.get(si);
                    int start = (a == b) ? si + 1 : 0;
                    for (int sj = start; sj < b.sessions.size(); sj++) {
                        Timetable.Session y = b.sessions.get(sj);
                        if (x.dayOfWeek != y.dayOfWeek || x.startSection != y.startSection
                                || x.endSection != y.endSection || !intersects(x.weeks, y.weeks)) continue;
                        mergeTeachers(x, y, a.teacher, b.teacher);
                        boolean added = false;
                        for (Integer w : y.weeks) if (!x.weeks.contains(w)) { x.weeks.add(w); added = true; }
                        Collections.sort(x.weeks);
                        if (added && !y.weeksRaw.isEmpty() && !x.weeksRaw.equals(y.weeksRaw)) {
                            x.weeksRaw = x.weeksRaw.isEmpty() ? y.weeksRaw : x.weeksRaw + "," + y.weeksRaw;
                        }
                        x.location = joinDistinct(x.location, y.location, " / ");
                        b.sessions.remove(sj);
                        sj--;
                        merged++;
                    }
                }
            }
        }
        for (int i = courses.size() - 1; i >= 0; i--) {
            if (courses.get(i).sessions.isEmpty()) courses.remove(i);
        }
        return merged;
    }

    private static void mergeTeachers(Timetable.Session x, Timetable.Session y,
                                      String courseTeacherX, String courseTeacherY) {
        String tx = x.teacher.isEmpty() ? courseTeacherX : x.teacher;
        String ty = y.teacher.isEmpty() ? courseTeacherY : y.teacher;
        if (ty == null || ty.isEmpty() || ty.equals(tx)) {
            for (Map.Entry<Integer, String> e : y.weekTeachers.entrySet()) {
                x.weekTeachers.putIfAbsent(e.getKey(), e.getValue());
            }
            return;
        }
        if (y.weeks.size() <= x.weeks.size() || x.weeks.isEmpty()) {
            for (Integer w : y.weeks) x.weekTeachers.put(w, ty);
        } else {
            for (Integer w : x.weeks) x.weekTeachers.putIfAbsent(w, tx);
            x.teacher = ty;
        }
        for (Map.Entry<Integer, String> e : y.weekTeachers.entrySet()) {
            x.weekTeachers.put(e.getKey(), e.getValue());
        }
    }

    private static boolean intersects(List<Integer> a, List<Integer> b) {
        for (Integer w : a) if (b.contains(w)) return true;
        return false;
    }

    private static String joinDistinct(String a, String b, String sep) {
        if (b == null || b.isEmpty()) return a == null ? "" : a;
        if (a == null || a.isEmpty()) return b;
        for (String p : a.split(Pattern.quote(sep))) if (p.equals(b)) return a;
        return a + sep + b;
    }
}
