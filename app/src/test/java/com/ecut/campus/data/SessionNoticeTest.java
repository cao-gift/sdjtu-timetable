package com.ecut.campus.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** 会话失效统一提示：何时打断用户、各入口文案一致。 */
public class SessionNoticeTest {

    @Test
    public void validSessionShowsNothing() {
        SessionNotice.Kind k = SessionNotice.of(false, false, false, false);
        assertEquals(SessionNotice.Kind.NONE, k);
        assertFalse(SessionNotice.needsAction(k));
        assertEquals("", SessionNotice.subtitleHint(k));
    }

    @Test
    public void savedCredentialsHandleExpiryQuietly() {
        SessionNotice.Kind k = SessionNotice.of(true, true, false, false);
        assertEquals(SessionNotice.Kind.AUTO, k);
        assertFalse(SessionNotice.needsAction(k));
        assertEquals("", SessionNotice.subtitleHint(k));
        assertEquals("● 登录已失效", SessionNotice.badge(k));
    }

    @Test
    public void expiredWithoutCredentialsAsksToLogin() {
        SessionNotice.Kind k = SessionNotice.of(true, false, false, false);
        assertEquals(SessionNotice.Kind.LOGIN, k);
        assertTrue(SessionNotice.needsAction(k));
        assertEquals("需要重新登录", SessionNotice.title(k));
        assertEquals("重新登录", SessionNotice.action(k));
        assertEquals("登录已过期，请重新登录", SessionNotice.status(k));
    }

    @Test
    public void pausedAutoLoginAsksToVerify() {
        SessionNotice.Kind k = SessionNotice.of(true, true, true, false);
        assertEquals(SessionNotice.Kind.VERIFY, k);
        assertTrue(SessionNotice.needsAction(k));
        assertEquals("去验证", SessionNotice.action(k));
        assertEquals("● 需验证", SessionNotice.badge(k));
    }

    @Test
    public void pageLoginFailureWinsOverAutoLogin() {
        // 自动登录已经试过但查询仍要求登录：必须让用户动手。
        assertEquals(SessionNotice.Kind.LOGIN, SessionNotice.of(true, true, false, true));
        assertEquals(SessionNotice.Kind.LOGIN, SessionNotice.of(false, false, false, true));
    }
}
