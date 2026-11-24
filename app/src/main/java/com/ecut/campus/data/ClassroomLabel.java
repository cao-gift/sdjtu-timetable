package com.ecut.campus.data;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** 只在课表卡片上简化地点。 */
public final class ClassroomLabel {
    private static final Pattern PHRASE_SEPARATOR = Pattern.compile("[\\r\\n,，、]+");
    private static final Pattern EDGE_SPACE = Pattern.compile("^[\\s\\p{Z}]+|[\\s\\p{Z}]+$");

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
}
