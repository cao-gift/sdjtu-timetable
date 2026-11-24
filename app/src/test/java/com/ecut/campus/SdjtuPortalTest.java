package com.ecut.campus;

import com.ecut.campus.data.SdjtuPortal;
import org.junit.Test;
import static org.junit.Assert.*;

public class SdjtuPortalTest {
    @Test public void silentSyncReadsOnlyTheActualQiangzhiQueryDocument() {
        String base = SdjtuPortal.academicBase(SdjtuPortal.STUDENT_HOME);
        CampusSyncClient.Kind timetable = CampusSyncClient.Kind.TIMETABLE;
        CampusSyncClient.Kind grades = CampusSyncClient.Kind.GRADES;
        assertTrue(SdjtuSyncSession.targetPage(base + "xskb/xskb_list.do?term=demo", timetable));
        assertTrue(SdjtuSyncSession.targetPage(base + "kscj/cjcx_list", grades));
        assertFalse(SdjtuSyncSession.targetPage(base + "kscj/cjcx_list", timetable));
        assertFalse(SdjtuSyncSession.targetPage(SdjtuPortal.LOGIN + "login?service=" + base + "xskb/xskb_list.do", timetable));
        assertFalse(SdjtuSyncSession.targetPage(SdjtuPortal.STUDENT_HOME, timetable));
        assertFalse(SdjtuSyncSession.targetPage("https://sdjtu.edu.cn.example/jsxsd/xskb/xskb_list.do", timetable));
        assertFalse(SdjtuPortal.isSchoolUrl("https://unrelated.example@wwebvpn.sdjtu.edu.cn/"));
        assertFalse(SdjtuPortal.isSchoolUrl("http://192.0.2.12/sso.jsp"));
        assertFalse(SdjtuPortal.isSchoolUrl("https://wwebvpn.sdjtu.edu.cn:8443/"));
    }

    @Test public void legacyStudentHomeUsesTheSchoolGatewayAndKeepsTheProxyPrefix() {
        String defaultHome = SdjtuPortal.studentHome("");
        assertEquals(SdjtuPortal.STUDENT_HOME, defaultHome);
        assertEquals(defaultHome, SdjtuPortal.studentHome(SdjtuPortal.LOGIN + "jsxsd/"));
        assertTrue(defaultHome.startsWith(SdjtuPortal.LOGIN + "https/"));
        assertTrue(defaultHome.endsWith("/jsxsd/framework/xsMain.jsp"));
        String oldBase = SdjtuPortal.LOGIN + "https/test-route/jsxsd/";
        assertEquals(oldBase + "framework/xsMain.jsp", SdjtuPortal.studentHome(oldBase));
        assertEquals(defaultHome, SdjtuPortal.studentHome("https://example.org/jsxsd/"));
    }

    @Test public void rememberOnlyReusableFeatureEntries() {
        String base = SdjtuPortal.academicBase(SdjtuPortal.STUDENT_HOME);
        SdjtuAcademicPages.Feature rooms = SdjtuAcademicPages.Feature.ROOMS;
        SdjtuAcademicPages.Feature plan = SdjtuAcademicPages.Feature.PLAN;
        assertEquals(base + "kbcx/kbxx_classroom", SdjtuStudyRoute.safeEntry(rooms, base + "kbcx/kbxx_classroom"));
        assertEquals(base + "pyfa/pyfa_query", SdjtuStudyRoute.safeEntry(plan, base + "pyfa/pyfa_query"));
        for (String suffix : new String[]{"pyfa/pyfa_query?id=2024000000","pyfa/pyfa_query#detail",
                "pyfa/topyfamx","pyfa/student/2024000000","framework/xsMain.jsp","kbcx/kbxx_classroom"}) {
            assertEquals("", SdjtuStudyRoute.safeEntry(plan, base + suffix));
        }
        assertEquals("", SdjtuStudyRoute.safeEntry(rooms, base + "kbcx/kbxx_classroom_ifr"));
    }

    @Test public void academicEntriesOpenOnlyTheirRequestedReader() {
        assertEquals(SdjtuAcademicPages.Feature.ROOMS, SdjtuAcademicPages.Feature.fromTarget("sdjtu_rooms"));
        assertEquals(SdjtuAcademicPages.Feature.PLAN, SdjtuAcademicPages.Feature.fromTarget("sdjtu_plan"));
        assertEquals(SdjtuAcademicPages.Feature.PORTAL, SdjtuAcademicPages.Feature.fromTarget("timetable"));
        assertEquals(SdjtuAcademicPages.Feature.PORTAL, SdjtuAcademicPages.Feature.fromTarget(null));
    }

    @Test public void publicLoginUsesTheSdjtuWebVpn() {
        assertEquals("山东交通学院", SdjtuPortal.NAME);
        assertEquals("https://wwebvpn.sdjtu.edu.cn/", SdjtuPortal.LOGIN);
        assertTrue(SdjtuPortal.isSchoolUrl(SdjtuPortal.LOGIN));
        assertTrue(SdjtuPortal.isSchoolUrl("https://sdjtu.edu.cn:443/"));
        assertFalse(SdjtuPortal.isSchoolUrl("http://wwebvpn.sdjtu.edu.cn/"));
        assertEquals("", SdjtuPortal.academicBase(SdjtuPortal.LOGIN));
        assertTrue(SdjtuPortal.isSchoolUrl("https://sso.sdjtu.edu.cn/cas/login"));
        assertFalse(SdjtuPortal.isSchoolUrl("https://sso.sdjtu.edu.cn.untrusted.example/"));
        assertFalse(SdjtuPortal.isSchoolUrl("https://ehall.ecut.edu.cn/"));
    }

    @Test public void academicRootRetainsOnlyTheSchoolHostAndProxyPrefix() {
        String root = SdjtuPortal.LOGIN + "https/test-route/jsxsd/";
        assertEquals(root, SdjtuPortal.academicBase(root + "framework/main.jsp?sample=test#x"));
        assertEquals(root, SdjtuPortal.academicBase(root));
        assertEquals("", SdjtuPortal.academicBase(SdjtuPortal.LOGIN + "login?next=/jsxsd/"));
        assertEquals("", SdjtuPortal.academicBase("https://evil.example/jsxsd/"));
        assertEquals("", SdjtuPortal.academicBase("https://name@wwebvpn.sdjtu.edu.cn/jsxsd/"));
        assertEquals("", SdjtuPortal.academicBase(SdjtuPortal.LOGIN + "../jsxsd/"));
    }
}
