package com.ecut.campus;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 升级必须保留用户之前启用的桌面入口。全部入口复用新图标和同一组快捷方式。
 */
public class LauncherAliasManifestTest {

    private static final String[] EXISTING_ALIASES = {"LauncherPear", "LauncherArt", "LauncherBenben"};

    private static String manifest() throws Exception {
        return new String(Files.readAllBytes(new File("src/main/AndroidManifest.xml").toPath()), StandardCharsets.UTF_8);
    }

    private static String alias(String m, String shortName) {
        Matcher x = Pattern.compile("<activity-alias[^>]*android:name=\"\\." + shortName + "\"[\\s\\S]*?</activity-alias>")
                .matcher(m);
        assertTrue("missing alias " + shortName, x.find());
        return x.group();
    }

    @Test
    public void existingLauncherEntriesUseTheNewIconAndOnlyOneIsEnabledByDefault() throws Exception {
        String m = manifest();
        for (String name : EXISTING_ALIASES) {
            String a = alias(m, name);
            assertTrue(a.contains("android:icon=\"@mipmap/ic_launcher\""));
            assertTrue(a.contains("android:roundIcon=\"@mipmap/ic_launcher_round\""));
            assertTrue(a.contains("android:targetActivity=\".MainActivity\""));
            assertTrue(a.contains("android.intent.category.LAUNCHER"));
            assertTrue(a.contains("@xml/shortcuts"));
            assertTrue(a.contains("android:exported=\"true\""));
            assertEquals(name.equals("LauncherPear"), a.contains("android:enabled=\"true\""));
        }
    }

    @Test
    public void mainActivityItselfIsNotALauncherEntry() throws Exception {
        String m = manifest();
        Matcher x = Pattern.compile("<activity\\s+android:name=\"\\.MainActivity\"[\\s\\S]*?(?:/>|</activity>)").matcher(m);
        assertTrue(x.find());
        assertFalse(x.group().contains("android.intent.category.LAUNCHER"));
        // 指令链接入口已移除，不再注册 lvlv:// 或 tangli:// scheme
        assertFalse(x.group().contains("android:scheme=\"lvlv\""));
        assertFalse(x.group().contains("android:scheme=\"tangli\""));
        // 整个清单里 LAUNCHER 只出现在兼容的三个 alias 上
        int n = 0;
        Matcher l = Pattern.compile("android.intent.category.LAUNCHER").matcher(m);
        while (l.find()) n++;
        assertEquals(EXISTING_ALIASES.length, n);
    }
}
