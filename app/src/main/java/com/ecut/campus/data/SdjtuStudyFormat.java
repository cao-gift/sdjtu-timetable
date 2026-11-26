package com.ecut.campus.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 交院查空教室与培养方案的展示格式化和筛选计算（纯 Java，无 Android 依赖，便于 JVM 单测）。
 */
public final class SdjtuStudyFormat {

    private SdjtuStudyFormat() {}

    public static final String DEFAULT_CAMPUS_HINT = "无影山校区";
    public static final String DEFAULT_BUILDING_HINT = "无影山1号教学楼";

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
}
