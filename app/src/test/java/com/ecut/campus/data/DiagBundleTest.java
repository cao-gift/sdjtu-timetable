package com.ecut.campus.data;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** 诊断包：快照命名、保留份数、zip 打包与路径安全。 */
public class DiagBundleTest {

    @Test
    public void snapshotNameIsSanitizedAndRecognized() {
        String n = DiagBundle.snapshotName("study-rooms/../x", 0L);
        assertTrue(n, n.startsWith("STUDY_ROOMS____X-"));
        assertTrue(n, DiagBundle.isSnapshotName(n));
        assertTrue(DiagBundle.snapshotName("", 0L).startsWith("UNKNOWN-"));
        assertFalse(DiagBundle.isSnapshotName("parse_failure_snapshot.json"));
        assertFalse(DiagBundle.isSnapshotName("../GRADES-20261125-210000-000.json"));
    }

    @Test
    public void pruneKeepsNewestAcrossKinds() {
        List<String> names = Arrays.asList(
                "GRADES-20261120-080000-000.json",
                "TIMETABLE-20261125-090000-000.json",
                "STUDY_ROOMS-20261122-100000-000.json",
                "notes.txt",
                "GRADES-20261125-090000-500.json");
        List<String> sorted = DiagBundle.newestFirst(names);
        assertEquals(4, sorted.size());
        assertEquals("GRADES-20261125-090000-500.json", sorted.get(0));
        assertEquals(Arrays.asList("STUDY_ROOMS-20261122-100000-000.json", "GRADES-20261120-080000-000.json"),
                DiagBundle.namesToPrune(names, 2));
        assertTrue(DiagBundle.namesToPrune(names, 10).isEmpty());
        assertEquals(4, DiagBundle.namesToPrune(names, -1).size());
    }

    @Test
    public void zipKeepsOrderSkipsNullAndBlocksTraversal() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put("README.txt", "hello".getBytes(StandardCharsets.UTF_8));
        entries.put("missing.json", null);
        entries.put("../../etc/passwd", "x".getBytes(StandardCharsets.UTF_8));
        entries.put("snapshots/GRADES-20261125-090000-000.json", "{}".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DiagBundle.writeZip(bos, entries);

        List<String> names = new ArrayList<>();
        try (ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(bos.toByteArray()), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) names.add(e.getName());
        }
        assertEquals(Arrays.asList("README.txt", "etc/passwd", "snapshots/GRADES-20261125-090000-000.json"), names);
        assertEquals("file", DiagBundle.safeEntryName("../.."));
        assertEquals("a/b.txt", DiagBundle.safeEntryName("C:\\a\\b.txt"));
    }

    @Test
    public void readmeStatesPrivacy() {
        String r = DiagBundle.readme("1.4.2 (74)", 3, "2026-11-25 21:00:00");
        assertTrue(r.contains("snapshots/"));
        assertTrue(r.contains("3 份"));
        assertTrue(r.contains("不含账号密码"));
    }
}
