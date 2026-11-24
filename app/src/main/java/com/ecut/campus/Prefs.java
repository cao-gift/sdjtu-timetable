package com.ecut.campus;

import android.content.Context;
import android.content.SharedPreferences;

import com.ecut.campus.data.SemesterMetaPolicy;

/** 简单偏好设置封装 */
public final class Prefs {

    private static final String FILE = "ecut_campus";
    private static final String KEY_PORTAL_SESSION = "portal_session_ready";
    private static final String KEY_CURRENT_WEEK = "current_week";
    private static final String KEY_LAST_SYNC_TIME = "last_sync_time";
    private static final String KEY_DISPLAY_DAYS = "display_days";
    private static final String KEY_SHOW_INACTIVE = "show_inactive_courses";
    private static final String KEY_SHOW_COURSE_CODE = "show_course_code";
    private static final String KEY_HIDDEN_LOCATION_PHRASES = "hidden_location_phrases";
    private static final String KEY_SEMESTER_START = "semester_start_date";
    private static final String KEY_TOTAL_WEEKS = "semester_total_weeks";
    private static final String KEY_GRADES_DEMO = "grades_demo";

    private Prefs() {}

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    // ===== 1.0：检查更新 =====
    public static boolean updateAutoCheck(Context ctx) {
        return sp(ctx).getBoolean("update_auto", true);
    }

    public static void setUpdateAutoCheck(Context ctx, boolean on) {
        sp(ctx).edit().putBoolean("update_auto", on).apply();
    }

    public static long updateCheckedAt(Context ctx) {
        return sp(ctx).getLong("update_checked_at", 0L);
    }

    public static void setUpdateCheckedAt(Context ctx, long t) {
        sp(ctx).edit().putLong("update_checked_at", t).apply();
    }

    public static String updateSkippedTag(Context ctx) {
        return sp(ctx).getString("update_skipped", "");
    }

    public static void setUpdateSkippedTag(Context ctx, String tag) {
        sp(ctx).edit().putString("update_skipped", tag).apply();
    }

    /** 是否已经在设置页完成统一门户/办事大厅会话初始化 */
    public static boolean hasPortalSession(Context ctx) {
        return sp(ctx).getBoolean(KEY_PORTAL_SESSION, false);
    }

    public static void setPortalSession(Context ctx, boolean ready) {
        sp(ctx).edit().putBoolean(KEY_PORTAL_SESSION, ready).apply();
    }

    /** 用户当前查看/锚定的教学周 */
    public static int currentWeek(Context ctx) {
        return sp(ctx).getInt(KEY_CURRENT_WEEK, 1);
    }

    public static boolean hasCurrentWeek(Context ctx) {
        return sp(ctx).contains(KEY_CURRENT_WEEK);
    }

    public static void setCurrentWeek(Context ctx, int week) {
        sp(ctx).edit().putInt(KEY_CURRENT_WEEK,
                Math.max(1, Math.min(WeekMath.OPEN_WEEKS_MAX, week))).apply();
    }

    /** 上次同步成功的时间戳文本 */
    public static String lastSyncTime(Context ctx) {
        return sp(ctx).getString(KEY_LAST_SYNC_TIME, "");
    }

    public static void setLastSyncTime(Context ctx, String text) {
        sp(ctx).edit().putString(KEY_LAST_SYNC_TIME, text == null ? "" : text).apply();
    }

    /** 课表显示天数：7 或 5，默认七天 */
    public static int displayDays(Context ctx) {
        return sp(ctx).getInt(KEY_DISPLAY_DAYS, 7) == 5 ? 5 : 7;
    }

    public static void setDisplayDays(Context ctx, int days) {
        sp(ctx).edit().putInt(KEY_DISPLAY_DAYS, days == 5 ? 5 : 7).apply();
    }

    /** 是否显示非本周课程，默认关闭 */
    public static boolean showInactiveCourses(Context ctx) {
        return sp(ctx).getBoolean(KEY_SHOW_INACTIVE, false);
    }

    public static void setShowInactiveCourses(Context ctx, boolean show) {
        sp(ctx).edit().putBoolean(KEY_SHOW_INACTIVE, show).apply();
    }

    public static boolean showCourseCode(Context ctx) {
        return sp(ctx).getBoolean(KEY_SHOW_COURSE_CODE, false);
    }

