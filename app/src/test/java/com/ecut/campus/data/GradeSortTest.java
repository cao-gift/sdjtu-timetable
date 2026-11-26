package com.ecut.campus.data;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class GradeSortTest {

    private static Grades.CourseGrade cg(String name, String score, Double val, Double gp, double credit, boolean passed) {
        Grades.CourseGrade g = new Grades.CourseGrade();
        g.name = name;
        g.score = score;
        g.scoreValue = val;
        g.gradePoint = gp;
        g.credit = credit;
        g.isPassed = passed;
        return g;
    }

    @Test
    public void sortsNumericAndTextGradesDescendingAndAscending() {
        List<Grades.CourseGrade> raw = Arrays.asList(
                cg("体育(4)", "76", 76.0, 2.6, 1.0, true),
                cg("电工与电子技术", "97", 97.0, 4.7, 2.0, true),
                cg("工程实训", "优秀", null, 4.5, 2.0, true),
                cg("高等数学", "54", 54.0, 0.0, 4.0, false));

        List<Grades.CourseGrade> desc = GradeSort.sorted(raw, GradeSort.SCORE_DESC);
        assertEquals("电工与电子技术", desc.get(0).name);
        assertEquals("工程实训", desc.get(1).name);
        assertEquals("体育(4)", desc.get(2).name);
        assertEquals("高等数学", desc.get(3).name);

        List<Grades.CourseGrade> asc = GradeSort.sorted(raw, GradeSort.SCORE_ASC);
        assertEquals("高等数学", asc.get(0).name);
        assertEquals("体育(4)", asc.get(1).name);
        assertEquals("工程实训", asc.get(2).name);
        assertEquals("电工与电子技术", asc.get(3).name);

        List<Grades.CourseGrade> def = GradeSort.sorted(raw, GradeSort.DEFAULT);
        assertEquals("体育(4)", def.get(0).name);
    }
}
