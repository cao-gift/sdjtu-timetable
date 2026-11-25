package com.ecut.campus.data;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 诊断包的纯 Java 部分：解析失败快照的命名与保留策略、zip 打包（无 Android 依赖，便于 JVM 单测）。
 * 诊断包只收录已经脱敏的文件，不含账号密码、Cookie、学号姓名。
 */
public final class DiagBundle {

    /** 每种同步最多保留的解析失败快照份数（全部种类合计）。 */
    public static final int MAX_SNAPSHOTS = 12;
    public static final String SNAPSHOT_SUFFIX = ".json";

    private DiagBundle() {}

    /** 快照文件名：{@code KIND-yyyyMMdd-HHmmss-SSS.json}，KIND 只保留大写字母、数字与下划线。 */
    public static String snapshotName(String kind, long timeMs) {
        String k = kind == null ? "" : kind.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9_]", "_");
        if (k.isEmpty()) k = "UNKNOWN";
        if (k.length() > 32) k = k.substring(0, 32);
        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(new Date(timeMs));
        return k + "-" + ts + SNAPSHOT_SUFFIX;
    }

    /** 是否为本类生成的快照文件名。 */
    public static boolean isSnapshotName(String name) {
        return name != null && name.matches("[A-Z0-9_]{1,32}-\\d{8}-\\d{6}-\\d{3}\\.json");
    }

    /** 文件名中的时间部分，用于排序。 */
    private static String stamp(String name) {
        int dash = name.indexOf('-');
        return dash < 0 ? "" : name.substring(dash + 1);
    }

    /** 按时间从新到旧排列快照文件名，忽略不认识的文件。 */
    public static List<String> newestFirst(List<String> names) {
        List<String> out = new ArrayList<>();
        if (names != null) for (String n : names) if (isSnapshotName(n)) out.add(n);
        Collections.sort(out, (a, b) -> {
            int c = stamp(b).compareTo(stamp(a));
            return c != 0 ? c : b.compareTo(a);
        });
        return out;
    }

    /** 超过保留份数时需要删除的旧快照。 */
    public static List<String> namesToPrune(List<String> names, int keep) {
        List<String> sorted = newestFirst(names);
        int k = Math.max(0, keep);
        return sorted.size() <= k ? new ArrayList<>() : new ArrayList<>(sorted.subList(k, sorted.size()));
    }

    /** zip 内路径：去掉盘符、上级目录与开头斜杠，防止解压越界。 */
    public static String safeEntryName(String name) {
        if (name == null) return "file";
        String n = name.replace('\\', '/');
        List<String> parts = new ArrayList<>();
        for (String p : n.split("/")) {
            if (p.isEmpty() || p.equals(".") || p.equals("..") || p.contains(":")) continue;
            parts.add(p);
        }
        return parts.isEmpty() ? "file" : String.join("/", parts);
    }

    /** 按插入顺序写 zip；值为 null 的条目跳过。 */
    public static void writeZip(OutputStream out, Map<String, byte[]> entries) throws IOException {
        ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8);
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            if (e.getValue() == null) continue;
            zip.putNextEntry(new ZipEntry(safeEntryName(e.getKey())));
            zip.write(e.getValue());
            zip.closeEntry();
        }
        zip.finish();
    }

    /** 诊断包里的说明文件。 */
    public static String readme(String version, int snapshotCount, String generatedAt) {
        return "驴驴课表诊断包\n"
                + "版本: " + (version == null ? "" : version) + "\n"
                + "生成时间: " + (generatedAt == null ? "" : generatedAt) + "\n\n"
                + "report.txt      全局诊断报告（设备、设置、登录状态、课表结构、运行日志）\n"
                + "snapshots/      解析失败快照 " + snapshotCount + " 份（已脱敏，可直接整理成测试 fixture）\n"
                + "page_sample.json  最近一次「导入此页」的页面结构样本（只含标签与字段名）\n"
                + "kblist.json     最近一次教务原始课表（只留排课字段）\n\n"
                + "隐私：不含账号密码、Cookie、会话令牌；学号等长数字、姓名、邮箱、URL 参数均已去除。\n";
    }
}
