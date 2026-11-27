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
    /** 连续两节（或两段）课之间允许合并为连堂块的最大课间分钟数（排除午休/晚饭长休）。 */
    public static final int MAX_CONSECUTIVE_BREAK_MINUTES = 45;

    /** 课表展示块：可将同一天、同课程、同教室、同教师的相邻连堂时段合为一个连续块展示。 */
    public static final class DisplayBlock {
        public final Timetable.Course course;
        public final Timetable.Session displaySession;
        public final Timetable.Session primarySession;

        public DisplayBlock(Timetable.Course course, Timetable.Session displaySession,
                            Timetable.Session primarySession) {
            this.course = course;
            this.displaySession = displaySession;
            this.primarySession = primarySession;
        }
    }

    /**
     * 将同一天的有效课程中、相邻连堂的同课块合并（例如 1-2 节与 3-4 节同课同教室同教师合为 1-4 节）。
     * 跨午休/晚休（课间超过 45 分钟）、不同教室、不同教师或自定义时间段不合并；不修改原始 Timetable 数据。
     */
    public static List<DisplayBlock> mergeConsecutiveActive(List<DisplayBlock> raw, int week,
                                                            ScheduleSettings schedule) {
        List<DisplayBlock> out = new ArrayList<>();
        if (raw == null || raw.isEmpty()) return out;
        List<DisplayBlock> sorted = new ArrayList<>(raw);
        sorted.sort((a, b) -> {
            float[] pa = position(a.displaySession, schedule);
            float[] pb = position(b.displaySession, schedule);
            int cmp = Float.compare(pa[0], pb[0]);
            return cmp != 0 ? cmp : Float.compare(pa[1], pb[1]);
        });
        for (DisplayBlock item : sorted) {
            Timetable.Session cur = item.displaySession;
            if (cur.hasCustomTime()) {
                out.add(item);
                continue;
            }
            DisplayBlock target = null;
            for (int i = out.size() - 1; i >= 0; i--) {
                DisplayBlock prev = out.get(i);
                Timetable.Session ps = prev.displaySession;
                if (ps.hasCustomTime()) continue;
                if (ps.dayOfWeek != cur.dayOfWeek) continue;
                if (ps.endSection + 1 != cur.startSection) continue;
                if (!canMerge(prev.course, ps, item.course, cur, week, schedule)) continue;
                target = prev;
                break;
            }
            if (target == null) {
                out.add(item);
            } else {
                Timetable.Session merged = copySessionForDisplay(target.displaySession, cur.endSection);
                int idx = out.indexOf(target);
                out.set(idx, new DisplayBlock(target.course, merged, target.primarySession));
            }
        }
        return out;
    }

    private static boolean canMerge(Timetable.Course c1, Timetable.Session s1,
                                    Timetable.Course c2, Timetable.Session s2,
                                    int week, ScheduleSettings schedule) {
        if (c1 != c2) {
            String n1 = norm(c1 == null ? null : c1.name);
            String n2 = norm(c2 == null ? null : c2.name);
            if (n1.isEmpty() || !n1.equals(n2)) return false;
            if (c1.colorIndex != c2.colorIndex) return false;
            if (!norm(c1.code).equals(norm(c2.code))) return false;
        }
        if (!norm(s1.location).equals(norm(s2.location))) return false;
        String t1 = norm(s1.teacherFor(week, c1 == null ? "" : c1.teacher));
        String t2 = norm(s2.teacherFor(week, c2 == null ? "" : c2.teacher));
        if (!t1.equals(t2)) return false;
        if (schedule != null) {
            int gap = schedule.startMinutes(s2.startSection) - schedule.endMinutes(s1.endSection);
            if (gap > MAX_CONSECUTIVE_BREAK_MINUTES) return false;
        }
        return true;
    }

    private static Timetable.Session copySessionForDisplay(Timetable.Session base, int newEndSection) {
        Timetable.Session s = base.copy();
        s.endSection = Math.max(base.endSection, newEndSection);
        return s;
    }

    private static String norm(String s) {
        return s == null ? "" : s.trim();
    }

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

    /**
     * 重修/选课时间冲突错层叠卡规划结果：
     * 取代原先把七天窄列再横切 1/2 导致课名竖排成单字条的做法，保持每门课接近全列宽度以正常横排阅读，
     * 并为底层课计算未被上层叠卡遮挡的文字区，保证每门冲突课的标题/教室都清晰可见。
     */
    public static final class OverlapCard {
        public final float visualStart;
        public final float visualEnd;
        public final float textStart;
        public final float textEnd;
        public final int depth;
        public final int clusterSize;
        public final int clusterId;

        public OverlapCard(float visualStart, float visualEnd, float textStart, float textEnd,
                           int depth, int clusterSize, int clusterId) {
            this.visualStart = visualStart;
            this.visualEnd = visualEnd;
            this.textStart = textStart;
            this.textEnd = textEnd;
            this.depth = depth;
            this.clusterSize = clusterSize;
            this.clusterId = clusterId;
        }
    }

    /**
     * 为同一天的有效时段规划错层叠卡布局（纯函数，按绘制顺序返回排序后的时段列表并写入 out）。
     * - 无冲突课程：depth=0, clusterSize=1，保持原始起止。
     * - 部分重叠（如 1-4 节与 3-4 节、1-4 节与 1-2 节、1-3 节与 2-4 节）：长课垫底（保留完整起止与左缘色条）、
     *   短课微缩叠在上方，底层课文字自动排进未遮挡时段（如 1-2 节或 3-4 节），两门课均拥有充裕宽高。
     * - 完全同时段或多门全覆盖（如两门课同为 7-8 节）：自动按阶梯切分文字与叠卡区，每门课均保持横向全宽可读。
     */
    public static List<Timetable.Session> planOverlapDeck(List<Timetable.Session> list,
                                                          ScheduleSettings schedule,
                                                          Map<Timetable.Session, OverlapCard> out) {
        List<Timetable.Session> drawOrder = new ArrayList<>();
        if (list == null || list.isEmpty()) return drawOrder;

        List<Timetable.Session> sorted = new ArrayList<>(list);
        sorted.sort((x, y) -> {
            float[] px = position(x, schedule);
            float[] py = position(y, schedule);
            int cmp = Float.compare(px[0], py[0]);
            return cmp != 0 ? cmp : Float.compare(py[1], px[1]);
        });

        List<List<Timetable.Session>> clusters = new ArrayList<>();
        List<Timetable.Session> curCluster = new ArrayList<>();
        float curEnd = -1f;
        for (Timetable.Session s : sorted) {
            float[] p = position(s, schedule);
            if (!curCluster.isEmpty() && p[0] >= curEnd - 0.01f) {
                clusters.add(curCluster);
                curCluster = new ArrayList<>();
                curEnd = -1f;
            }
            curCluster.add(s);
            curEnd = Math.max(curEnd, p[1]);
        }
        if (!curCluster.isEmpty()) clusters.add(curCluster);

        int nextClusterId = 1;
        for (List<Timetable.Session> cl : clusters) {
            if (cl.size() == 1) {
                Timetable.Session s = cl.get(0);
                float[] p = position(s, schedule);
                out.put(s, new OverlapCard(p[0], p[1], p[0], p[1], 0, 1, 0));
                drawOrder.add(s);
                continue;
            }

            int cid = nextClusterId++;
            int n = cl.size();
            // 尝试模式 A：长时段垫底、短时段在顶，检查每张卡是否都有 >= 0.85 节的未遮挡连续文字区
            List<Timetable.Session> bySpanDesc = new ArrayList<>(cl);
            bySpanDesc.sort((a, b) -> {
                float[] pa = position(a, schedule);
                float[] pb = position(b, schedule);
                int lenCmp = Float.compare(pb[1] - pb[0], pa[1] - pa[0]);
                return lenCmp != 0 ? lenCmp : Float.compare(pa[0], pb[0]);
            });

            float[][] rawPos = new float[n][];
            for (int i = 0; i < n; i++) rawPos[i] = position(bySpanDesc.get(i), schedule);

            float[][] uncovered = new float[n][];
            boolean naturalFits = true;
            for (int i = 0; i < n; i++) {
                List<float[]> upper = new ArrayList<>();
                for (int j = i + 1; j < n; j++) upper.add(rawPos[j]);
                float[] best = longestUncoveredSegment(rawPos[i][0], rawPos[i][1], upper);
                if (best[1] - best[0] < 0.85f) {
                    naturalFits = false;
                    break;
                }
                uncovered[i] = best;
            }

            if (naturalFits) {
                for (int i = 0; i < n; i++) {
                    Timetable.Session s = bySpanDesc.get(i);
                    out.put(s, new OverlapCard(rawPos[i][0], rawPos[i][1],
                            uncovered[i][0], uncovered[i][1], i, n, cid));
                    drawOrder.add(s);
                }
            } else {
                // 模式 B：阶梯叠卡切分（如两门课同为 7-8 节），底层延伸至簇底以体现完整节次，每层获得等高文字区
                float cMin = Float.MAX_VALUE, cMax = -Float.MAX_VALUE;
                for (Timetable.Session s : cl) {
                    float[] p = position(s, schedule);
                    cMin = Math.min(cMin, p[0]);
                    cMax = Math.max(cMax, p[1]);
                }
                float span = Math.max(0.5f, cMax - cMin);
                float slice = span / n;
                for (int i = 0; i < n; i++) {
                    Timetable.Session s = cl.get(i);
                    float t0 = cMin + i * slice;
                    float t1 = (i == n - 1) ? cMax : (cMin + (i + 1) * slice);
                    out.put(s, new OverlapCard(t0, cMax, t0, t1, i, n, cid));
                    drawOrder.add(s);
                }
            }
        }
        return drawOrder;
    }

    private static float[] longestUncoveredSegment(float start, float end, List<float[]> covers) {
        if (covers == null || covers.isEmpty()) return new float[]{start, end};
        List<float[]> clipped = new ArrayList<>();
        for (float[] c : covers) {
            float a = Math.max(start, c[0]);
            float b = Math.min(end, c[1]);
            if (b > a + 0.005f) clipped.add(new float[]{a, b});
        }
        if (clipped.isEmpty()) return new float[]{start, end};
        clipped.sort((x, y) -> Float.compare(x[0], y[0]));

        float bestStart = start, bestEnd = start;
        float cursor = start;
        for (float[] c : clipped) {
            if (c[0] > cursor + 0.005f) {
                if (c[0] - cursor > bestEnd - bestStart) {
                    bestStart = cursor;
                    bestEnd = c[0];
                }
            }
            cursor = Math.max(cursor, c[1]);
        }
        if (end > cursor + 0.005f && end - cursor > bestEnd - bestStart) {
            bestStart = cursor;
            bestEnd = end;
        }
        return new float[]{bestStart, bestEnd};
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
