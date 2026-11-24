package com.ecut.campus.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class GradesTest {

    @Test
    public void storedGradesRemainReadableAndCanBeMergedAfterUpgrade() throws Exception {
        String json = "{\"schemaVersion\":1,\"semesters\":[{\"id\":\"2024-2025-1\",\"courses\":["
                + "{\"code\":\"MA1\",\"name\":\"高等数学\",\"credit\":1,\"score\":\"88\",\"scoreValue\":88,\"gradePoint\":3.8,\"isPassed\":true},"
                + "{\"code\":\"MA2\",\"name\":\"线性代数\",\"credit\":3,\"score\":\"75\",\"scoreValue\":75,\"gradePoint\":2.5,\"isPassed\":true}]}]}";
        Grades grades = Grades.fromJson(json);
        grades.recomputeAll();
        assertEquals(2, grades.totalCourseCount());
        assertEquals("高等数学", grades.semesters.get(0).courses.get(0).name);
        assertEquals(78.25, grades.summary.weightedScore, 0.01);
        assertEquals(2.83, grades.summary.gpa, 0.01);
        assertTrue(grades.semesters.get(0).courses.get(1).isPassed);
        Grades restored = Grades.fromJson(grades.toJson());
        assertEquals(grades.summary.weightedScore, restored.summary.weightedScore, 0.01);
        assertEquals(2, GradesStore.merge(grades, restored).totalCourseCount());
    }
}
