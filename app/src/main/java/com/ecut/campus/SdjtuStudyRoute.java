package com.ecut.campus;

import android.content.Context;
import android.content.SharedPreferences;
import com.ecut.campus.data.SdjtuPortal;
import java.net.URI;

/** 只记住在本人教务页面实际识别到、能够再次 GET 打开的功能入口。 */
final class SdjtuStudyRoute {
    private SdjtuStudyRoute() {}

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getSharedPreferences("sdjtu_study_routes", Context.MODE_PRIVATE);
    }

    static String safeEntry(SdjtuAcademicPages.Feature feature, String page) {
        String base = SdjtuPortal.academicBase(page);
        if (base.isEmpty()) return "";
        URI uri = URI.create(page);
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) return "";
        String path = uri.getRawPath();
        int marker = path.indexOf("/jsxsd/");
        if (marker < 0) return "";
        String relative = path.substring(marker + "/jsxsd/".length());
        boolean usable = feature == SdjtuAcademicPages.Feature.ROOMS
                ? relative.matches("(?:kbcx/kbxx_classroom|kbxx/jsjy_query)(?:\\.(?:jsp|do))?/?")
                : feature == SdjtuAcademicPages.Feature.PLAN
                && relative.matches("pyfa/pyfa_query(?:\\.(?:jsp|do))?/?");
        return usable ? base + relative : "";
    }

    static String load(Context ctx, SdjtuAcademicPages.Feature feature) {
        return safeEntry(feature, prefs(ctx).getString(feature.target, ""));
    }

    static boolean remember(Context ctx, SdjtuAcademicPages.Feature feature, String page) {
        String entry = safeEntry(feature, page);
        if (entry.isEmpty()) return false;
        SharedPreferences prefs = prefs(ctx);
        if (!entry.equals(prefs.getString(feature.target, ""))) prefs.edit().putString(feature.target, entry).apply();
        return true;
    }

    static boolean hasAnyRoute(Context ctx) {
        return !load(ctx, SdjtuAcademicPages.Feature.ROOMS).isEmpty()
                || !load(ctx, SdjtuAcademicPages.Feature.PLAN).isEmpty();
    }

    static void clear(Context ctx) {
        prefs(ctx).edit().clear().apply();
    }
}
