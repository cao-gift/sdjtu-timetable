package com.ecut.campus.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 强智 HTML 表格与文本提取辅助（从 QiangzhiSource 拆出，保持纯 Java）。 */
final class QiangzhiHtml {

    private static final int FLAGS = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;
    static final Pattern TR = Pattern.compile("<tr\\b[^>]*>(.*?)(?=<tr\\b|</table>|$)", FLAGS);
    private static final Pattern CELL = Pattern.compile("<(td|th)\\b([^>]*)>(.*?)(?=<td\\b|<th\\b|</tr>|$)", FLAGS);
    private static final Pattern DIV = Pattern.compile("<div\\b([^>]*)>(.*?)</div>", FLAGS);
    static final Pattern TITLE_ATTR = Pattern.compile("\\btitle\\s*=\\s*(\"([^\"]*)\"|'([^']*)')", FLAGS);
    private static final Pattern CLASS_ATTR = Pattern.compile("\\bclass\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", FLAGS);
    private static final Pattern ID_ATTR = Pattern.compile("\\bid\\s*=\\s*(\"([^\"]*)\"|'([^']*)'|([^\\s>]+))", FLAGS);
    private static final Pattern COLSPAN = Pattern.compile("\\bcolspan\\s*=\\s*[\"']?(\\d+)", FLAGS);
    static final Pattern VALUE_ATTR = Pattern.compile("\\bvalue\\s*=\\s*[\"']?([^\"'\\s>]+)", FLAGS);
    private static final String[] CN_NUM = {"一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};

    private QiangzhiHtml() {}

    /** 每个元素 {标签, 属性, 内容}。 */
    static List<String[]> cells(String row) {
        List<String[]> out = new ArrayList<>();
        Matcher m = CELL.matcher(row);
        while (m.find()) {
            String body = m.group(3);
            int close = lastIndexOfIgnoreCase(body, "</" + m.group(1).toLowerCase(Locale.ROOT) + ">");
            if (close >= 0) body = body.substring(0, close);
            out.add(new String[]{m.group(1).toLowerCase(Locale.ROOT), m.group(2), body});
        }
        return out;
    }

    static boolean looksLikeHeader(List<String[]> cells) {
        int hits = 0;
        for (String[] c : cells) if (dayFromText(text(c[2])) > 0) hits++;
        return hits >= 5;
    }

    /** 表头列（不含行表头格子）→ 星期。 */
    static int[] headerDays(List<String[]> cells) {
        List<Integer> days = new ArrayList<>();
        boolean first = true;
        for (String[] c : cells) {
            int d = dayFromText(text(c[2]));
            if (first && d == 0) { first = false; continue; }
            first = false;
            int span = colspan(c[1]);
            for (int i = 0; i < span; i++) days.add(d);
        }
        int[] out = new int[days.size()];
        for (int i = 0; i < out.length; i++) out[i] = days.get(i);
        return out;
    }

    static int dayFromText(String t) {
        if (t == null) return 0;
        String s = t.replaceAll("\\s+", "");
        String[] names = {"一", "二", "三", "四", "五", "六"};
        for (int i = 0; i < names.length; i++) {
            if (s.contains("星期" + names[i]) || s.equals("周" + names[i])
                    || s.startsWith("周" + names[i])) return i + 1;
        }
        if (s.contains("星期日") || s.contains("星期天") || s.startsWith("周日") || s.startsWith("周天")) return 7;
        return 0;
    }

    static boolean looksLikeRowLabel(String html) {
        String t = text(html);
        return t.contains("大节") || t.contains("小节") || t.matches("^第?[0-9一二三四五六七八九十]+节.*");
    }

    /** 行表头 → 节次：「0102」「01,02」「1-2节」优先，其次「第三大节」，都没有按行号。 */
    static int[] rowLabelSections(String label, int dataRow) {
        String raw = label == null ? "" : label;
        String t = raw.replaceAll("\\s+", "");
        Matcher pairs = Pattern.compile("(?<![0-9])(\\d{2})(\\d{2})(?![0-9])").matcher(t);
        if (pairs.find()) {
            int a = Integer.parseInt(pairs.group(1)), b = Integer.parseInt(pairs.group(2));
            if (a > 0 && b >= a && b - a < 6) return new int[]{a, b};
        }
        String withoutTimes = raw.replaceAll("\\d{1,2}:\\d{2}", " ").replaceAll("\\s+", "");
        Matcher nums = Pattern.compile("\\d+").matcher(withoutTimes.replaceAll("^第[一二三四五六七八九十]+大节", ""));
        List<Integer> list = new ArrayList<>();
        while (nums.find()) list.add(Integer.parseInt(nums.group()));
        if (list.size() >= 2) {
            int a = list.get(0), b = list.get(list.size() - 1);
            if (a > 0 && b >= a && b - a < 6) return new int[]{a, b};
        }
        for (int i = CN_NUM.length - 1; i >= 0; i--) {
            if (t.contains("第" + CN_NUM[i] + "大节")) return new int[]{i * 2 + 1, i * 2 + 2};
        }
        int n = Math.max(1, dataRow);
        return new int[]{n * 2 - 1, n * 2};
    }

    /** 「01-02节」「01-02-03-04节」「9-11节」→ [起, 止]。 */
    static int[] sectionsFrom(String s) {
        Matcher m = Pattern.compile("\\d+").matcher(s);
        int min = Integer.MAX_VALUE, max = -1;
        while (m.find()) {
            int v = Integer.parseInt(m.group());
            if (v <= 0 || v > 20) continue;
            min = Math.min(min, v);
            max = Math.max(max, v);
        }
        return max < 0 ? null : new int[]{min, max};
    }

    /** 格子里的详细课程 div：{id, 内容}。没有 kbcontent 时退回 kbcontent1。 */
    static List<String[]> contentDivs(String cell) {
        List<String[]> detailed = new ArrayList<>(), brief = new ArrayList<>();
        Matcher m = DIV.matcher(cell);
        while (m.find()) {
            String cls = attr(CLASS_ATTR, m.group(1));
            String[] item = {attr(ID_ATTR, m.group(1)), m.group(2)};
            if (cls.matches("(^|.*\\s)kbcontent(\\s.*|$)")) detailed.add(item);
            else if (cls.matches("(^|.*\\s)kbcontent1(\\s.*|$)")) brief.add(item);
        }
        return detailed.isEmpty() ? brief : detailed;
    }

    /** div id 形如 {@code 4F2A…-3-2}：倒数第二段是星期。 */
    static int dayFromId(String id) {
        if (id == null) return 0;
        Matcher m = Pattern.compile("-(\\d)-\\d$").matcher(id.trim());
        if (!m.find()) return 0;
        int d = Integer.parseInt(m.group(1));
        return d >= 1 && d <= 7 ? d : 0;
    }

    static int colspan(String attrs) {
        Matcher m = COLSPAN.matcher(attrs == null ? "" : attrs);
        if (!m.find()) return 1;
        try {
            return Math.max(1, Math.min(14, Integer.parseInt(m.group(1))));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    static String attr(Pattern p, String attrs) {
        if (attrs == null) return "";
        Matcher m = p.matcher(attrs);
        if (!m.find()) return "";
        for (int g = 2; g <= m.groupCount(); g++) if (m.group(g) != null) return m.group(g).trim();
        return m.group(1) == null ? "" : m.group(1).trim();
    }

    static List<String> lines(String html) {
        String s = html.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?is)<[^>]+>", "");
        List<String> out = new ArrayList<>();
        for (String line : unescape(s).split("\n")) {
            String part = line.trim().replaceFirst("^[●★○◆■\\s]+", "");
            if (!part.isEmpty() && !part.matches("-+")) out.add(part);
        }
        return out;
    }

    static String joinName(List<String> parts) {
        StringBuilder name = new StringBuilder();
        for (String part : parts) {
            if (name.length() > 0) {
                char previous = name.charAt(name.length() - 1), next = part.charAt(0);
                if (previous < 128 && next < 128
                        && Character.isLetterOrDigit(previous) && Character.isLetterOrDigit(next)) name.append(' ');
            }
            name.append(part);
        }
        return name.toString();
    }

    static String text(String html) {
        if (html == null) return "";
        String s = html.replaceAll("(?i)<br\\s*/?>", " ").replaceAll("(?is)<[^>]+>", "");
        return unescape(s).replaceAll("\\s+", " ").trim();
    }

    static String unescape(String s) {
        return s.replace("&nbsp;", " ").replace("&#160;", " ")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'")
                .replace('\u00a0', ' ').replace('\u3000', ' ').trim()
                .replace("&ensp;", " ").replace("&emsp;", " ");
    }

    static int indexOfIgnoreCase(String s, String key) {
        return indexOfIgnoreCase(s, key, 0);
    }

    static int indexOfIgnoreCase(String s, String key, int from) {
        return s.toLowerCase(Locale.ROOT).indexOf(key.toLowerCase(Locale.ROOT), from);
    }

    static int lastIndexOfIgnoreCase(String s, String key) {
        return s.toLowerCase(Locale.ROOT).lastIndexOf(key.toLowerCase(Locale.ROOT));
    }
}