    public static void setShowCourseCode(Context ctx, boolean show) {
        sp(ctx).edit().putBoolean(KEY_SHOW_COURSE_CODE, show).apply();
    }

    public static String hiddenLocationPhrases(Context ctx) {
        return sp(ctx).getString(KEY_HIDDEN_LOCATION_PHRASES, "");
    }

    public static void setHiddenLocationPhrases(Context ctx, String phrases) {
        sp(ctx).edit().putString(KEY_HIDDEN_LOCATION_PHRASES, phrases).apply();
    }

    /**
     * 兼容缓存：主源是 timetable_semesters.json。
     * 只允许 SemesterArchive 刷新，业务代码应读写归档。
     */
    public static String semesterStartDate(Context ctx) {
        return sp(ctx).getString(KEY_SEMESTER_START, "");
    }

    /** 仅供 SemesterArchive 镜像同步；业务代码不要调用。 */
    public static void setSemesterStartDate(Context ctx, String date) {
        sp(ctx).edit().putString(KEY_SEMESTER_START, date == null ? "" : date).apply();
    }

    /**
     * 界面上可用的周跨度。不再让用户设定「学期总周数」：
     * 只看开学日期，周次一直往后排（见 WeekMath.openWeekSpan）。
     */
    public static int totalWeeks(Context ctx) {
        return WeekMath.openWeekSpan(WeekMath.normalizeToMonday(semesterStartDate(ctx)));
    }

    /** 归档里存的旧周数字段，只用于原样回写，不参与界面。 */
    public static int storedTotalWeeks(Context ctx) {
        return SemesterMetaPolicy.clampWeeks(
                sp(ctx).getInt(KEY_TOTAL_WEEKS, SemesterMetaPolicy.DEFAULT_WEEKS));
    }

    /** 仅供 SemesterArchive 镜像同步；业务代码不要调用。 */
    public static void setTotalWeeks(Context ctx, int weeks) {
        sp(ctx).edit().putInt(KEY_TOTAL_WEEKS,
                SemesterMetaPolicy.clampWeeks(weeks)).apply();
    }

    /** 当前成绩是否为主动载入的演示数据 */
    public static boolean isGradesDemo(Context ctx) {
        return sp(ctx).getBoolean(KEY_GRADES_DEMO, false);
    }

    public static void setGradesDemo(Context ctx, boolean demo) {
        sp(ctx).edit().putBoolean(KEY_GRADES_DEMO, demo).apply();
    }

    // ===== 0.4：外观、同步状态、新成绩 =====
    /** 一屏显示全部 10 节（压缩课块高度）。 */
    public static boolean fitAllSections(Context ctx) { return sp(ctx).getBoolean("fit_all_sections", false); }
    public static void setFitAllSections(Context ctx, boolean v) { sp(ctx).edit().putBoolean("fit_all_sections", v).apply(); }

    /** 0 纸墨，1 磨砂玻璃。 */
    public static int theme(Context ctx) { return sp(ctx).getInt("ui_theme", 0); }
    public static void setTheme(Context ctx, int t) { sp(ctx).edit().putInt("ui_theme", t).commit(); }

    public static int nightMode(Context ctx) { return sp(ctx).getInt("night_mode", 0); }
    public static void setNightMode(Context ctx, int m) { sp(ctx).edit().putInt("night_mode", m).apply(); }

    /** 上次成功同步课表的时间戳（毫秒）。 */
    public static long timetableSyncedAt(Context ctx) { return sp(ctx).getLong("tt_synced_at", 0L); }
    public static void setTimetableSyncedAt(Context ctx, long t) { sp(ctx).edit().putLong("tt_synced_at", t).apply(); }

    /** 门户/零信任登录是否已失效（同步时检测到跳回登录页）。 */
    public static boolean sessionExpired(Context ctx) { return sp(ctx).getBoolean("session_expired", false); }
    public static void setSessionExpired(Context ctx, boolean v) { sp(ctx).edit().putBoolean("session_expired", v).apply(); }

