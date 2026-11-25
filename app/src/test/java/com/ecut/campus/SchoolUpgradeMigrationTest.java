package com.ecut.campus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.ecut.campus.data.SdjtuPortal;
import com.ecut.campus.data.SemesterMetaPolicy;
import com.ecut.campus.data.Timetable;

import org.junit.Test;

/** 单校升级与跨校成绩缓存迁移判定。 */
public class SchoolUpgradeMigrationTest {

    private static final String SDJTU_BASE = SdjtuPortal.academicBase(SdjtuPortal.STUDENT_HOME);

    @Test
    public void explicitSdjtuPreservesGradesAndSession() {
        assertFalse(Prefs.isForeignSchoolState(
                "sdjtu", false, false, false,
                true, 1700000000000L, "11-23 10:00",
                true, true, SDJTU_BASE,
                true, true, true, false));
        // 即使早年切校残留过旧 portal_host，显式选中交院时仍保留交院成绩
        assertFalse(Prefs.isForeignSchoolState(
                "sdjtu", true, false, false,
                true, 1700000000000L, "11-23 10:00",
                true, true, SDJTU_BASE,
                false, false, true, false));
    }

    @Test
    public void explicitOtherSchoolsTriggerForeignCleanup() {
        for (String id : new String[]{"ecut", "custom", "nju"}) {
            assertTrue(id, Prefs.isForeignSchoolState(
                    id, false, false, false,
                    true, 1700000000000L, "11-23 10:00",
                    true, true, SDJTU_BASE,
                    false, false, false, true));
        }
    }

    @Test
    public void defaultSdjtuInstallPreservesGradesAcrossSignals() {
        // 1.3.3–1.3.13 新装默认交院（未写 school_id），通过 academicBase 识别
        assertFalse(Prefs.isForeignSchoolState(
                "", false, false, false,
                true, 1700000000000L, "11-23 10:00",
                true, true, SDJTU_BASE,
                false, false, false, false));
        // 保存过交院自动登录凭据时，SdjtuCredentials.save 会清空 sdjtu_academic_base，仍须保留交院成绩
        assertFalse(Prefs.isForeignSchoolState(
                "", false, false, false,
                false, 1700000000000L, "11-23 10:00",
                true, true, "",
                true, false, false, false));
        // 有交院空教室/培养方案/考试快照或路由
        assertFalse(Prefs.isForeignSchoolState(
                "", false, false, false,
                true, 0L, "11-23 10:00",
                true, false, "",
                false, true, false, false));
        // 仅有强智课表且无他校痕迹
        assertFalse(Prefs.isForeignSchoolState(
                "", false, false, false,
                false, 1700000000000L, "11-23 10:00",
                true, false, "",
                false, false, true, false));
    }

    @Test
    public void legacyForeignInstallsWithoutSchoolIdClearGrades() {
        // 旧版有 aTrust portal_host
        assertTrue(Prefs.isForeignSchoolState(
                "", true, false, false,
                false, 0L, "",
                true, true, "",
                false, false, false, false));
        // 旧版有 custom_school 或校历配置
        assertTrue(Prefs.isForeignSchoolState(
                "", false, true, false,
                false, 0L, "",
                true, false, "",
                false, false, false, false));
        assertTrue(Prefs.isForeignSchoolState(
                "", false, false, true,
                false, 0L, "",
                true, false, "",
                false, false, false, false));
        // 旧版只有正方教务课表（非 qiangzhi_ 前缀）
        assertTrue(Prefs.isForeignSchoolState(
                "", false, false, false,
                false, 0L, "",
                true, false, "",
                false, false, false, true));
        // 旧版重置过 portal_host 但仍留有他校成绩或未读提醒、且无任何交院信号
        assertTrue(Prefs.isForeignSchoolState(
                "", false, false, false,
                false, 0L, "",
                true, false, "",
                false, false, false, false));
        assertTrue(Prefs.isForeignSchoolState(
                "", false, false, false,
                false, 0L, "",
                false, true, "",
                false, false, false, false));
        // 无效的伪造 academicBase 不能冒充交院信号
        assertTrue(Prefs.isForeignSchoolState(
                "", false, false, false,
                true, 0L, "11-20 09:00",
                true, false, "https://other.example/jsxsd/",
                false, false, false, false));
    }

