package com.ecut.campus.data;

import static org.junit.Assert.*;

import org.junit.Test;

/** 「新版本说明」只给从老版本升上来的人看一次，新装不弹。 */
public class WhatsNewTest {
    private static final int NOTES = WhatsNew.NOTES_CODE;

    @Test
    public void freshInstallNeverShows() {
        assertFalse(WhatsNew.shouldShow(0, NOTES, false));
        assertFalse(WhatsNew.shouldShow(0, NOTES + 1, false));
    }

    @Test
    public void upgradeFromOldVersionWithoutRecordShowsOnce() {
        // 1.2.6 及以前没记过 versionCode：靠覆盖安装判断
        assertTrue(WhatsNew.shouldShow(0, NOTES, true));
        // 打开过一次后记下当前版本号，就不再弹
        assertFalse(WhatsNew.shouldShow(NOTES, NOTES, true));
    }

    @Test
    public void laterUpdatesDoNotRepeatTheSameNotes() {
        assertFalse(WhatsNew.shouldShow(NOTES, NOTES + 1, true));
        assertFalse(WhatsNew.shouldShow(NOTES + 1, NOTES + 1, true));
    }

    @Test
    public void downgradeOrBuildsWithoutNotesNeverShow() {
        assertFalse(WhatsNew.shouldShow(0, NOTES - 1, true));
        assertFalse(WhatsNew.shouldShow(NOTES + 2, NOTES, true));
    }

    @Test
    public void knownOlderCodeShows() {
        assertTrue(WhatsNew.shouldShow(NOTES - 1, NOTES, false));
    }

    @Test
    public void upgradeDetection() {
        assertFalse(WhatsNew.isUpgrade(1_000_000L, 1_000_000L));
        assertFalse(WhatsNew.isUpgrade(1_000_000L, 1_000_500L));
        assertTrue(WhatsNew.isUpgrade(1_000_000L, 5_000_000L));
        assertFalse(WhatsNew.isUpgrade(0L, 5_000_000L));
    }
}
