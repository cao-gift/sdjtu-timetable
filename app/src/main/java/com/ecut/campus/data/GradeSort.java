package com.ecut.campus.data;

import java.util.ArrayList;
import java.util.List;

/** 成绩列表排序（纯 Java，无 Android 依赖，便于 JVM 单测）。 */
public final class GradeSort {
    public static final int DEFAULT = 0;
    public static final int SCORE_DESC = 1;
    public static final int SCORE_ASC = 2;

    private GradeSort() {}

    /** 将百分制与等级制成绩映射为可排序的分值（不修改原始成绩文本）。 */
    public static double rankOf(Grades.CourseGrade cg) {
        if (cg == null) return -1.0;
        if (cg.scoreValue != null && Double.isFinite(cg.scoreValue)) {
            return cg.scoreValue;
        }
        String raw = cg.score == null ? "" : cg.score.trim();
        if (raw.contains("优秀") || "优".equals(raw) || "A".equalsIgnoreCase(raw) || "A+".equalsIgnoreCase(raw)) return 95.0;
        if (raw.contains("良好") || "良".equals(raw) || "B".equalsIgnoreCase(raw) || "B+".equalsIgnoreCase(raw)) return 85.0;
        if (raw.contains("中等") || "中".equals(raw) || "C".equalsIgnoreCase(raw)) return 75.0;
        if (raw.contains("不合格") || raw.contains("不及格") || raw.contains("未通过") || "F".equalsIgnoreCase(raw)) return 50.0;
        if (raw.contains("合格") || raw.contains("及格") || raw.contains("通过") || "D".equalsIgnoreCase(raw)) return 65.0;
        if (raw.contains("缓考") || raw.contains("缺考") || raw.contains("旷考") || raw.contains("取消")) return -1.0;
        return cg.isPassed ? 60.0 : 0.0;
    }

    public static List<Grades.CourseGrade> sorted(List<Grades.CourseGrade> src, int mode) {
        List<Grades.CourseGrade> out = new ArrayList<>(src == null ? java.util.Collections.emptyList() : src);
        if (mode == DEFAULT || out.size() <= 1) return out;
        out.sort((a, b) -> {
            double ra = rankOf(a);
            double rb = rankOf(b);
            int cmp = mode == SCORE_DESC ? Double.compare(rb, ra) : Double.compare(ra, rb);
            if (cmp != 0) return cmp;
            double ga = a.gradePoint != null ? a.gradePoint : -1.0;
            double gb = b.gradePoint != null ? b.gradePoint : -1.0;
            cmp = mode == SCORE_DESC ? Double.compare(gb, ga) : Double.compare(ga, gb);
            if (cmp != 0) return cmp;
            cmp = Double.compare(b.credit, a.credit);
            if (cmp != 0) return cmp;
            String na = a.name == null ? "" : a.name;
            String nb = b.name == null ? "" : b.name;
            return na.compareTo(nb);
        });
        return out;
    }
}
