package com.ecut.campus.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.List;

public class UpdateManifestTest {

    private static final String SAMPLE_SHA =
            "18e0ca39f04b679c4b8c6c2f4d72b4275268b2c4881455c4bba936b661150f29";

    @Test
    public void normalizeBaseUrlEnforcesHttpsAndTrimsSlash() {
        assertEquals("https://lvlv.freeblock.cn", UpdateManifest.normalizeBaseUrl("https://lvlv.freeblock.cn/"));
        assertEquals("https://lvlv.freeblock.cn", UpdateManifest.normalizeBaseUrl("lvlv.freeblock.cn///"));
        assertEquals("", UpdateManifest.normalizeBaseUrl("http://lvlv.freeblock.cn"));
        assertEquals("", UpdateManifest.normalizeBaseUrl("javascript:alert(1)"));
        assertEquals("", UpdateManifest.normalizeBaseUrl("   "));
        assertEquals("", UpdateManifest.normalizeBaseUrl(null));
    }

    @Test
    public void checkEndpointsPrioritizesOfficialSiteBeforeMirrorsAndGitHub() {
        List<UpdateManifest.Endpoint> eps = UpdateManifest.checkEndpoints(
                "https://lvlv.freeblock.cn/", "cao-gift/sdjtu-timetable");
        assertEquals(6, eps.size());
        assertEquals("https://lvlv.freeblock.cn/latest.json", eps.get(0).url);
        assertEquals(UpdateManifest.KIND_OFFICIAL_JSON, eps.get(0).kind);
        assertTrue(eps.get(1).url.startsWith("https://ghfast.top/https://github.com/cao-gift/sdjtu-timetable/"));
        assertTrue(eps.get(2).url.startsWith("https://gh-proxy.com/https://github.com/cao-gift/sdjtu-timetable/"));
        assertTrue(eps.get(3).url.startsWith("https://gh-proxy.org/https://github.com/cao-gift/sdjtu-timetable/"));
        assertEquals("https://fastly.jsdelivr.net/gh/cao-gift/sdjtu-timetable@site/latest.json", eps.get(4).url);
        assertEquals("https://api.github.com/repos/cao-gift/sdjtu-timetable/releases/latest", eps.get(5).url);
        assertEquals(UpdateManifest.KIND_GITHUB_API, eps.get(5).kind);
    }

    @Test
    public void parseOfficialJsonResolvesRelativePathsAndFallback() {
        String json = "{"
                + "\"tag\":\"v1.3.18\","
                + "\"versionCode\":70,"
                + "\"name\":\"驴驴课表 v1.3.18\","
                + "\"notes\":\"接入国内官网直连更新\","
                + "\"apkUrl\":\"/downloads/lvlv-v1.3.18.apk\","
                + "\"githubApkUrl\":\"https://github.com/cao-gift/sdjtu-timetable/releases/download/v1.3.18/lvlv-v1.3.18.apk\","
                + "\"pageUrl\":\"/\","
                + "\"size\":3569372,"
                + "\"sha256\":\"" + SAMPLE_SHA.toUpperCase() + "\""
                + "}";

        UpdateManifest.ReleaseInfo info = UpdateManifest.parseOfficialJson(
                json, "https://lvlv.freeblock.cn", "https://lvlv.freeblock.cn/");
        assertNotNull(info);
        assertEquals("v1.3.18", info.tag);
        assertEquals(70, info.versionCode);
        assertEquals("https://lvlv.freeblock.cn/downloads/lvlv-v1.3.18.apk", info.apkUrl);
        assertEquals("https://github.com/cao-gift/sdjtu-timetable/releases/download/v1.3.18/lvlv-v1.3.18.apk",
                info.fallbackApkUrl);
        assertEquals("https://lvlv.freeblock.cn/", info.pageUrl);
        assertEquals(3569372L, info.size);
        assertEquals(SAMPLE_SHA, info.sha256);

        // 没有官网 baseUrl 时，相对路径回退到 githubApkUrl
        UpdateManifest.ReleaseInfo noSite = UpdateManifest.parseOfficialJson(
                json, "", "https://github.com/cao-gift/sdjtu-timetable/releases");
        assertNotNull(noSite);
        assertEquals("https://github.com/cao-gift/sdjtu-timetable/releases/download/v1.3.18/lvlv-v1.3.18.apk",
                noSite.apkUrl);
        assertEquals("", noSite.fallbackApkUrl);
        assertEquals("https://github.com/cao-gift/sdjtu-timetable/releases", noSite.pageUrl);
    }

