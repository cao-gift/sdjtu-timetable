package com.ecut.campus.data;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/** GPA 目标模拟器与校区天气坐标/调课计数单测。 */
public class GpaSimulatorTest {

    @Test
    public void computeTargetCalculatesRequiredGpAndScore() {
        GpaSimulator.Baseline base = GpaSimulator.Baseline.of(3.0, 60.0);
        GpaSimulator.TargetResult r = GpaSimulator.computeTarget(base, 3.2, 20.0);
        assertEquals(GpaSimulator.TargetResult.Status.ACHIEVABLE, r.status);
        // (3.2 * 80 - 3.0 * 60) / 20 = (256 - 180) / 20 = 3.80 -> 88.0 分
        assertEquals(3.80, r.requiredGp, 0.01);
        assertEquals(88.0, r.requiredScore, 0.1);
        assertTrue(r.headline.contains("88.0"));
    }

    @Test
    public void computeTargetHandlesImpossibleAndAlreadyReached() {
        GpaSimulator.Baseline base = GpaSimulator.Baseline.of(2.5, 100.0);
        GpaSimulator.TargetResult impossible = GpaSimulator.computeTarget(base, 4.0, 10.0);
        assertEquals(GpaSimulator.TargetResult.Status.IMPOSSIBLE, impossible.status);

        GpaSimulator.Baseline high = GpaSimulator.Baseline.of(4.0, 80.0);
        GpaSimulator.TargetResult reached = GpaSimulator.computeTarget(high, 3.0, 10.0);
        assertEquals(GpaSimulator.TargetResult.Status.REACHED, reached.status);

        GpaSimulator.TargetResult invalid = GpaSimulator.computeTarget(high, 0.0, 10.0);
        assertEquals(GpaSimulator.TargetResult.Status.INVALID, invalid.status);
    }

    @Test
    public void projectMergesPlannedCourses() {
        GpaSimulator.Baseline base = GpaSimulator.Baseline.of(3.0, 30.0);
        GpaSimulator.Projection proj = GpaSimulator.project(base, Arrays.asList(
                new GpaSimulator.PlannedCourse("高等数学", 5.0, 90.0), // gp 4.0
                new GpaSimulator.PlannedCourse("英语", 5.0, 80.0)     // gp 3.0
        ));
        // (90 + 20 + 15) / 40 = 125 / 40 = 3.125 -> 3.13
        assertEquals(3.13, proj.projectedGpa, 0.01);
        assertEquals(0.13, proj.delta, 0.01);
        assertEquals(10.0, proj.addedCredits, 0.01);
        assertEquals(40.0, proj.totalCredits, 0.01);
    }

    @Test
    public void fromGradesReadsWeightedBaseline() {
        Grades grades = new Grades();
        Grades.SemesterGrades sem = new Grades.SemesterGrades();
        sem.id = "2025-2026-1";
        Grades.CourseGrade c1 = new Grades.CourseGrade();
        c1.name = "高数";
        c1.credit = 4.0;
        c1.gradePoint = 4.0;
        Grades.CourseGrade c2 = new Grades.CourseGrade();
        c2.name = "英语";
        c2.credit = 2.0;
        c2.gradePoint = 2.5;
        sem.courses.add(c1);
        sem.courses.add(c2);
        grades.semesters.add(sem);

        GpaSimulator.Baseline b = GpaSimulator.Baseline.fromGrades(grades);
        assertEquals(6.0, b.credits, 0.01);
        assertEquals(3.50, b.gpa, 0.01);
        assertEquals(2, b.courseCount);
        assertEquals(3.50, GpaSimulator.project(b, Collections.emptyList()).projectedGpa, 0.01);
    }

    @Test
    public void campusLookupAndSyncDiffCounters() {
        assertEquals(RainForecast.Campus.WUYINGSHAN, RainForecast.Campus.byId(""));
        assertEquals(RainForecast.Campus.CHANGQING, RainForecast.Campus.byId("changqing"));
        assertEquals(RainForecast.Campus.WEIHAI, RainForecast.Campus.byId("weihai"));
        assertTrue(RainForecast.Campus.WUYINGSHAN.region.contains("天桥区"));
        assertTrue(RainForecast.Campus.CHANGQING.region.contains("长清区"));
        assertTrue(RainForecast.Campus.WEIHAI.region.contains("环翠区"));

        assertEquals(0, SyncDiff.changeCount(""));
        assertEquals(2, SyncDiff.changeCount("高数 教室：101 → 202\n英语 时间：周一1-2节 → 周二3-4节"));
        assertEquals("高数 教室：101 → 202", SyncDiff.firstLine("高数 教室：101 → 202\n英语"));
    }
}
