package com.ecut.campus;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** 验证快捷方式、同步磁贴、作业提醒接收器与图片共享路径均已正确注册。 */
public class PolishManifestTest {

    private static String read(String path) throws Exception {
        return new String(Files.readAllBytes(new File(path).toPath()), StandardCharsets.UTF_8);
    }

    @Test
    public void shortcutsExposeTodayRoomsGradesAndExams() throws Exception {
        for (String path : new String[]{"src/main/res/xml/shortcuts.xml", "src/debug/res/xml/shortcuts.xml"}) {
            String xml = read(path);
            assertTrue(path + " missing open_today", xml.contains("open_today"));
            assertTrue(path + " missing open_rooms", xml.contains("open_rooms"));
            assertTrue(path + " missing open_grades", xml.contains("open_grades"));
            assertTrue(path + " missing open_exams", xml.contains("open_exams"));
        }
    }

    @Test
    public void manifestRegistersSyncTileTodoActivityAndCalendarPermissions() throws Exception {
        String m = read("src/main/AndroidManifest.xml");
        assertTrue(m.contains("android.permission.READ_CALENDAR"));
        assertTrue(m.contains("android.permission.WRITE_CALENDAR"));
        assertTrue(m.contains("android:name=\".SyncTimetableTileService\""));
        assertTrue(m.contains("android:name=\".CourseTodoActivity\""));
        assertTrue(m.contains("android:name=\".TodoReminder$Receiver\""));
        assertTrue(m.contains("android:name=\".fun.GpaSimulatorActivity\""));
        assertTrue(m.contains("android:label=\"小工具\""));
        assertTrue(m.contains("android:enableOnBackInvokedCallback=\"true\""));
        assertTrue(m.contains("android.permission.SCHEDULE_EXACT_ALARM"));
        assertFalse(m.contains("android.permission.USE_EXACT_ALARM"));
        assertFalse(m.contains("android.permission.ACCESS_COARSE_LOCATION"));
        assertFalse(m.contains("android.permission.CAMERA"));
        assertTrue(read("src/main/res/xml/file_paths.xml").contains("todo_photos/"));
        assertTrue(read("src/main/res/xml/file_paths.xml").contains("qr_scan/"));
        assertTrue(read("src/main/res/mipmap-anydpi-v26/ic_launcher.xml").contains("<monochrome"));
        assertTrue(read("src/main/res/values/themes.xml").contains("windowSplashScreenBackground"));
    }
}
