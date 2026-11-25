package com.ecut.campus;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SdjtuLoginFormTest {
    private static final String HOST = "wwebvpn.sdjtu.edu.cn";
    private static final String PATH = "/https/77726476706e69737468656265737421f3f652cd69236c5a6a1dc7a99c406d36e9/cas/login";
    private static final String SERVICE = "?service=https%3A%2F%2Fwwebvpn.sdjtu.edu.cn%2Flogin%3Fcas_login%3Dtrue";
    private static final String PAGE = "https://" + HOST + PATH;

    @Test public void exactSchoolCasPageAndWebVpnReturnServiceAreRecognized() {
        assertTrue(SdjtuLoginForm.isLoginPage(PAGE + SERVICE));
        assertTrue(SdjtuLoginForm.isLoginPage(PAGE + SERVICE.replace("%3A", ":")));
        assertTrue(SdjtuLoginForm.isLoginPage("https://" + HOST + ":443" + PATH + SERVICE));
        assertTrue(SdjtuLoginForm.isLoginPage(PAGE + "?service=http%3A%2F%2F192.168.253.164%2Fsso.jsp"));
        assertTrue(SdjtuLoginForm.isLoginPage(PAGE + "?service=https%3A%2F%2Fwwebvpn.sdjtu.edu.cn%2Fhttp%2F77726476706e69737468656265737421a1ae13d27666301e2c5ddae2c90377%2Fsso.jsp"));
        assertTrue(SdjtuLoginForm.isLoginPage(PAGE + "?service=https%3A%2F%2Fzhjwgl.sdjtu.edu.cn%2Fjsxsd%2Fframework%2FxsMain.jsp"));
    }

    @Test public void otherApplicationsAndMalformedRedirectsCannotReceiveCredentials() {
        String[] denied = {
                null, "", PAGE, PAGE + SERVICE + "#", PAGE + SERVICE + "&next=x",
                PAGE + SERVICE + SERVICE.replace("?", "&"), PAGE + SERVICE.replace("true", "false"),
                PAGE + SERVICE.replace("%3A", "%253A"), PAGE + SERVICE.replace("wwebvpn", "other"),
                PAGE + "?service=%", PAGE.replace("https://", "http://") + SERVICE,
                PAGE.replace(HOST, HOST + ".example.invalid") + SERVICE,
                PAGE.replace(HOST, HOST + ":8443") + SERVICE,
                PAGE.replace(HOST, "demo@" + HOST) + SERVICE,
                PAGE.replace("/cas/login", "/other/../cas/login") + SERVICE,
                PAGE.replace("/cas/login", "/cas/%6cogin") + SERVICE,
                PAGE.replace("f3f652cd", "00000000") + SERVICE,
                "https://cas1.sdjtu.edu.cn/cas/login" + SERVICE,
                PAGE + "?service=http%3A%2F%2F192.168.253.193%2Fzxcas",
                PAGE + "?service=https%3A%2F%2Fzhjwgl.sdjtu.edu.cn%2Fjsxsd%2F..%2Fother",
                PAGE + "?service=https%3A%2F%2Fzhjwgl.sdjtu.edu.cn%2Fjsxsd%2Fframework%2FxsMain.jsp%3Fnext%3Dhttps%3A%2F%2Fevil.example"
        };
        for (String url : denied) assertFalse(SdjtuLoginForm.isLoginPage(url));
    }
}