    @Test
    public void parseOfficialJsonRejectsHttpAndInvalidTag() {
        String httpOnly = "{\"tag\":\"v1.3.18\",\"apkUrl\":\"http://evil.example/a.apk\",\"sha256\":\"bad\"}";
        UpdateManifest.ReleaseInfo info = UpdateManifest.parseOfficialJson(httpOnly, "", "https://lvlv.freeblock.cn/");
        assertNotNull(info);
        assertEquals("", info.apkUrl);
        assertEquals("", info.sha256);

        assertNull(UpdateManifest.parseOfficialJson("{\"notes\":\"no tag\"}", "", ""));
        assertNull(UpdateManifest.parseOfficialJson("not-json", "", ""));
    }

    @Test
    public void parseGitHubReleaseJsonExtractsApkAndDigest() {
        String ghJson = "{"
                + "\"tag_name\":\"v1.3.17\","
                + "\"name\":\"驴驴课表 v1.3.17\","
                + "\"body\":\"更新说明\","
                + "\"html_url\":\"https://github.com/cao-gift/sdjtu-timetable/releases/tag/v1.3.17\","
                + "\"assets\":["
                + "{\"name\":\"latest.json\",\"browser_download_url\":\"https://github.com/cao-gift/sdjtu-timetable/releases/download/v1.3.17/latest.json\",\"size\":512},"
                + "{\"name\":\"lvlv-v1.3.17.apk\",\"browser_download_url\":\"https://github.com/cao-gift/sdjtu-timetable/releases/download/v1.3.17/lvlv-v1.3.17.apk\",\"size\":3569372,\"digest\":\"sha256:" + SAMPLE_SHA + "\"}"
                + "]}";
        UpdateManifest.ReleaseInfo info = UpdateManifest.parseGitHubReleaseJson(ghJson, "https://lvlv.freeblock.cn/");
        assertNotNull(info);
        assertEquals("v1.3.17", info.tag);
        assertEquals("https://github.com/cao-gift/sdjtu-timetable/releases/download/v1.3.17/lvlv-v1.3.17.apk", info.apkUrl);
        assertEquals(3569372L, info.size);
        assertEquals(SAMPLE_SHA, info.sha256);
    }

    @Test
    public void downloadSourcesPutsOfficialFirstThenMirrorsThenGitHub() {
        String official = "https://lvlv.freeblock.cn/downloads/lvlv-v1.3.18.apk";
        String github = "https://github.com/cao-gift/sdjtu-timetable/releases/download/v1.3.18/lvlv-v1.3.18.apk";
        List<UpdateManifest.DownloadSource> sources = UpdateManifest.downloadSources(official, github);
        assertEquals(5, sources.size());
        assertEquals("官方下载", sources.get(0).label);
        assertEquals(official, sources.get(0).url);
        assertEquals("https://ghfast.top/" + github, sources.get(1).url);
        assertEquals("https://gh-proxy.com/" + github, sources.get(2).url);
        assertEquals("https://gh-proxy.org/" + github, sources.get(3).url);
        assertEquals(github, sources.get(4).url);
    }

    @Test
    public void isNewerHandlesVersionNameAndVersionCode() {
        UpdateManifest.ReleaseInfo newerTag = new UpdateManifest.ReleaseInfo();
        newerTag.tag = "v1.3.18";
        newerTag.versionCode = 70;
        assertTrue(UpdateManifest.isNewer(newerTag, "1.3.17", 69));

        UpdateManifest.ReleaseInfo sameTagHigherCode = new UpdateManifest.ReleaseInfo();
        sameTagHigherCode.tag = "v1.3.17";
        sameTagHigherCode.versionCode = 70;
        assertTrue(UpdateManifest.isNewer(sameTagHigherCode, "1.3.17", 69));

        UpdateManifest.ReleaseInfo sameTagSameCode = new UpdateManifest.ReleaseInfo();
        sameTagSameCode.tag = "v1.3.17";
        sameTagSameCode.versionCode = 69;
        assertFalse(UpdateManifest.isNewer(sameTagSameCode, "1.3.17", 69));

        UpdateManifest.ReleaseInfo olderTagHigherCode = new UpdateManifest.ReleaseInfo();
        olderTagHigherCode.tag = "v1.3.16";
        olderTagHigherCode.versionCode = 99;
        assertFalse(UpdateManifest.isNewer(olderTagHigherCode, "1.3.17", 69));
    }
}
