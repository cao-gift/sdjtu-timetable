package com.ecut.campus.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class SdjtuSessionCheckTest {

    @Test
    public void detectsExpiredRedirectsAndHttpErrors() {
        assertEquals(SdjtuSessionCheck.State.EXPIRED,
                SdjtuSessionCheck.classifyStudentHome(401, null, ""));
        assertEquals(SdjtuSessionCheck.State.EXPIRED,
                SdjtuSessionCheck.classifyStudentHome(403, null, ""));
        assertEquals(SdjtuSessionCheck.State.EXPIRED,
                SdjtuSessionCheck.classifyStudentHome(302, "https://wwebvpn.sdjtu.edu.cn/login", ""));
        assertEquals(SdjtuSessionCheck.State.EXPIRED,
                SdjtuSessionCheck.classifyStudentHome(302, "/https/abc/cas/login?service=x", ""));
        assertEquals(SdjtuSessionCheck.State.EXPIRED,
                SdjtuSessionCheck.classifyStudentHome(302, "https://wwebvpn.sdjtu.edu.cn/fingerprint", ""));
        assertEquals(SdjtuSessionCheck.State.EXPIRED,
                SdjtuSessionCheck.classifyStudentHome(302, "https://wwebvpn.sdjtu.edu.cn/http/abc/sso.jsp", ""));
        assertEquals(SdjtuSessionCheck.State.UNKNOWN,
                SdjtuSessionCheck.classifyStudentHome(302, "https://wwebvpn.sdjtu.edu.cn/https/abc/jsxsd/framework/xsMain_new.jsp", ""));
    }

    @Test
    public void classifiesAcademicHomeVsLoginPageAndMaintenance() {
        String activeFrameset = "<html><frameset><frame src=\"/jsxsd/framework/xsMain_new.jsp\"></frameset></html>";
        assertEquals(SdjtuSessionCheck.State.ACTIVE,
                SdjtuSessionCheck.classifyStudentHome(200, null, activeFrameset));

        String activeMenu = "<html><body><div id=\"LeftMenu1_divChildMenu\">我的课表</div></body></html>";
        assertEquals(SdjtuSessionCheck.State.ACTIVE,
                SdjtuSessionCheck.classifyStudentHome(200, null, activeMenu));

        String casLogin = "<html><body><form id=\"normalLoginForm\"><input type=\"password\" name=\"password\"></form></body></html>";
        assertEquals(SdjtuSessionCheck.State.EXPIRED,
                SdjtuSessionCheck.classifyStudentHome(200, null, casLogin));

        String expiredScript = "<html><script>window.top.location.href='/jsxsd/'</script></html>";
        assertEquals(SdjtuSessionCheck.State.EXPIRED,
                SdjtuSessionCheck.classifyStudentHome(200, null, expiredScript));

        String expiredMsg = "<html><body>会话已过期，请重新登录</body></html>";
        assertEquals(SdjtuSessionCheck.State.EXPIRED,
                SdjtuSessionCheck.classifyStudentHome(200, null, expiredMsg));

        String maintenance = "<html><body>系统维护中，请稍后再试</body></html>";
        assertEquals(SdjtuSessionCheck.State.UNKNOWN,
                SdjtuSessionCheck.classifyStudentHome(200, null, maintenance));

        assertEquals(SdjtuSessionCheck.State.UNKNOWN,
                SdjtuSessionCheck.classifyStudentHome(503, null, ""));
        assertTrue(SdjtuSessionCheck.isLoginBody(casLogin));
        assertFalse(SdjtuSessionCheck.isLoginBody(activeMenu));
    }
}
