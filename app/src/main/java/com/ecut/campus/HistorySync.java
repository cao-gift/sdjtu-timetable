package com.ecut.campus;

import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.ecut.campus.data.Grades;
import com.ecut.campus.data.GradesStore;

/** 历史学期课表同步（从 MainActivity 拆出）：按成绩覆盖的学年逐个学期串行拉取，写入归档。 */
final class HistorySync {

    static final int MAX_TERM = 2;

    private final MainActivity a;

    HistorySync(MainActivity a) {
        this.a = a;
    }


    /**
     * 历史学期同步：按成绩页覆盖的学年范围，把该生所有学期课表拉到归档。
     * 当前学期保持 active 不变，历史学期只进归档。
     */
    void start() {
        if (a.activeSync != null && a.activeSync.isRunning()) {
            Toast.makeText(a, "正在同步，请稍候", Toast.LENGTH_SHORT).show();
            return;
        }
        Grades grades = GradesStore.load(a);
        if (grades.semesters.isEmpty()) {
            Toast.makeText(a, "先同步一次成绩，才知道要拉取哪些学年", Toast.LENGTH_SHORT).show();
            return;
        }
        int startYear = earliestGradeYear(grades);
        java.util.Calendar cal = java.util.Calendar.getInstance();
        int thisYear = cal.get(java.util.Calendar.YEAR);
        int month = cal.get(java.util.Calendar.MONTH) + 1;
        int endYear = currentFallYear(thisYear, month);
        int endTerm = currentEndTerm(month);
        if (startYear < 0 || startYear > endYear) {
            Toast.makeText(a, "无法识别学年范围", Toast.LENGTH_SHORT).show();
            return;
        }

        new AlertDialog.Builder(a)
                .setTitle("同步历史学期课表")
                .setMessage("将按成绩记录从 " + startYear + " 年级第一学期拉到 "
                        + endYear + "-" + (endYear + 1) + " 第" + endTerm + "学期，"
                        + "每个学期单独请求教务接口并写入归档，当前学期不受影响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("开始", (d, w) -> {
                    // 串行发起：每个学期一次隐藏 WebView 请求
                    syncSemesterRange(startYear, endYear, endTerm, 1);
                })
                .show();
    }

    private int historyCursorYear;
    private int historyCursorTerm;
    private int historyEndYear;
    private int historyEndTerm;

    static int currentFallYear(int year, int month) {
        return month >= 7 ? year : year - 1;
    }

    /** 7–12 月及次年 1 月为第 1 学期，2–6 月为第 2 学期。 */
    static int currentEndTerm(int month) {
        return (month >= 2 && month <= 6) ? 2 : 1;
    }

    static String currentTermId(int year, int month) {
        int fall = currentFallYear(year, month);
        return fall + "-" + (fall + 1) + "-" + currentEndTerm(month);
    }

    static boolean hasMore(int cursorYear, int cursorTerm, int endYear, int endTerm) {
        return cursorYear < endYear || (cursorYear == endYear && cursorTerm <= endTerm);
    }

    static int[] nextCursor(int cursorYear, int cursorTerm) {
        if (cursorTerm >= MAX_TERM) return new int[]{cursorYear + 1, 1};
        return new int[]{cursorYear, cursorTerm + 1};
    }

    static java.util.List<String> plannedTerms(int startYear, int year, int month) {
        java.util.List<String> out = new java.util.ArrayList<>();
        int endYear = currentFallYear(year, month);
        int endTerm = currentEndTerm(month);
        if (startYear < 0 || startYear > endYear) return out;
        int curYear = startYear;
        int curTerm = 1;
        while (hasMore(curYear, curTerm, endYear, endTerm)) {
            out.add(curYear + "-" + (curYear + 1) + "-" + curTerm);
            int[] next = nextCursor(curYear, curTerm);
            curYear = next[0];
            curTerm = next[1];
        }
        return out;
    }

    /**
     * 成绩学期列表按 id 倒序排列（新学期在前），
     * 最早学年取列表末尾的学期 id 首段。
     */
    static int earliestGradeYear(Grades grades) {
        if (grades == null || grades.semesters.isEmpty()) return -1;
        Grades.SemesterGrades last = grades.semesters.get(grades.semesters.size() - 1);
        String[] parts = (last.id == null ? "" : last.id).split("-");
        if (parts.length < 1 || parts[0].isEmpty()) return -1;
        try {
            return Integer.parseInt(parts[0].trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private void syncSemesterRange(int fromYear, int toYear, int toTerm, int fromTerm) {
        historyCursorYear = fromYear;
        historyCursorTerm = fromTerm;
        historyEndYear = toYear;
        historyEndTerm = toTerm;
        syncNextHistorySemester();
    }

    private void syncNextHistorySemester() {
        if (a.isFinishing() || a.isDestroyed()) return;
        if (!hasMore(historyCursorYear, historyCursorTerm, historyEndYear, historyEndTerm)) {
            Toast.makeText(a, "历史学期同步完成", Toast.LENGTH_SHORT).show();
            a.refreshTimetableTab();
            a.refreshSettingsTab();
            return;
        }
        String xnm = String.valueOf(historyCursorYear);
        String xqm = historyCursorTerm == 1 ? "3" : (historyCursorTerm == 2 ? "12" : "16");
        Toast.makeText(a, "同步 " + historyCursorYear + "-"
                + (historyCursorYear + 1) + " 第" + historyCursorTerm + "学期…",
                Toast.LENGTH_SHORT).show();
        a.activeSync = CampusSyncClient.start(a.newWebViewForSync(),
                CampusSyncClient.Kind.TIMETABLE, xnm, xqm,
                new CampusSyncClient.Callback() {
                    @Override
                    public void onSuccess(int count) {
                        a.activeSync = null;
                        advanceHistoryCursor();
                    }

                    @Override
                    public void onLoginRequired(String message) {
                        a.activeSync = null;
                        historyCursorYear = historyEndYear + 1;
                        new AlertDialog.Builder(a)
                                .setTitle("请登录统一门户")
                                .setMessage(message + "\n\n请在登录后再次同步历史学期。")
                                .setPositiveButton("去登录", (dialog, which) -> a.openPortal())
                                .setNegativeButton("稍后", null)
                                .show();
                    }

                    @Override
                    public void onFailure(String message) {
                        a.activeSync = null;
                        // 单个学期失败不中断，继续下一个
                        Toast.makeText(a,
                                "第 " + historyCursorTerm + " 学期："
                                        + message + "，继续下一个",
                                Toast.LENGTH_SHORT).show();
                        advanceHistoryCursor();
                    }
                });
    }

    private void advanceHistoryCursor() {
        int[] next = nextCursor(historyCursorYear, historyCursorTerm);
        historyCursorYear = next[0];
        historyCursorTerm = next[1];
        // 没开课的学期教务会返回空课表，失败时也照常推进，
        // 最终归档里只会留下有课的学期。
        syncNextHistorySemester();
    }
}