    @Test
    public void freshOrManualOnlyInstallIsNotMarkedForeign() {
        // 新装或 1.3.13 仅手动加课与五天显示设置
        assertFalse(Prefs.isForeignSchoolState(
                "", false, false, false,
                false, 0L, "",
                false, false, "",
                false, false, false, false));
    }

    @Test
    public void followUpMigrationClearsStaleForeignGradesFromEarly1314OnlyBeforeSdjtuSync() {
        // 从早期 1.3.14 升上来：当时标记了 preserve_legacy_semester_dates 且尚未同步过交院 -> 补清
        assertTrue(Prefs.shouldClearGradesOnFollowUp(
                true, false, true, false, "", ""));
        // 已经在 1.3.14 上登录或同步过交院 -> 不得清掉新交院成绩
        assertFalse(Prefs.shouldClearGradesOnFollowUp(
                true, false, true, true, "", ""));
        assertFalse(Prefs.shouldClearGradesOnFollowUp(
                true, false, true, false, SDJTU_BASE, ""));
        assertFalse(Prefs.shouldClearGradesOnFollowUp(
                true, false, true, false, "", "11-24 12:00"));
        // 原来就是交院用户（preserve_legacy_semester_dates=false） -> 保留
        assertFalse(Prefs.shouldClearGradesOnFollowUp(
                true, false, false, false, "", ""));
        // 已完成成绩迁移 -> 不重复执行
        assertFalse(Prefs.shouldClearGradesOnFollowUp(
                true, true, true, false, "", ""));
    }

    @Test
    public void inspectJwglxtOriginDistinguishesQiangzhiForeignAndManual() {
        Timetable manual = new Timetable();
        Timetable.Course custom = new Timetable.Course();
        custom.source = Timetable.SOURCE_CUSTOM;
        custom.key = "custom_verify";
        custom.name = "单校升级验证课";
        manual.courses.add(custom);
        assertEquals(SemesterMetaPolicy.ORIGIN_NONE, SemesterMetaPolicy.inspectJwglxtOrigin(manual));

        Timetable qz = new Timetable();
        Timetable.Course sdjtu = new Timetable.Course();
        sdjtu.source = Timetable.SOURCE_JWGLXT;
        sdjtu.key = "qiangzhi_004720-050209R_教师甲";
        qz.courses.add(sdjtu);
        assertEquals(SemesterMetaPolicy.ORIGIN_QIANGZHI, SemesterMetaPolicy.inspectJwglxtOrigin(qz));

        Timetable zf = new Timetable();
        Timetable.Course ecut = new Timetable.Course();
        ecut.source = Timetable.SOURCE_JWGLXT;
        ecut.key = "jwglxt_544E7DFB";
        zf.courses.add(ecut);
        assertEquals(SemesterMetaPolicy.ORIGIN_FOREIGN, SemesterMetaPolicy.inspectJwglxtOrigin(zf));

        zf.courses.add(sdjtu);
        assertEquals(SemesterMetaPolicy.ORIGIN_QIANGZHI | SemesterMetaPolicy.ORIGIN_FOREIGN,
                SemesterMetaPolicy.inspectJwglxtOrigin(zf));
    }

    @Test
    public void upgrade1313SampleWithManualCourseAndUnreadNotificationId2001() {
        assertEquals(2001, Notify.ID_NEW_GRADES);
        // 1.3.13 单校升级样本：仅手动课 + 五天设置 + 交院成绩未读，不应触发异校清理
        assertFalse(Prefs.isForeignSchoolState(
                "sdjtu", false, false, false,
                true, 1700000000000L, "11-23 09:00",
                true, true, SDJTU_BASE,
                false, false, false, false));
        // 同样本若归属 ecut，则应清理旧校 grades.json 与未读通知 2001
        assertTrue(Prefs.isForeignSchoolState(
                "ecut", true, false, false,
                true, 1700000000000L, "11-23 09:00",
                true, true, "",
                false, false, false, false));
    }
}
