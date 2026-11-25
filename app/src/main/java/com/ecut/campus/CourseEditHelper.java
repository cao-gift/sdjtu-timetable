package com.ecut.campus;

import com.ecut.campus.data.Timetable;
import com.ecut.campus.data.WeekRange;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 课程编辑页纯逻辑辅助（从 CourseEditActivity 拆出）：周次模式识别、表达式生成、自定义时间换算节次。 */
final class CourseEditHelper {

    private CourseEditHelper() {}

    static int detectWeekMode(Timetable.Session s, int storedTotalWeeks) {
        String raw = s.weeksRaw == null ? "" : s.weeksRaw;
        if (raw.contains("单")) return 2;
        if (raw.contains("双")) return 3;
        if (raw.equals("1-" + storedTotalWeeks) || raw.equals("1-" + storedTotalWeeks + "周")) return 0;
        if (raw.contains(",") || raw.contains("，")) return 4;
        if (raw.contains("-")) return 1;
        return 4;
    }

    static String formatWeeksText(int weekMode, Set<Integer> selectedWeeks, int storedTotalWeeks) {
        if (selectedWeeks.isEmpty()) return "";
        if (weekMode == 0 && selectedWeeks.size() >= storedTotalWeeks) {
            return "1-" + storedTotalWeeks;
        }
        if (weekMode == 2) return "1-" + storedTotalWeeks + "周(单)";
        if (weekMode == 3) return "1-" + storedTotalWeeks + "周(双)";
        List<Integer> sorted = new ArrayList<>(selectedWeeks);
        sorted.sort(Integer::compareTo);
        return WeekRange.compress(sorted) + "周";
    }

    static void applyWeeksToSession(Timetable.Session s, int weekMode,
                                    Set<Integer> selectedWeeks, int storedTotalWeeks) {
        List<Integer> sorted = new ArrayList<>(selectedWeeks);
        sorted.sort(Integer::compareTo);
        if (weekMode == 2) {
            s.weeksRaw = "1-" + storedTotalWeeks + "周(单)";
        } else if (weekMode == 3) {
            s.weeksRaw = "1-" + storedTotalWeeks + "周(双)";
        } else {
            s.weeksRaw = WeekRange.compress(sorted);
            if (!s.weeksRaw.isEmpty()) s.weeksRaw += "周";
        }
        s.weeks.clear();
        s.weeks.addAll(sorted);
    }

    /** 将节次或自定义时间写回 Session。 */
    static void applySectionsTo(Timetable.Session s, boolean customTime,
                                String timeStart, String timeEnd,
                                int selectedStart, int selectedEnd,
                                ScheduleSettings sched) {
        int maxSec = sched == null ? ScheduleSettings.DEFAULT_SECTION_COUNT : sched.sectionCount();
        if (customTime && Timetable.Session.minutesOf(timeStart) >= 0
                && Timetable.Session.minutesOf(timeEnd) > Timetable.Session.minutesOf(timeStart)) {
            int a = Timetable.Session.minutesOf(timeStart);
            int b = Timetable.Session.minutesOf(timeEnd);
            int first = maxSec, last = 1;
            for (int sec = 1; sec <= maxSec; sec++) {
                if (sched != null && sched.endMinutes(sec) > a && sched.startMinutes(sec) < b) {
                    first = Math.min(first, sec);
                    last = Math.max(last, sec);
                }
            }
            if (first > last) {
                first = selectedStart;
                last = selectedEnd;
            }
            s.startSection = first;
            s.endSection = last;
            s.timeStart = timeStart;
            s.timeEnd = timeEnd;
        } else {
            s.startSection = selectedStart;
            s.endSection = Math.max(selectedStart, selectedEnd);
            s.timeStart = "";
            s.timeEnd = "";
        }
    }
}
