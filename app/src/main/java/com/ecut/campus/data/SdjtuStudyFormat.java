package com.ecut.campus.data;

import com.ecut.campus.ScheduleSettings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 交院查空教室与培养方案的展示格式化、智能连空推荐与筛选计算（纯 Java，无 Android 依赖，便于 JVM 单测）。
 */
public final class SdjtuStudyFormat {

    private SdjtuStudyFormat() {}

    public static final String DEFAULT_CAMPUS_HINT = "无影山校区";
    public static final String DEFAULT_BUILDING_HINT = "无影山1号教学楼";

    /** 按校区名称给出默认教学楼匹配提示词（未保存该校区常用楼栋时使用）。 */
    public static String defaultBuildingHintForCampus(String campusLabel) {
        String s = campusLabel == null ? "" : campusLabel.trim();
        if (s.isEmpty() || s.contains("无影山")) return DEFAULT_BUILDING_HINT;
        if (s.contains("长清") || s.contains("威海")) return "1号教学楼";
        return "";
    }

    /** 自习好教室连续空闲推荐评估结果。 */
    public static final class StudyRecommendation {
        public static final StudyRecommendation EMPTY = new StudyRecommendation(
                0, 0, 0, false, "", -1, false, false, "", "");

        /** 从基准时段（当前或所选时段）起连续空闲的小节数（如 5-10 节 = 6）。 */
        public final int streakSections;
        /** 从基准时段起连续空闲的时段格数。 */
        public final int streakSlots;
        /** 当天任意时段中最长连续空闲小节数（用于非今日或晚间排优）。 */
        public final int maxAnyStreakSections;
        /** 是否一路连空至当天最后一个时段（直至末节结束不必换座）。 */
        public final boolean untilDayEnd;
        /** 连续空闲持续到的结束时刻（如 "20:35"、"15:00"）。 */
        public final String untilTime;
        /** 基准时段在 periods 列表中的下标（-1 表示非锚定模式）。 */
        public final int anchorSlotIndex;
        /** 是否处于「今日此刻起」实时推荐模式。 */
        public final boolean fromNowMode;
        /** 是否属于优先推荐教室（从基准起连空 >= 2 个大节或直通末节）。 */
        public final boolean recommended;
        /** 卡片右上角紧凑角标（空串时沿用默认 roomBadge）。 */
        public final String badgeOverride;
        /** 卡片内展示的智能推荐说明文案（空串表示不显示）。 */
        public final String hintText;

        public StudyRecommendation(int streakSections, int streakSlots, int maxAnyStreakSections,
                                   boolean untilDayEnd, String untilTime, int anchorSlotIndex,
                                   boolean fromNowMode, boolean recommended,
                                   String badgeOverride, String hintText) {
            this.streakSections = streakSections;
            this.streakSlots = streakSlots;
            this.maxAnyStreakSections = maxAnyStreakSections;
            this.untilDayEnd = untilDayEnd;
            this.untilTime = untilTime == null ? "" : untilTime;
            this.anchorSlotIndex = anchorSlotIndex;
            this.fromNowMode = fromNowMode;
            this.recommended = recommended;
            this.badgeOverride = badgeOverride == null ? "" : badgeOverride;
            this.hintText = hintText == null ? "" : hintText;
        }
    }

    public static final class RoomLabel {
        public final String building;
        public final String room;

        public RoomLabel(String building, String room) {
            this.building = building;
            this.room = room;
        }
    }

    public static final class PeriodSlot {
        public final String period;
        public final String shortLabel;
        public final String state; // free | busy | unknown

        public PeriodSlot(String period, String shortLabel, String state) {
            this.period = period;
            this.shortLabel = shortLabel;
            this.state = state;
        }
    }

    public static final class PlanStats {
        public final int courseCount;
        public final double totalCredit;
        public final int requiredCount;
        public final int termCount;

        public PlanStats(int courseCount, double totalCredit, int requiredCount, int termCount) {
            this.courseCount = courseCount;
            this.totalCredit = totalCredit;
            this.requiredCount = requiredCount;
            this.termCount = termCount;
        }
    }