    /** 新出、尚未查看的成绩 key（GradeDiff.key），用 \n 分隔。 */
    public static java.util.Set<String> newGradeKeys(Context ctx) {
        String raw = sp(ctx).getString("new_grade_keys", "");
        java.util.Set<String> set = new java.util.HashSet<>();
        for (String k : raw.split("\n")) if (!k.isEmpty()) set.add(k);
        return set;
    }
    public static void setNewGradeKeys(Context ctx, java.util.Collection<String> keys) {
        sp(ctx).edit().putString("new_grade_keys", String.join("\n", keys)).apply();
    }

    public static boolean gradeNotify(Context ctx) { return sp(ctx).getBoolean("grade_notify", true); }
    public static void setGradeNotify(Context ctx, boolean v) { sp(ctx).edit().putBoolean("grade_notify", v).apply(); }

    /** 课前提醒，默认关。 */
    public static boolean classRemind(Context ctx) { return sp(ctx).getBoolean("class_remind", false); }
    public static void setClassRemind(Context ctx, boolean v) { sp(ctx).edit().putBoolean("class_remind", v).apply(); }
    /** 提前几分钟提醒（5/10/15/20），默认 10。 */
    public static int classRemindLead(Context ctx) { return sp(ctx).getInt("class_remind_lead", 10); }
    public static void setClassRemindLead(Context ctx, int m) { sp(ctx).edit().putInt("class_remind_lead", m).apply(); }

    public static String shareName(Context ctx) { return sp(ctx).getString("share_name", ""); }
    public static void setShareName(Context ctx, String n) { sp(ctx).edit().putString("share_name", n).apply(); }

    /**
     * 判断升级前的本地状态是否属于非山交学校（需要清理旧校会话、成绩缓存与未读提醒）。
     * 纯 Java 可测，便于覆盖单校升级与跨校迁移边界。
     */
    static boolean isForeignSchoolState(String previousSchoolId,
                                        boolean hasPortalHost,
                                        boolean hasCustomSchool,
                                        boolean hasCalendarImg,
                                        boolean hasPortalSession,
                                        long timetableSyncedAt,
                                        String lastSyncTime,
                                        boolean hasGradesFile,
                                        boolean hasUnreadGrades,
                                        String sdjtuAcademicBase,
                                        boolean hasSdjtuCredentials,
                                        boolean hasSdjtuStudyData,
                                        boolean hasQiangzhiTimetable,
                                        boolean hasForeignTimetable) {
        String previous = previousSchoolId == null ? "" : previousSchoolId.trim();
        if (!previous.isEmpty()) {
            return !"sdjtu".equals(previous);
        }
        boolean hasForeignArtifact = hasPortalHost || hasCustomSchool || hasCalendarImg || hasForeignTimetable;
        if (hasForeignArtifact) {
            return true;
        }
        boolean hasSdjtuSignal = !com.ecut.campus.data.SdjtuPortal.academicBase(sdjtuAcademicBase).isEmpty()
                || hasSdjtuCredentials || hasSdjtuStudyData || hasQiangzhiTimetable;
        if (hasSdjtuSignal) {
            return false;
        }
        return hasPortalSession
                || timetableSyncedAt > 0
                || (lastSyncTime != null && !lastSyncTime.isEmpty())
                || hasGradesFile
                || hasUnreadGrades;
    }

    /** 兼容从尚未清理 grades.json 的早期 1.3.14 覆盖升级：仅在确认尚未同步山交时补清旧校成绩与通知。 */
    static boolean shouldClearGradesOnFollowUp(boolean alreadyMigratedSchool,
                                               boolean alreadyMigratedGrades,
                                               boolean preserveLegacyDates,
                                               boolean hasPortalSession,
                                               String sdjtuAcademicBase,
                                               String lastSyncTime) {
        if (!alreadyMigratedSchool || alreadyMigratedGrades) return false;
        if (!preserveLegacyDates) return false;
        boolean syncedOnSdjtu = hasPortalSession
                || !com.ecut.campus.data.SdjtuPortal.academicBase(sdjtuAcademicBase).isEmpty()
                || (lastSyncTime != null && !lastSyncTime.isEmpty());
        return !syncedOnSdjtu;
    }

