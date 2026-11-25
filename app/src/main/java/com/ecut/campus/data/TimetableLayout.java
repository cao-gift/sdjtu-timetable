package com.ecut.campus.data;

import com.ecut.campus.ScheduleSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 课表网格布局与交互数学（纯 Java，无 Android 依赖，便于 JVM 单元测试）。
 * 涵盖节次坐标换算、平滑时间线位置、重叠课程分簇分列、块内文本行数规划与翻页/回弹数学。
 */
public final class TimetableLayout {

    /** 下课前多少分钟进入「即将下课」淡提示状态。 */
    public static final int ENDING_SOON_MINUTES = 5;

    private TimetableLayout() {}

    public static int totalSections(ScheduleSettings schedule) {
        return schedule == null ? ScheduleSettings.DEFAULT_SECTION_COUNT : schedule.sectionCount();
    }

    /**
     * 时段在网格里的起止位置，单位「节」（第 1 节顶部为 0）。
     * 普通课按真实节次；自定义时间按作息表换算。
     */
    public static float[] position(Timetable.Session s, ScheduleSettings schedule) {
        int total = totalSections(schedule);
        if (s.hasCustomTime()) {
            float a = minutesToPos(Timetable.Session.minutesOf(s.timeStart), schedule);
            float b = minutesToPos(Timetable.Session.minutesOf(s.timeEnd), schedule);
            if (b - a < 0.35f) b = Math.min(total, a + 0.35f);
            return new float[]{a, b};
        }
        int st = Math.max(1, Math.min(total, s.startSection));
        int en = Math.max(st, Math.min(total, s.endSection));
        return new float[]{st - 1, en};
    }

    /** 某个时刻落在网格的哪里：节内按比例，课间落在相邻边界上（供课块起止对齐）。 */
    public static float minutesToPos(float m, ScheduleSettings schedule) {
        ScheduleSettings sched = schedule == null ? ScheduleSettings.createDefault() : schedule;
        int total = sched.sectionCount();
        for (int sec = 1; sec <= total; sec++) {
            int st = sched.startMinutes(sec);
            int en = sched.endMinutes(sec);
            if (m <= st) return sec - 1;
            if (m <= en) return sec - 1 + (m - st) / (float) Math.max(1, en - st);
        }
        return total;
    }

    /**
     * 「现在线」在网格里的平滑位置（单位：节，支持带秒的小数分钟）。
     * 在课内随时间线性前进；在两节课课间轻微贴近边界、不突兀跳变；早于首节或晚于末节返回 -1。
     */
    public static float smoothNowPos(float nowMinutesFloat, ScheduleSettings schedule) {
        ScheduleSettings sched = schedule == null ? ScheduleSettings.createDefault() : schedule;
        int total = sched.sectionCount();
        if (total <= 0) return -1f;
        int firstStart = sched.startMinutes(1);
        int lastEnd = sched.endMinutes(total);
        if (nowMinutesFloat < firstStart - 30f || nowMinutesFloat > lastEnd + 15f) return -1f;
        if (nowMinutesFloat <= firstStart) return 0f;
        if (nowMinutesFloat >= lastEnd) return total;
        for (int sec = 1; sec <= total; sec++) {
            int st = sched.startMinutes(sec);
            int en = sched.endMinutes(sec);
            if (nowMinutesFloat <= en) {
                if (nowMinutesFloat >= st) {
                    return (sec - 1) + (nowMinutesFloat - st) / (float) Math.max(1, en - st);
                }
                // 课间：上一节结束到本节开始之间平滑微移，避免跳变
                if (sec == 1) return 0f;
                int prevEnd = sched.endMinutes(sec - 1);
                float ratio = (nowMinutesFloat - prevEnd) / (float) Math.max(1, st - prevEnd);
                // 课间保持在 (sec - 1) 边界微幅过渡 [-0.04, 0]
                return (sec - 1) - 0.04f * (1f - Math.max(0f, Math.min(1f, ratio)));
            }
        }
        return total;
    }

