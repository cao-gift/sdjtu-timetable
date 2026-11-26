package com.ecut.campus.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 只在课表卡片上简化地点。 */
public final class ClassroomLabel {
    private static final Pattern PHRASE_SEPARATOR = Pattern.compile("[\\r\\n,，、]+");
    private static final Pattern EDGE_SPACE = Pattern.compile("^[\\s\\p{Z}]+|[\\s\\p{Z}]+$");
    private static final Pattern BUILDING_WITH_ROOM = Pattern.compile(
            "^(.+?)\\s*(?:[A-Za-z]?-?\\d{2,4}[A-Za-z]?室?)$");
    private static final Pattern CAMPUS_PREFIX = Pattern.compile(
            "^(无影山校区|无影山|长清校区|长清|威海校区|威海|东校区|西校区|南校区|北校区)(.{2,})$");

    public static final String DEFAULT_SAMPLE_LOCATION = "无影山1号教学楼1102";

    private ClassroomLabel() {}

    /** 保留用户的输入顺序和词组内部的空格。 */
    public static List<String> parsePhrases(String input) {
        if (input == null || input.isEmpty()) return Collections.emptyList();
        Set<String> phrases = new LinkedHashSet<>();
        for (String entry : PHRASE_SEPARATOR.split(input)) {
            String phrase = EDGE_SPACE.matcher(entry).replaceAll("");
            if (!phrase.isEmpty()) phrases.add(phrase);
        }
        return new ArrayList<>(phrases);
    }

    /** 序列化为按行存储的文本，供 Prefs 持久化。 */
    public static String formatPhrases(List<String> phrases) {
        if (phrases == null || phrases.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (String raw : phrases) {
            if (raw == null) continue;
            String p = EDGE_SPACE.matcher(raw).replaceAll("");
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append('\n');
            sb.append(p);
        }
        return sb.toString();
    }

    /** 向现有词组追加（支持换行/逗号/顿号一次加入多个），自动去重保序。 */
    public static List<String> addPhrases(List<String> current, String input) {
        Set<String> out = new LinkedHashSet<>();
        if (current != null) {
            for (String p : current) {
                if (p != null && !p.isEmpty()) out.add(p);
            }
        }
        out.addAll(parsePhrases(input));
        return new ArrayList<>(out);
    }

    /** 删除指定词组，保留其余顺序。 */
    public static List<String> removePhrase(List<String> current, String target) {
        if (current == null || current.isEmpty()) return Collections.emptyList();
        List<String> out = new ArrayList<>(current.size());
        for (String p : current) {
            if (p != null && !p.equals(target)) out.add(p);
        }
        return out;
    }

    /** 命中自定义词组时保留其余文字，默认显示完整地点。 */
    public static String compact(String location, List<String> phrases) {
        if (location == null) return "";
        if (phrases == null || phrases.isEmpty()) return location;
        StringBuilder remaining = new StringBuilder(location.length());
        for (int offset = 0; offset < location.length();) {
            // 只在原文上匹配，避免删除后拼接出的新词也被删除。
            int longest = 0;
            for (String phrase : phrases) {
                if (phrase != null && phrase.length() > longest && location.startsWith(phrase, offset)) {
                    longest = phrase.length();
                }
            }
            if (longest > 0) offset += longest;
            else remaining.append(location.charAt(offset++));
        }
        if (remaining.length() == location.length()) return location;
        return EDGE_SPACE.matcher(remaining).replaceAll("");
    }

    /**
     * 从课表真实地点中自动提取常见楼宇/校区前缀作为快捷建议（按出现频次排序）。
     */
    public static List<String> suggestPhrases(List<String> locations, int limit) {
        if (limit <= 0 || locations == null || locations.isEmpty()) return Collections.emptyList();
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (String raw : locations) {
            if (raw == null) continue;
            String loc = EDGE_SPACE.matcher(raw).replaceAll("");
            if (loc.length() < 3) continue;

            Matcher m = BUILDING_WITH_ROOM.matcher(loc);
            if (m.matches()) {
                String prefix = EDGE_SPACE.matcher(m.group(1)).replaceAll("");
                if (isUsefulPrefix(prefix)) {
                    scores.put(prefix, scores.getOrDefault(prefix, 0) + 2);
                }
            }

            Matcher cm = CAMPUS_PREFIX.matcher(loc);
            if (cm.matches()) {
                String campus = cm.group(1);
                if (isUsefulPrefix(campus)) {
                    scores.put(campus, scores.getOrDefault(campus, 0) + 1);
                }
            }
        }

        List<Map.Entry<String, Integer>> entries = new ArrayList<>(scores.entrySet());
        entries.sort((a, b) -> {
            int cmp = Integer.compare(b.getValue(), a.getValue());
            if (cmp != 0) return cmp;
            return Integer.compare(b.getKey().length(), a.getKey().length());
        });

        List<String> result = new ArrayList<>(Math.min(limit, entries.size()));
        for (Map.Entry<String, Integer> e : entries) {
            if (result.size() >= limit) break;
            result.add(e.getKey());
        }
        return result;
    }

    /** 挑选一条最适合在设置页展示「原地点 → 简化效果」预览的真实地点。 */
    public static String pickSampleLocation(List<String> locations, List<String> activePhrases) {
        if (locations == null || locations.isEmpty()) return DEFAULT_SAMPLE_LOCATION;
        String longest = "";
        if (activePhrases != null && !activePhrases.isEmpty()) {
            for (String raw : locations) {
                if (raw == null) continue;
                String loc = EDGE_SPACE.matcher(raw).replaceAll("");
                if (loc.isEmpty()) continue;
                String compacted = compact(loc, activePhrases);
                if (!compacted.equals(loc) && !compacted.isEmpty()) {
                    return loc;
                }
            }
        }
        for (String raw : locations) {
            if (raw == null) continue;
            String loc = EDGE_SPACE.matcher(raw).replaceAll("");
            if (loc.length() > longest.length()) longest = loc;
        }
        return longest.isEmpty() ? DEFAULT_SAMPLE_LOCATION : longest;
    }

    private static boolean isUsefulPrefix(String prefix) {
        if (prefix == null || prefix.length() < 2) return false;
        // 至少包含一个汉字或字母，避免纯数字/符号被误识别为前缀
        for (int i = 0; i < prefix.length(); i++) {
            char ch = prefix.charAt(i);
            if (Character.isLetter(ch)) return true;
        }
        return false;
    }
}