    /** 将学校返回的多行/带缩进教室名拆分为「教学楼」和「教室号」，去除冗余空白。 */
    public static RoomLabel splitRoomLabel(String rawName, String selectedBuilding) {
        String clean = rawName == null ? "" : rawName.replace('\u00A0', ' ')
                .replaceAll("\\s+", " ").trim();
        String bldHint = selectedBuilding == null ? "" : selectedBuilding.replaceAll("\\s+", " ").trim();
        if (clean.isEmpty()) return new RoomLabel(bldHint, "");

        // 格式 1：「楼名 · 教室号」
        int dot = clean.lastIndexOf("·");
        if (dot > 0 && dot < clean.length() - 1) {
            String left = clean.substring(0, dot).trim();
            String right = clean.substring(dot + 1).trim();
            if (!right.isEmpty()) return new RoomLabel(left, right);
        }

        // 格式 2：以当前所选教学楼名开头（如「无影山1号教学楼 1414」或「无影山1号教学楼1414」）
        if (!bldHint.isEmpty() && clean.startsWith(bldHint) && clean.length() > bldHint.length()) {
            String tail = clean.substring(bldHint.length()).replaceAll("^[-·\\s]+", "").trim();
            if (!tail.isEmpty()) return new RoomLabel(bldHint, tail);
        }

        // 格式 3：包含「教学楼/实验楼/综合楼/实训楼/办公楼/楼」后接房间号
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^(.+?(?:教学楼|实验楼|综合楼|实训楼|办公楼|楼))\\s*[-·]?\\s*(.+)$")
                .matcher(clean);
        if (m.matches()) {
            String left = m.group(1).trim();
            String right = m.group(2).trim();
            if (!right.isEmpty()) return new RoomLabel(left, right);
        }

        return new RoomLabel(bldHint, clean);
    }

    /** 将「第1–2节」「第01、02节」压缩为更易扫读的「1-2节」。 */
    public static String shortPeriodLabel(String period) {
        if (period == null) return "";
        String s = period.replaceAll("\\s+", "");
        if (s.isEmpty()) return "";
        if ("教务查询所选节次".equals(s)) return "所选节次";
        s = s.replaceFirst("^第", "").replaceAll("(?:小节|节次|节)$", "");
        String[] nums = s.split("[-–—~至、,，]+");
        List<Integer> parsed = new ArrayList<>();
        for (String n : nums) {
            if (n.matches("\\d{1,2}")) parsed.add(Integer.parseInt(n));
        }
        if (parsed.size() == 1) return parsed.get(0) + "节";
        if (parsed.size() >= 2) {
            return parsed.get(0) + "-" + parsed.get(parsed.size() - 1) + "节";
        }
        return period.trim();
    }