    /** 同一天的时段按重叠分簇，簇内贪心分列；laneOf[s] = {第几列, 共几列}。 */
    public static void assignLanes(List<Timetable.Session> list,
                                   ScheduleSettings schedule,
                                   Map<Timetable.Session, int[]> laneOf) {
        if (list == null || list.isEmpty()) return;
        List<Timetable.Session> sorted = new ArrayList<>(list);
        sorted.sort((x, y) -> Float.compare(position(x, schedule)[0], position(y, schedule)[0]));
        List<Timetable.Session> cluster = new ArrayList<>();
        List<Float> laneEnds = new ArrayList<>();
        float clusterEnd = -1f;
        for (Timetable.Session s : sorted) {
            float[] p = position(s, schedule);
            if (!cluster.isEmpty() && p[0] >= clusterEnd - 0.01f) {
                for (Timetable.Session c : cluster) laneOf.get(c)[1] = laneEnds.size();
                cluster.clear();
                laneEnds.clear();
            }
            int lane = -1;
            for (int i = 0; i < laneEnds.size(); i++) {
                if (laneEnds.get(i) <= p[0] + 0.01f) {
                    lane = i;
                    break;
                }
            }
            if (lane < 0) {
                lane = laneEnds.size();
                laneEnds.add(p[1]);
            } else {
                laneEnds.set(lane, p[1]);
            }
            laneOf.put(s, new int[]{lane, 1});
            cluster.add(s);
            clusterEnd = Math.max(clusterEnd, p[1]);
        }
        for (Timetable.Session c : cluster) laneOf.get(c)[1] = laneEnds.size();
    }

    public static boolean overlapsActive(Timetable.Session s, List<Timetable.Session> active, ScheduleSettings schedule) {
        float[] pos = position(s, schedule);
        for (Timetable.Session a : active) {
            if (a == s) continue;
            float[] p = position(a, schedule);
            if (p[0] < pos[1] - 0.01f && pos[0] < p[1] - 0.01f) return true;
        }
        return false;
    }

    /** 正在上这节课时返回已上比例 0.02..1.0，否则返回 -1。 */
    public static float liveProgressOf(float now, int start, int end) {
        if (end <= start || now < start || now >= end) return -1f;
        return Math.max(0.02f, Math.min(1f, (now - start) / (float) (end - start)));
    }

    /** 是否处于「下课前 5 分钟」淡淡提示窗口。 */
    public static boolean isEndingSoon(float now, int start, int end) {
        if (end <= start || now < start || now >= end) return false;
        float left = end - now;
        return left > 0f && left <= ENDING_SOON_MINUTES;
    }

    /**
     * 课块里放哪些行。titleCum[i] = 课程名前 i+1 行的高度；items 按优先级排在课程名之后
     * （0 = 没有这一行），依次是 教室、自定义时间、教师、标签。
     */
    public static int[] blockPlan(float avail, float[] titleCum, float gap, float... items) {
        int n = Math.max(1, titleCum.length);
        float room = items.length > 0 && items[0] > 0f ? gap + items[0] : 0f;
        while (n > 1 && titleCum[n - 1] + room > avail) n--;
        float th = titleCum.length == 0 ? 0f : titleCum[n - 1];
        int mask = 0;
        if (room > 0f && th + room <= avail) mask |= 1;
        float used = th + ((mask & 1) != 0 ? room : 0f);
        for (int i = 1; i < items.length; i++) {
            if (items[i] > 0f && used + gap + items[i] <= avail) {
                mask |= 1 << i;
                used += gap + items[i];
            }
        }
        return new int[]{n, mask};
    }

    public static long pageAnimatorDuration(float distance, float pageWidth) {
        if (distance <= 0f || pageWidth <= 0f) return 0L;
        float fraction = Math.max(0f, Math.min(1f, distance / pageWidth));
        return Math.round(140L + 120L * Math.sqrt(fraction));
    }

    public static boolean shouldCommitPage(float dx, float velocityX, float pageWidth) {
        if (dx == 0f || pageWidth <= 0f) return false;
        int direction = dx < 0f ? -1 : 1;
        float projected = dx + velocityX * 0.11f;
        boolean sameDirection = projected != 0f && Math.signum(projected) == direction;
        return sameDirection && Math.abs(projected) >= pageWidth * 0.22f;
    }

    /** 纵向越界拖动阻尼。 */
    public static float rubberBand(float scrollOffset, float maxScrollOffset, float delta, float limit) {
        float over = scrollOffset < 0 ? -scrollOffset
                : (scrollOffset > maxScrollOffset ? scrollOffset - maxScrollOffset : 0f);
        boolean outward = (scrollOffset < 0 && delta < 0) || (scrollOffset > maxScrollOffset && delta > 0);
        if (!outward && over == 0f) {
            float next = scrollOffset + delta;
            if (next < 0) return -scrollOffset + next * 0.45f;
            if (next > maxScrollOffset) return (maxScrollOffset - scrollOffset) + (next - maxScrollOffset) * 0.45f;
            return delta;
        }
        if (!outward) return delta;
        float safeLimit = Math.max(1f, limit);
        float k = 0.45f * Math.max(0.05f, 1f - over / safeLimit);
        return delta * k;
    }
}