    /** 只迁移旧版学校入口、身份状态与非山交成绩缓存；课表归档、手动设置及本机山交凭据保留。 */
    static void migrateSchoolSettings(Context ctx) {
        SharedPreferences prefs = sp(ctx);
        boolean migratedSchool = prefs.getBoolean("sdjtu_only_migrated", false);
        boolean migratedGrades = prefs.getBoolean("sdjtu_only_grades_migrated", false);
        if (migratedSchool && migratedGrades) return;

        if (migratedSchool) {
            boolean clearForeignGrades = shouldClearGradesOnFollowUp(
                    true, false, preserveLegacySemesterDates(ctx),
                    hasPortalSession(ctx), sdjtuAcademicBase(ctx), lastSyncTime(ctx));
            SharedPreferences.Editor edit = prefs.edit();
            boolean done = true;
            if (clearForeignGrades) {
                com.ecut.campus.data.GradesStore.clear(ctx);
                Notify.cancelNewGrades(ctx);
                edit.remove("new_grade_keys").remove(KEY_GRADES_DEMO);
                done = !com.ecut.campus.data.GradesStore.exists(ctx);
            }
            if (done) edit.putBoolean("sdjtu_only_grades_migrated", true);
            edit.apply();
            return;
        }

        String previous = prefs.getString("school_id", "");
        boolean hasPortalHost = prefs.contains("portal_host");
        boolean hasCustomSchool = !prefs.getString("custom_school", "").trim().isEmpty();
        boolean hasCalendarImg = !prefs.getString("calendar_img", "").trim().isEmpty();
        boolean hasGradesFile = com.ecut.campus.data.GradesStore.exists(ctx);
        boolean hasUnreadGrades = !newGradeKeys(ctx).isEmpty();
        boolean hasSdjtuCreds = false;
        boolean hasSdjtuStudy = false;
        int origin = SemesterMetaPolicy.ORIGIN_NONE;
        if (previous.trim().isEmpty()) {
            hasSdjtuCreds = SdjtuCredentials.hasAnyState(ctx);
            hasSdjtuStudy = SdjtuStudyStore.hasAnySnapshot(ctx) || SdjtuStudyRoute.hasAnyRoute(ctx);
            origin = com.ecut.campus.data.SemesterArchive.inspectJwglxtOrigin(ctx);
        }
        boolean foreign = isForeignSchoolState(
                previous, hasPortalHost, hasCustomSchool, hasCalendarImg,
                hasPortalSession(ctx), timetableSyncedAt(ctx), lastSyncTime(ctx),
                hasGradesFile, hasUnreadGrades, sdjtuAcademicBase(ctx),
                hasSdjtuCreds, hasSdjtuStudy,
                (origin & SemesterMetaPolicy.ORIGIN_QIANGZHI) != 0,
                (origin & SemesterMetaPolicy.ORIGIN_FOREIGN) != 0);

        SharedPreferences.Editor edit = prefs.edit()
                .remove("school_id").remove("custom_school").remove("targets_json")
                .remove("portal_host").remove("calendar_img");
        boolean gradesDone = true;
        if (foreign) {
            com.ecut.campus.data.GradesStore.clear(ctx);
            Notify.cancelNewGrades(ctx);
            gradesDone = !com.ecut.campus.data.GradesStore.exists(ctx);
            edit.remove(KEY_PORTAL_SESSION).remove("session_expired")
                    .remove("sdjtu_academic_base").remove(KEY_LAST_SYNC_TIME)
                    .remove("tt_synced_at").remove("new_grade_keys")
                    .remove(KEY_GRADES_DEMO)
                    .putBoolean("preserve_legacy_semester_dates", true);
        }
        edit.putBoolean("sdjtu_only_migrated", true);
        if (gradesDone) edit.putBoolean("sdjtu_only_grades_migrated", true);
        edit.apply();
    }

    public static boolean preserveLegacySemesterDates(Context ctx) {
        return sp(ctx).getBoolean("preserve_legacy_semester_dates", false);
    }

    static String sdjtuAcademicBase(Context ctx) {
        return sp(ctx).getString("sdjtu_academic_base", "");
    }

    static void setSdjtuAcademicBase(Context ctx, String base) {
        sp(ctx).edit().putString("sdjtu_academic_base", base).apply();
    }

