package com.ecut.campus;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

/** 诊断日志脱敏：查询参数、路径里的 jsessionid、长数字。 */
public class DiagLogScrubTest {

    @Test
    public void stripsJsessionidAndQuery() {
        String out = DiagLog.scrub("finished: http://jw.example.edu.cn/jsxsd/xskb/xskb_list.do;jsessionid=AB12CD34EF?x=1");
        assertFalse(out, out.contains("AB12CD34EF"));
        assertEquals("finished: http://jw.example.edu.cn/jsxsd/xskb/xskb_list.do;…?…", out);
        assertEquals("page: https://h/jwglxt/xtgl/index.html?…", DiagLog.scrub("page: https://h/jwglxt/xtgl/index.html?gnmkdm=N1&su=2023123456"));
    }

    @Test
    public void parseFailureSnapshotStripsPiiAndKeepsStructure() {
        String rawStructure = "{\"path\":\"/jsxsd/kscj/cjcx_list?ticket=ST-123456\",\"tables\":[{\"id\":\"dataList\",\"rows\":5,\"maxCols\":11}],\"labels\":{\"学生姓名张三\":\"2024000000\"}}";
        String snap = DiagLog.buildParseFailureSnapshot("GRADES",
                "解析失败 https://wwebvpn.sdjtu.edu.cn/jsxsd/kscj/cjcx_list;jsessionid=SECRET99?su=2024000000",
                rawStructure, "姓名张三 学号2024000000 邮箱 test@example.com");
        assertFalse(snap.isEmpty());
        assertFalse("must strip jsessionid", snap.contains("SECRET99"));
        assertFalse("must strip student id", snap.contains("2024000000"));
        assertFalse("must strip student name", snap.contains("张三"));
        assertFalse("must strip email", snap.contains("test@example.com"));
        assertFalse("must strip query ticket", snap.contains("ST-123456"));
        org.junit.Assert.assertTrue("keeps table id for fixture", snap.contains("dataList"));
    }
}
