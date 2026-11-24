package com.ecut.campus.data;

import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class CourseIdentityTest {
    private static final String CODE = "004000-AB01";
    private static final String NAME = "交通规划";

    private Timetable.Course course(String name, String code) {
        Timetable.Course c = new Timetable.Course();
        c.name = name;
        c.code = code;
        c.teacher = "教师甲";
        c.key = "qiangzhi_" + (code.isEmpty() ? name : code) + "_" + c.teacher;
        Timetable.Session s = new Timetable.Session();
        s.weeks.add(4);
        s.location = "无影山1号教学楼1102";
        c.sessions.add(s);
        return c;
    }

    private Timetable table(Timetable.Course... courses) {
        Timetable t = new Timetable();
        t.semester.id = "2026-2027-1";
        t.courses.addAll(Arrays.asList(courses));
        return t;
    }

    @Test public void optionalCodeAndFullLocationSurviveBackupsAndShareCodes() throws Exception {
        for (String code : Arrays.asList("", CODE)) {
            Timetable t = table(course(NAME, code));
            JSONObject saved = t.toJson().getJSONArray("courses").getJSONObject(0);
            assertEquals(!code.isEmpty(), saved.has("code"));
            Timetable restored = TimetableStore.importJson(TimetableStore.exportJson(t));
            Timetable shared = TimetableShare.decode(TimetableShare.encode(t, "")).timetable;
            for (Timetable read : Arrays.asList(restored, shared)) {
                Timetable.Course c = read.courses.get(0);
                assertEquals(NAME, c.name);
                assertEquals(code, c.code);
                assertEquals("无影山1号教学楼1102", c.sessions.get(0).location);
            }
            assertEquals("", shared.courses.get(0).teacher);
        }
    }

    @Test public void correctedSyncKeepsUserDataAndTargetsTheOriginalCourse() {
        Timetable.Course old = course(CODE, "");
        old.note = "带图纸";
        old.colorIndex = 6;
        old.colorTouched = true;
        Timetable.Course custom = course("自主研习", "");
        custom.key = "custom_1";
        custom.source = Timetable.SOURCE_CUSTOM;
        Timetable local = table(old, custom);
        Timetable.Adjust move = new Timetable.Adjust();
        move.name = CODE;
        move.type = Timetable.Adjust.MOVE;
        move.day = 1;
        move.week = 4;
        move.toWeek = 5;
        local.adjusts.add(move);

        Timetable.Course fixed = course(NAME, CODE);
        Timetable.Course other = course(NAME, "004001-AB02");
        Timetable synced = TimetableStore.merge(local, table(fixed, other));
        assertEquals(3, synced.courses.size());
        assertTrue(synced.courses.contains(custom));
        assertEquals("带图纸", fixed.note);
        assertEquals(6, fixed.colorIndex);
        assertTrue(fixed.colorTouched);
        WeekOverlay source = WeekOverlay.build(synced, "", 4);
        assertTrue(source.cancelled.containsKey(fixed.sessions.get(0)));
        assertFalse(source.cancelled.containsKey(other.sessions.get(0)));
        assertSame(fixed, WeekOverlay.build(synced, "", 5).extras.get(0).course);

        AbsenceBook book = new AbsenceBook();
        book.bump(CODE, 0, 2);
        book.bump(NAME, 0, 1);
        assertTrue(book.restoreCourseNames(synced));
        assertFalse(book.restoreCourseNames(synced));
        assertEquals(3, book.get(NAME).leave);
        assertEquals(0, book.get(CODE).leave);
    }

    @Test public void restoringOldBackupKeepsAlreadyRecoveredIdentity() {
        Timetable.Course c = TimetableStore.merge(table(course(NAME, CODE)), table(course(CODE, ""))).courses.get(0);
        assertEquals(CODE, c.code);
        assertEquals(NAME, c.name);
    }

    @Test public void distinctCodesDoNotCollapseSameNameAndSession() {
        Timetable t = table(course(NAME, CODE), course(NAME, "004001-AB02"));
        assertEquals(0, QiangzhiSource.mergeOverlaps(t.courses));
        assertEquals(2, t.courses.size());
        t = table(course(NAME, ""), course(NAME, ""));
        assertEquals(1, QiangzhiSource.mergeOverlaps(t.courses));
    }
}