    /** 小组件背景：false = 默认纸色，true = 自选图片（files/widget_bg.jpg）。版本号每换一次图 / 改一次遮罩就变，用来刷新缓存。 */
    public static boolean widgetBgImage(Context ctx) { return sp(ctx).getBoolean("widget_bg_image", false); }
    public static void setWidgetBgImage(Context ctx, boolean on) { sp(ctx).edit().putBoolean("widget_bg_image", on).putLong("widget_bg_ver", System.currentTimeMillis()).commit(); }
    public static long widgetBgVer(Context ctx) { return sp(ctx).getLong("widget_bg_ver", 0L); }
    /** 遮罩强度 %，默认 35（见 data/WidgetLook）。 */
    public static int widgetMask(Context ctx) { return com.ecut.campus.data.WidgetLook.clampMask(sp(ctx).getInt("widget_mask", com.ecut.campus.data.WidgetLook.MASK_DEFAULT)); }
    public static void setWidgetMask(Context ctx, int p) { sp(ctx).edit().putInt("widget_mask", com.ecut.campus.data.WidgetLook.clampMask(p)).putLong("widget_bg_ver", System.currentTimeMillis()).commit(); }
    // ===== 1.2.7 =====
    /** 通知栏常驻「下一节课」，默认关。 */
    public static boolean nextClassNotice(Context ctx) { return sp(ctx).getBoolean("next_class_notice", false); }
    public static void setNextClassNotice(Context ctx, boolean v) { sp(ctx).edit().putBoolean("next_class_notice", v).apply(); }
    static String legacyLauncherAlias(Context ctx) {
        String saved = sp(ctx).getString("app_icon", "");
        if ("art".equals(saved)) return "com.ecut.campus.LauncherArt";
        if ("benben".equals(saved)) return "com.ecut.campus.LauncherBenben";
        return "com.ecut.campus.LauncherPear";
    }
    /** 上次打开时的 versionCode（「新版本说明」用），没记过为 0。 */
    public static int lastSeenVersionCode(Context ctx) { return sp(ctx).getInt("last_seen_vc", 0); }
    public static void setLastSeenVersionCode(Context ctx, int vc) { sp(ctx).edit().putInt("last_seen_vc", vc).apply(); }
    /** 小组件上次「不靠打开 App」自己刷新的时间（闹钟 / 兜底 / 系统），诊断日志用。 */
    public static long widgetBgRefreshAt(Context ctx) { return sp(ctx).getLong("widget_bg_refresh_at", 0L); }
    public static void markWidgetBgRefresh(Context ctx) { sp(ctx).edit().putLong("widget_bg_refresh_at", System.currentTimeMillis()).apply(); }
    /** 「允许后台刷新」引导是否已经主动弹过（只主动弹一次，设置里随时能再点）。 */
    public static boolean bgGuideShown(Context ctx) { return sp(ctx).getBoolean("bg_guide_shown", false); }
    public static void setBgGuideShown(Context ctx) { sp(ctx).edit().putBoolean("bg_guide_shown", true).apply(); }

    // ===== 1.2.10：课块下雨提示 =====
    /** 课块下雨提示开关，默认开启。 */
    public static boolean rainHint(Context ctx) { return sp(ctx).getBoolean("rain_hint", true); }
    public static void setRainHint(Context ctx, boolean on) { sp(ctx).edit().putBoolean("rain_hint", on).apply(); }
    /** 是否已首次主动询问过定位权限（拒绝后不再纠缠）。 */
    public static boolean rainPermAsked(Context ctx) { return sp(ctx).getBoolean("rain_perm_asked", false); }
    public static void setRainPermAsked(Context ctx, boolean v) { sp(ctx).edit().putBoolean("rain_perm_asked", v).apply(); }
    /** 上次成功拉取天气预报的时间戳（毫秒）。 */
    public static long rainFetchedAt(Context ctx) { return sp(ctx).getLong("rain_fetched_at", 0L); }
    public static void setRainFetchedAt(Context ctx, long t) { sp(ctx).edit().putLong("rain_fetched_at", t).apply(); }
    /** 最近一次粗略定位坐标（仅在仍有定位权限时用作兜底）。 */
    public static void setRainLocation(Context ctx, double lat, double lon) {
        sp(ctx).edit()
                .putString("rain_lat", Double.toString(lat))
                .putString("rain_lon", Double.toString(lon))
                .apply();
    }
    public static double[] rainLocation(Context ctx) {
        String a = sp(ctx).getString("rain_lat", "");
        String b = sp(ctx).getString("rain_lon", "");
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return null;
        try {
            return new double[]{Double.parseDouble(a), Double.parseDouble(b)};
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
