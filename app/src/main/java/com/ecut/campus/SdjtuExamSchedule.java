package com.ecut.campus;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 两份学校考试表的统一结果。只保留学生查看安排需要的列。 */
final class SdjtuExamSchedule {
    private static final String[] FIELDS = {"name", "code", "time", "room", "seat"};
    private static final Pattern SPACE = Pattern.compile("[\\s\\u00a0\\u3000]+");
    private static final Pattern DATE = Pattern.compile(
            "(?<!\\d)(\\d{4})\\s*[-./年]\\s*(\\d{1,2})\\s*[-./月]\\s*(\\d{1,2})(?!\\d)日?");
    private static final Pattern CLOCK = Pattern.compile("(?<!\\d)([01]?\\d|2[0-3])\\s*[:：]\\s*([0-5]\\d)(?!\\d)");

    private SdjtuExamSchedule() {}

    static boolean valid(JSONObject page) {
        if (page == null || !(page.opt("term") instanceof String) || clean(page.optString("term")).isEmpty()) return false;
        JSONArray exams = page.optJSONArray("exams");
        if (exams == null) return false;
        for (int i = 0; i < exams.length(); i++) {
            JSONObject row = exams.optJSONObject(i);
            if (row == null || !(row.opt("name") instanceof String) || clean(row.optString("name")).isEmpty()) return false;
            for (String field : FIELDS) {
                if (!row.isNull(field) && !(row.opt(field) instanceof String)) return false;
            }
        }
        return true;
    }

    static JSONObject merge(JSONObject first, JSONObject second) {
        if ((first == null && second == null) || (first != null && !valid(first)) || (second != null && !valid(second)))
            throw new IllegalArgumentException("教务返回的考试安排格式有误");
        String term = clean((first != null ? first : second).optString("term"));
        if (second != null && !term.equals(clean(second.optString("term"))))
            throw new IllegalArgumentException("两份考试安排的学期不一致");

        List<Exam> primary = read(first), other = read(second);
        List<Exam> merged = new ArrayList<>(primary);
        // 只有场次唯一且各列相容时才补齐缺失列，以免把同课不同考场合并。
        int[] matches = new int[primary.size()];
        for (Exam a : other) {
            for (int i = 0; i < primary.size(); i++) {
                if (a.sameSitting(primary.get(i))) matches[i]++;
            }
        }
        for (Exam b : other) {
            int match = -1;
            boolean duplicate = false;
            for (int i = 0; i < primary.size(); i++) {
                Exam a = primary.get(i);
                if (a.identifiable() && a.key.equals(b.key)) { duplicate = true; break; }
                if (b.sameSitting(a)) match = match == -1 ? i : -2;
            }
            if (duplicate) continue;
            if (match >= 0 && matches[match] == 1) merged.set(match, primary.get(match).filledFrom(b));
            else merged.add(b);
        }
        // List.sort is stable. Keep the school's order for equal starts and for unscheduled exams.
        merged.sort(Comparator.comparingLong(exam -> exam.start));
        JSONArray exams = new JSONArray();
        try {
            for (Exam exam : merged) {
                JSONObject item = new JSONObject();
                for (int i = 0; i < FIELDS.length; i++) {
                    if (!exam.values[i].isEmpty()) item.put(FIELDS[i], exam.values[i]);
                }
                exams.put(item);
            }
            return new JSONObject().put("term", term).put("exams", exams);
        } catch (JSONException e) {
            throw new IllegalArgumentException("考试安排格式有误", e);
        }
    }

    private static String clean(String text) { return SPACE.matcher(text).replaceAll(" ").trim(); }

    private static List<Exam> read(JSONObject page) {
        List<Exam> result = new ArrayList<>();
        Set<List<String>> knownSittings = new HashSet<>();
        if (page != null) {
            JSONArray rows = page.optJSONArray("exams");
            for (int i = 0; i < rows.length(); i++) {
                JSONObject row = rows.optJSONObject(i);
                String[] values = new String[FIELDS.length];
                for (int n = 0; n < FIELDS.length; n++)
                    values[n] = row.isNull(FIELDS[n]) ? "" : clean(row.optString(FIELDS[n]));
                Exam exam = new Exam(values);
                if (!exam.identifiable() || knownSittings.add(exam.key)) result.add(exam);
            }
        }
        return result;
    }

    private static final class Exam {
        final String[] values;
        final List<String> key;
        final long start;
        final boolean timed;

        Exam(String[] values) {
            this.values = values;
            String[] parts = values.clone();
            Matcher date = DATE.matcher(parts[2]);
            long order = Long.MAX_VALUE;
            boolean hasTime = false;
            if (date.find()) {
                int year = Integer.parseInt(date.group(1)), month = Integer.parseInt(date.group(2));
                int day = Integer.parseInt(date.group(3));
                if (month >= 1 && month <= 12 && day >= 1 && day <= 31) {
                    Matcher clock = CLOCK.matcher(parts[2]);
                    int hhmm = 2400;
                    if (clock.find(date.end())) {
                        hhmm = Integer.parseInt(clock.group(1)) * 100 + Integer.parseInt(clock.group(2));
                        hasTime = true;
                    }
                    order = ((year * 100L + month) * 100 + day) * 10000 + hhmm;
                    String normalized = date.replaceFirst(String.format(Locale.ROOT, "%04d-%02d-%02d", year, month, day));
                    clock = CLOCK.matcher(normalized);
                    StringBuffer joined = new StringBuffer();
                    while (clock.find()) clock.appendReplacement(joined,
                            String.format(Locale.ROOT, "%02d:%s", Integer.parseInt(clock.group(1)), clock.group(2)));
                    parts[2] = clock.appendTail(joined).toString().replace(" ", "").replaceAll("[－–—~～至]", "-");
                }
            }
            key = Arrays.asList(parts);
            start = order;
            timed = hasTime;
        }

        boolean identifiable() {
            // 同名且都待定的两行可能是两场独立考试。
            return timed && (!values[1].isEmpty() || !values[3].isEmpty());
        }

        boolean sameSitting(Exam other) {
            if (!timed || !values[0].equals(other.values[0]) || !key.get(2).equals(other.key.get(2))) return false;
            boolean sameCode = !values[1].isEmpty() && values[1].equals(other.values[1]);
            boolean sameRoom = !values[3].isEmpty() && values[3].equals(other.values[3]);
            if (!sameCode && !sameRoom) return false;
            for (int i = 1; i < FIELDS.length; i++) {
                if (i != 2 && !values[i].isEmpty() && !other.values[i].isEmpty() && !values[i].equals(other.values[i]))
                    return false;
            }
            return true;
        }

        Exam filledFrom(Exam other) {
            String[] filled = values.clone();
            for (int i = 0; i < filled.length; i++) if (filled[i].isEmpty()) filled[i] = other.values[i];
            return new Exam(filled);
        }
    }
}