    /** 读取一间教室当天各节次的空闲/占用列表。 */
    public static List<PeriodSlot> roomPeriods(JSONObject room) {
        List<PeriodSlot> out = new ArrayList<>();
        if (room == null) return out;
        JSONArray arr = room.optJSONArray("periods");
        if (arr == null) return out;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject p = arr.optJSONObject(i);
            if (p == null) continue;
            String label = p.optString("period").trim();
            String state = normalizeState(p.optString("state"));
            if (!label.isEmpty()) {
                out.add(new PeriodSlot(label, shortPeriodLabel(label), state));
            }
        }
        return out;
    }

    /**
     * 计算教室在指定节次下的状态。
     * selectedPeriod 为空时表示「当天任意节次」：只要有至少一节空闲即视作有空闲（free）。
     */
    public static String effectiveState(JSONObject room, String selectedPeriod) {
        if (room == null) return "unknown";
        List<PeriodSlot> slots = roomPeriods(room);
        if (slots.isEmpty()) return normalizeState(room.optString("state"));
        if (selectedPeriod != null && !selectedPeriod.isEmpty()) {
            for (PeriodSlot s : slots) {
                if (selectedPeriod.equals(s.period)) return s.state;
            }
            return normalizeState(room.optString("state"));
        }
        boolean anyFree = false;
        boolean anyUnknown = false;
        for (PeriodSlot s : slots) {
            if ("free".equals(s.state)) anyFree = true;
            else if ("unknown".equals(s.state)) anyUnknown = true;
        }
        if (anyFree) return "free";
        return anyUnknown ? "unknown" : "busy";
    }

    /** 统计一间教室当天空闲节次数。 */
    public static int freePeriodCount(JSONObject room) {
        List<PeriodSlot> slots = roomPeriods(room);
        if (slots.isEmpty()) return "free".equals(normalizeState(room == null ? "" : room.optString("state"))) ? 1 : 0;
        int n = 0;
        for (PeriodSlot s : slots) if ("free".equals(s.state)) n++;
        return n;
    }

    /** 生成教室卡片右上角的简洁状态标签（如「全天空闲」「空 3/5 节」「当日满课」）。 */
    public static String roomBadge(JSONObject room, String selectedPeriod) {
        List<PeriodSlot> slots = roomPeriods(room);
        if (slots.isEmpty()) {
            String st = normalizeState(room == null ? "" : room.optString("state"));
            return "free".equals(st) ? "空闲" : "busy".equals(st) ? "有占用" : "待核实";
        }
        if (selectedPeriod != null && !selectedPeriod.isEmpty()) {
            String st = effectiveState(room, selectedPeriod);
            return "free".equals(st) ? shortPeriodLabel(selectedPeriod) + "空闲"
                    : "busy".equals(st) ? shortPeriodLabel(selectedPeriod) + "有课" : "待核实";
        }
        int free = freePeriodCount(room);
        int total = slots.size();
        if (free == total) return "全天空闲";
        if (free > 0) return "空 " + free + "/" + total + " 节";
        String st = effectiveState(room, "");
        return "unknown".equals(st) ? "待核实" : "当日满课";
    }

    public static String normalizeState(String state) {
        if ("free".equals(state) || "busy".equals(state) || "unknown".equals(state)) return state;
        return "unknown";
    }

    /** 统计培养方案总课程数、总学分、必修门数与开设学期数。 */
    public static PlanStats computePlanStats(JSONArray courses) {
        if (courses == null) return new PlanStats(0, 0.0, 0, 0);
        int count = 0;
        double credit = 0.0;
        int required = 0;
        Set<String> terms = new LinkedHashSet<>();
        for (int i = 0; i < courses.length(); i++) {
            JSONObject c = courses.optJSONObject(i);
            if (c == null) continue;
            count++;
            credit += parseCredit(c.optString("credit"));
            String nature = c.optString("nature") + " " + c.optString("kind");
            if (nature.contains("必修")) required++;
            String t = c.optString("term").trim();
            if (!t.isEmpty()) terms.add(t);
        }
        return new PlanStats(count, Math.round(credit * 100.0) / 100.0, required, terms.size());
    }

    public static double parseCredit(String raw) {
        if (raw == null) return 0.0;
        String s = raw.trim().replaceAll("[^0-9.]", "");
        if (s.isEmpty()) return 0.0;
        try {
            double v = Double.parseDouble(s);
            return Double.isFinite(v) && v >= 0 ? v : 0.0;
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    public static String formatCredit(double credit) {
        double rounded = Math.round(credit * 10.0) / 10.0;
        if (Math.abs(rounded - Math.rint(rounded)) < 0.001) {
            return String.format(Locale.US, "%.0f", rounded);
        }
        return String.format(Locale.US, "%.1f", rounded);
    }

    /** 格式化星期数字（1..7）为「星期一」..「星期日」。 */
    public static String weekdayLabel(int day) {
        if (day < 1 || day > 7) return "";
        return "星期" + "一二三四五六日".charAt(day - 1);
    }

    /** 解析节次文本为 [startSection, endSection]（1-based）；无法解析返回 null。 */
    public static int[] parsePeriodRange(String period) {
        if (period == null) return null;
        String s = period.replaceAll("\\s+", "")
                .replaceFirst("^第", "")
                .replaceAll("(?:小节|节次|节)$", "");
        if (s.isEmpty()) return null;
        String[] parts = s.split("[-–—~至、,，]+");
        int first = -1, last = -1;
        for (String p : parts) {
            if (p.matches("\\d{1,2}")) {
                int v = Integer.parseInt(p);
                if (v >= 1 && v <= ScheduleSettings.MAX_SECTIONS) {
                    if (first < 0) first = v;
                    last = v;
                }
            }
        }
        return first > 0 && last >= first ? new int[]{first, last} : null;
    }

    /** 计算某个节次格涵盖的小节数（如「第5、6节」为 2，「第3节」为 1）。 */
    public static int periodSectionSpan(String period) {
        int[] r = parsePeriodRange(period);
        return r == null ? 1 : (r[1] - r[0] + 1);
    }

    /**
     * 查找当前时刻 nowMinutes（距 00:00 的分钟数）对应的「现在或下一时段」下标。
     * 若某时段剩余时间 <= 5 分钟则自动切至下一时段；当天所有时段结束后返回 -1。
     */
    public static int currentOrNextSlotIndex(List<PeriodSlot> slots, ScheduleSettings schedule, int nowMinutes) {
        if (slots == null || slots.isEmpty() || nowMinutes < 0) return -1;
        ScheduleSettings sched = schedule == null ? ScheduleSettings.createDefault() : schedule;
        int maxSec = sched.sectionCount();
        for (int i = 0; i < slots.size(); i++) {
            int[] range = parsePeriodRange(slots.get(i).period);
            if (range == null) continue;
            int endMin = sched.endMinutes(Math.min(maxSec, range[1]));
            if (nowMinutes < endMin - 5) return i;
        }
        return -1;
    }

    private static String slotStartTime(PeriodSlot slot, ScheduleSettings sched) {
        int[] r = parsePeriodRange(slot.period);
        if (r == null) return "";
        return sched.startTime(Math.min(sched.sectionCount(), r[0]));
    }

    private static String slotEndTime(PeriodSlot slot, ScheduleSettings sched) {
        int[] r = parsePeriodRange(slot.period);
        if (r == null) return "";
        return sched.endTime(Math.min(sched.sectionCount(), r[1]));
    }

    /**
     * 评估教室连续空闲推荐信息：
     * - 当日全部时段且未到末节结束：自动以当前/下一时段为起点，计算「从现在起连空 N 节（直至 HH:mm 不必换座）」；
     * - 选中特定时段筛选：以所选时段为起点计算连空节数；
     * - 查询其他日期或晚间已过末节：计算全天最长连续空闲段，便于次日自习规划。
     */
    public static StudyRecommendation evaluateStudy(JSONObject room, String selectedPeriod,
                                                    boolean isQueryingToday, int nowMinutes,
                                                    ScheduleSettings schedule) {
        List<PeriodSlot> slots = roomPeriods(room);
        if (slots.isEmpty()) return StudyRecommendation.EMPTY;
        ScheduleSettings sched = schedule == null ? ScheduleSettings.createDefault() : schedule;

        // 计算全天任意起点最长连续空闲段（用于次日/晚间排序与提示）
        int maxAnySecs = 0, curSecs = 0;
        int bestStartIdx = -1, bestEndIdx = -1, curStartIdx = -1;
        for (int i = 0; i < slots.size(); i++) {
            if ("free".equals(slots.get(i).state)) {
                if (curStartIdx < 0) curStartIdx = i;
                curSecs += periodSectionSpan(slots.get(i).period);
                if (curSecs > maxAnySecs) {
                    maxAnySecs = curSecs;
                    bestStartIdx = curStartIdx;
                    bestEndIdx = i;
                }
            } else {
                curSecs = 0;
                curStartIdx = -1;
            }
        }

        int anchorIdx = -1;
        boolean fromNow = false;
        if (selectedPeriod != null && !selectedPeriod.isEmpty()) {
            for (int i = 0; i < slots.size(); i++) {
                if (selectedPeriod.equals(slots.get(i).period)) {
                    anchorIdx = i;
                    break;
                }
            }
        } else if (isQueryingToday) {
            anchorIdx = currentOrNextSlotIndex(slots, sched, nowMinutes);
            fromNow = anchorIdx >= 0;
        }

        if (anchorIdx >= 0) {
            PeriodSlot anchor = slots.get(anchorIdx);
            if ("free".equals(anchor.state)) {
                int streakSecs = 0;
                int streakSlots = 0;
                int lastFree = anchorIdx;
                for (int j = anchorIdx; j < slots.size(); j++) {
                    if (!"free".equals(slots.get(j).state)) break;
                    streakSecs += periodSectionSpan(slots.get(j).period);
                    streakSlots++;
                    lastFree = j;
                }
                boolean untilEnd = (lastFree == slots.size() - 1);
                String untilTime = slotEndTime(slots.get(lastFree), sched);
                boolean recommended = untilEnd || streakSlots >= 2;
                String prefix = fromNow ? "从现在起" : "从 " + anchor.shortLabel + "起";
                String hint;
                if (untilEnd) {
                    hint = (recommended ? "推荐 · " : "") + prefix + "连空 " + streakSecs + " 节"
                            + (untilTime.isEmpty() ? "（直至末节不必换座）" : "（直至 " + untilTime + " 不必换座）");
                } else if (streakSlots >= 2) {
                    hint = "推荐 · " + prefix + "连空 " + streakSecs + " 节"
                            + (untilTime.isEmpty() ? "" : "（可自习至 " + untilTime + "）");
                } else {
                    String nextStart = (lastFree + 1 < slots.size()) ? slotStartTime(slots.get(lastFree + 1), sched) : "";
                    String baseLbl = fromNow ? "当前空闲" : anchor.shortLabel + "空闲";
                    hint = baseLbl + (untilTime.isEmpty() ? "" : "至 " + untilTime)
                            + (nextStart.isEmpty() ? "（下节有课）" : "（" + nextStart + " 有课）");
                }
                String badge = "";
                if (fromNow) {
                    badge = untilEnd && !untilTime.isEmpty()
                            ? "连空至 " + untilTime
                            : "连空 " + streakSecs + " 节";
                } else if (streakSlots >= 2) {
                    badge = untilEnd && !untilTime.isEmpty()
                            ? "连空至 " + untilTime
                            : "连空 " + streakSecs + " 节";
                }
                return new StudyRecommendation(streakSecs, streakSlots, maxAnySecs, untilEnd,
                        untilTime, anchorIdx, fromNow, recommended, badge, hint);
            } else {
                // 基准时段有课/待核实：往后找最近连续空闲段
                String hint = "";
                if (fromNow) {
                    int nextFreeIdx = -1;
                    for (int j = anchorIdx + 1; j < slots.size(); j++) {
                        if ("free".equals(slots.get(j).state)) {
                            nextFreeIdx = j;
                            break;
                        }
                    }
                    if (nextFreeIdx >= 0) {
                        int nSecs = 0;
                        int nEnd = nextFreeIdx;
                        for (int j = nextFreeIdx; j < slots.size() && "free".equals(slots.get(j).state); j++) {
                            nSecs += periodSectionSpan(slots.get(j).period);
                            nEnd = j;
                        }
                        String st = slotStartTime(slots.get(nextFreeIdx), sched);
                        String en = slotEndTime(slots.get(nEnd), sched);
                        boolean nUntilEnd = (nEnd == slots.size() - 1);
                        hint = "当前有课 · " + (st.isEmpty() ? slots.get(nextFreeIdx).shortLabel : st)
                                + " 起连空 " + nSecs + " 节"
                                + (nUntilEnd && !en.isEmpty() ? "（至 " + en + "）" : "");
                    } else if ("busy".equals(anchor.state)) {
                        hint = "当前至晚间均有课";
                    }
                }
                String badge = fromNow && "free".equals(effectiveState(room, "")) ? "当前有课" : "";
                return new StudyRecommendation(0, 0, maxAnySecs, false, "", anchorIdx,
                        fromNow, false, badge, hint);
            }
        }

        // 非今日或已过当日末节：按全天最长连续空闲给出辅助提示
        boolean allFree = freePeriodCount(room) == slots.size();
        String lastEnd = slotEndTime(slots.get(slots.size() - 1), sched);
        String hint = "";
        if (allFree && maxAnySecs > 0) {
            hint = "全天连空 " + maxAnySecs + " 节"
                    + (lastEnd.isEmpty() ? "" : "（直至 " + lastEnd + " 不必换座）");
        } else if (maxAnySecs >= 4 && bestStartIdx >= 0 && bestEndIdx >= bestStartIdx) {
            String st = slotStartTime(slots.get(bestStartIdx), sched);
            String en = slotEndTime(slots.get(bestEndIdx), sched);
            String rangeTxt = (!st.isEmpty() && !en.isEmpty()) ? "（" + st + "–" + en + "）" : "";
            hint = "最长连空 " + maxAnySecs + " 节" + rangeTxt;
        }
        return new StudyRecommendation(0, 0, maxAnySecs, allFree, allFree ? lastEnd : "", -1,
                false, allFree, "", hint);
    }
}
