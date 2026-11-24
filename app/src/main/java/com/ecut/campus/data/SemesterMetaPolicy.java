package com.ecut.campus.data;

/**
 * Semester metadata ownership rules.
 *
 * The archive is the only writable source of truth. timetable.json and Prefs
 * are mirrors for legacy readers. Pure Java so JVM tests can verify migration
 * and precedence without Android.
 */
public final class SemesterMetaPolicy {

    public static final int MIN_WEEKS = 1;
    /** Keep the archive policy aligned with the existing settings/UI limit. */
    public static final int MAX_WEEKS = 30;
    public static final int DEFAULT_WEEKS = 20;

    public static final int ORIGIN_NONE = 0;
    public static final int ORIGIN_QIANGZHI = 1;
    public static final int ORIGIN_FOREIGN = 2;

    private SemesterMetaPolicy() {}

    /**
     * 检查课表中教务来源课程（source=jwglxt）的归属位掩码：
     * 手动/导入课程为中性（返回 0），强智课程置 ORIGIN_QIANGZHI，其余教务课程（如正方）置 ORIGIN_FOREIGN。
     */
    public static int inspectJwglxtOrigin(Timetable t) {
        if (t == null || t.courses == null) return ORIGIN_NONE;
        int flags = ORIGIN_NONE;
        for (Timetable.Course c : t.courses) {
            if (c == null || !Timetable.SOURCE_JWGLXT.equals(c.source)) continue;
            if (c.key != null && c.key.startsWith("qiangzhi_")) flags |= ORIGIN_QIANGZHI;
            else flags |= ORIGIN_FOREIGN;
        }
        return flags;
    }

    public static int clampWeeks(int weeks) {
        return Math.max(MIN_WEEKS, Math.min(MAX_WEEKS, weeks));
    }

    /**
     * Backfill only a missing archive start date from a legacy timetable.json
     * mirror. An existing archive date always wins, even if the mirror is newer,
     * because background writes must not revive stale dates.
     */
    public static boolean shouldSeedStartDate(String archiveSemesterId,
                                              String mirrorSemesterId,
                                              String archiveStartDate,
                                              String mirrorStartDate) {
        if (!sameSemester(archiveSemesterId, mirrorSemesterId)) return false;
        boolean hasArchiveStart = archiveStartDate != null && !archiveStartDate.isEmpty();
        boolean hasMirrorStart = mirrorStartDate != null && !mirrorStartDate.isEmpty();
        return !hasArchiveStart && hasMirrorStart;
    }

    /**
     * Total weeks are an archive value too. A legacy mirror may fill the field
     * only when the archive JSON did not contain that field at all; a present
     * value (including the default 20) must not be replaced by the mirror.
     */
    public static boolean shouldSeedTotalWeeks(String archiveSemesterId,
                                               String mirrorSemesterId,
                                               boolean archiveHasTotalWeeks,
                                               int mirrorTotalWeeks) {
        if (!sameSemester(archiveSemesterId, mirrorSemesterId)) return false;
        if (archiveHasTotalWeeks) return false;
        return mirrorTotalWeeks >= MIN_WEEKS && mirrorTotalWeeks <= MAX_WEEKS;
    }

    // 2026-11-23 是山交 2026 秋季第 14 周周一。
    static final String SDJTU_FALL_2026_ID = "2026-2027-1";
    static final String SDJTU_FALL_2026_START = "2026-08-24";

    static boolean isSdjtuFall2026(Timetable t) {
        if (t == null || !SDJTU_FALL_2026_ID.equals(t.semester.id)) return false;
        // 旧归档没有 schoolId，切换学校又会保留课表。只校正含原生强智课程的学期。
        boolean qiangzhi = false;
        for (Timetable.Course course : t.courses) {
            if (!Timetable.SOURCE_JWGLXT.equals(course.source)) continue;
            if (!course.key.startsWith("qiangzhi_")) return false;
            qiangzhi = true;
        }
        return qiangzhi;
    }

    /** 为本学期的山交课表补开学日期，并纠正已知的旧自动日期；手动校准优先。 */
    public static boolean fillSdjtuStartDate(Timetable t) {
        return isSdjtuFall2026(t) && repairSdjtuStartDate(t.semester, false);
    }

    /** 在已确认的山交学期上执行一次：旧迁移曾把东华日期写成“已手动设置”。 */
    static boolean repairSdjtuStartDate(Timetable.Semester semester, boolean oldMigrationRan) {
        boolean oldDefault = "2026-09-07".equals(semester.startDate)
                && (oldMigrationRan || !semester.startDateTouched);
        if (!oldDefault && (!semester.startDate.isEmpty() || semester.startDateTouched)) return false;
        semester.startDate = SDJTU_FALL_2026_START;
        semester.startDateTouched = false;
        return true;
    }

    private static boolean sameSemester(String archiveSemesterId, String mirrorSemesterId) {
        return archiveSemesterId != null
                && !archiveSemesterId.isEmpty()
                && archiveSemesterId.equals(mirrorSemesterId);
    }

    /**
     * Archive wins. A missing archive date is preserved as missing rather than
     * copied from the mirror; explicit user saves update the archive first.
     */
    public static String resolveStartDate(String archiveStartDate) {
        return archiveStartDate == null ? "" : archiveStartDate;
    }

    public static int resolveTotalWeeks(int archiveTotalWeeks) {
        return clampWeeks(archiveTotalWeeks);
    }
}
