package com.ecut.campus;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Date;

/** 山东交通学院考试安排。只将一次完整查询的结果保存到本机。 */
public final class SdjtuExamActivity extends AppCompatActivity {
    private static final int LOGIN = 1;
    private static final SdjtuAcademicPages.Feature EXAMS = SdjtuAcademicPages.Feature.EXAMS;
    private final Exams adapter = new Exams();
    private Button syncButton;
    private TextView status, scope, login, exportBtn, remindBtn;
    private com.ecut.campus.view.InkEmptyView emptyArt;
    private com.ecut.campus.view.GaugeSkeletonView skeleton;
    private SdjtuExamQuery query;
    private JSONObject cached, shown;
    private long syncedAt;
    private String message = "";
    private boolean partial, loginRequired, awaitingLogin, allowLoginPrompt = true;

    @Override protected void onCreate(@Nullable Bundle saved) {
        super.onCreate(saved);
        G.init(this);
        setContentView(R.layout.activity_sdjtu_study);
        ((TextView) findViewById(R.id.study_title)).setText("考试安排");
        findViewById(R.id.study_back).setOnClickListener(v -> finish());
        syncButton = findViewById(R.id.study_sync);
        syncButton.setOnClickListener(v -> sync());

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(0, dp(8), 0, dp(8));
        scope = Ui.text(this, header, "", 12, G.SUB);
        scope.setTypeface(Typeface.MONOSPACE);
        status = Ui.text(this, header, "", 14, G.INK);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        login = Ui.button(this, "登录统一门户", false, v -> openLogin(null));
        LinearLayout.LayoutParams loginLp = new LinearLayout.LayoutParams(-2, -2);
        loginLp.topMargin = dp(10);
        header.addView(login, loginLp);

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(-1, -2);
        actLp.topMargin = dp(10);
        exportBtn = Ui.button(this, "导出到日历", false, v -> exportExamsIcs());
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(0, -2, 1f);
        actionRow.addView(exportBtn, btnLp);
        remindBtn = Ui.button(this, "", false, v -> toggleExamRemind());
        LinearLayout.LayoutParams btn2Lp = new LinearLayout.LayoutParams(0, -2, 1f);
        btn2Lp.setMarginStart(dp(8));
        actionRow.addView(remindBtn, btn2Lp);
        header.addView(actionRow, actLp);

        emptyArt = new com.ecut.campus.view.InkEmptyView(this);
        emptyArt.setKind(com.ecut.campus.view.InkEmptyView.KIND_EXAMS);
        LinearLayout.LayoutParams eLp = new LinearLayout.LayoutParams(dp(72), dp(72));
        eLp.gravity = android.view.Gravity.CENTER_HORIZONTAL;
        eLp.topMargin = dp(24);
        eLp.bottomMargin = dp(8);
        header.addView(emptyArt, eLp);

        skeleton = new com.ecut.campus.view.GaugeSkeletonView(this);
        skeleton.setRows(4);
        skeleton.setVisibility(View.GONE);
        header.addView(skeleton, new LinearLayout.LayoutParams(-1, -2));

        ListView list = findViewById(R.id.study_list);
        list.addHeaderView(header, null, false);
        list.setAdapter(adapter);

        JSONObject snapshot = SdjtuStudyStore.load(this, EXAMS);
        if (snapshot != null) {
            cached = snapshot.optJSONObject("data");
            syncedAt = snapshot.optLong("syncedAt");
        }
        shown = cached;
        if (saved != null) {
            awaitingLogin = saved.getBoolean("awaitingLogin");
            loginRequired = saved.getBoolean("loginRequired");
            allowLoginPrompt = saved.getBoolean("allowLoginPrompt", true);
        }
        render();
        if (saved != null && saved.getBoolean("syncing")) sync(allowLoginPrompt);
    }

    private void sync() {
        sync(true);
    }

    private void sync(boolean promptForLogin) {
        if (query != null || awaitingLogin) return;
        allowLoginPrompt = promptForLogin;
        if (promptForLogin && WebShellActivity.needsManualLoginFirst(this)) {
            openLogin(null);
            return;
        }
        loginRequired = false;
        boolean autoLogin = !Prefs.hasPortalSession(this) || Prefs.sessionExpired(this);
        message = autoLogin ? "正在自动登录并查询考试安排…" : "正在查询考试安排…";
        query = SdjtuExamQuery.start(this, findViewById(R.id.study_sync_host), new SdjtuExamQuery.Callback() {
            @Override public void onSuccess(JSONObject data) {
                query = null;
                partial = false;
                shown = data;
                long now = System.currentTimeMillis();
                if (SdjtuStudyStore.save(SdjtuExamActivity.this, EXAMS, data, now)) {
                    cached = data;
                    syncedAt = now;
                    message = "";
                    ExamReminder.reschedule(SdjtuExamActivity.this);
                    ClassWidget.updateAll(getApplicationContext());
                } else message = "已取得本次考试安排，保存到手机失败。";
                render();
            }

            @Override public void onPartial(JSONObject data, String reason, boolean needsLogin) {
                query = null;
                loginRequired = needsLogin;
                partial = true;
                shown = data;
                message = reason;
                if (data.optJSONArray("exams").length() == 0 && cached != null) {
                    shown = cached;
                    message += " 当前显示上次完整结果。";
                }
                render();
                if (needsLogin && allowLoginPrompt) openLogin(reason);
            }

            @Override public void onFailure(String reason, boolean needsLogin) {
                query = null;
                loginRequired = needsLogin;
                partial = false;
                shown = cached;
                message = reason + (cached != null ? " 当前显示上次完整结果。" : "");
                render();
                if (needsLogin && allowLoginPrompt) openLogin(reason);
            }
        });
        render();
    }

    private void openLogin(@Nullable String reason) {
        if (awaitingLogin || query != null || isFinishing() || isDestroyed()) return;
        awaitingLogin = true;
        render();
        WebShellActivity.startManualLoginForSync(this, LOGIN, "考试安排", reason);
    }

    @Override protected void onActivityResult(int code, int result, @Nullable Intent data) {
        super.onActivityResult(code, result, data);
        if (code != LOGIN || !awaitingLogin) return;
        awaitingLogin = false;
        if (result == RESULT_OK) sync(false);
        else {
            message = "登录已取消。可重新登录后同步。";
            if (shown != null && shown == cached) message += " 当前显示上次完整结果。";
            render();
        }
    }

    private void render() {
        boolean busy = query != null;
        syncButton.setEnabled(!busy && !awaitingLogin);
        syncButton.setText(busy ? "查询中…" : "同步");
        login.setVisibility(loginRequired && !busy ? View.VISIBLE : View.GONE);
        login.setEnabled(!awaitingLogin);
        String summary = shown == null ? "" : shown.optString("term") + " 学期";
        if (shown != null && shown == cached)
            summary += "  ·  上次同步 " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(syncedAt));
        scope.setText(summary);
        scope.setVisibility(summary.isEmpty() ? View.GONE : View.VISIBLE);
        JSONArray rows = shown == null ? null : shown.optJSONArray("exams");
        String notice = message;
        if (notice.isEmpty() && !busy) {
            if (shown == null) notice = "点「同步」查看本学期考试安排。";
            else if (rows.length() == 0 && !partial) notice = "本学期暂无考试安排。";
        }
        status.setText(notice);
        status.setVisibility(notice.isEmpty() ? View.GONE : View.VISIBLE);
        boolean hasExams = rows != null && rows.length() > 0;
        if (exportBtn != null) exportBtn.setVisibility(hasExams ? View.VISIBLE : View.GONE);
        if (remindBtn != null) {
            remindBtn.setVisibility(hasExams ? View.VISIBLE : View.GONE);
            remindBtn.setText(Prefs.examRemind(this) ? "考前提醒：开" : "考前提醒：关");
        }
        if (skeleton != null) skeleton.setVisibility(busy && !hasExams ? View.VISIBLE : View.GONE);
        if (emptyArt != null) emptyArt.setVisibility(!busy && !hasExams ? View.VISIBLE : View.GONE);
        adapter.rows = rows;
        adapter.notifyDataSetChanged();
    }

    private void toggleExamRemind() {
        boolean next = !Prefs.examRemind(this);
        Prefs.setExamRemind(this, next);
        if (next) Notify.requestPermissionIfNeeded(this);
        ExamReminder.reschedule(this);
        render();
        android.widget.Toast.makeText(this,
                next ? "已开启考前一天与考前一小时提醒" : "已关闭考试提醒",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private void exportExamsIcs() {
        java.util.List<com.ecut.campus.data.ExamCountdown.Entry> list =
                com.ecut.campus.data.ExamCountdown.parseAll(shown);
        String term = shown == null ? "" : shown.optString("term", "");
        String ics = com.ecut.campus.data.IcsExporter.exportExams(list,
                term.isEmpty() ? "考试安排 · 驴驴课表" : term + " 考试安排 · 驴驴课表");
        if (ics == null || ics.isEmpty()) {
            android.widget.Toast.makeText(this, "暂无明确日期的考试可导出", android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            java.io.File out = ShareFiles.exportFile(this, "lvlv_exams.ics");
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(out)) {
                fos.write(ics.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            ShareFiles.send(this, out, "text/calendar", null, "导出考试到系统日历");
        } catch (Exception e) {
            android.widget.Toast.makeText(this, "导出失败：" + e.getMessage(), android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private final class Exams extends BaseAdapter {
        JSONArray rows;
        @Override public int getCount() { return rows == null ? 0 : rows.length(); }
        @Override public JSONObject getItem(int i) { return rows.optJSONObject(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public boolean isEnabled(int i) { return false; }
        @Override public View getView(int i, View recycled, ViewGroup parent) {
            LinearLayout row = (LinearLayout) recycled;
            if (row == null) {
                row = new LinearLayout(SdjtuExamActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                row.setPadding(0, dp(12), 0, 0);
                LinearLayout top = new LinearLayout(SdjtuExamActivity.this);
                top.setOrientation(LinearLayout.HORIZONTAL);
                top.setGravity(android.view.Gravity.CENTER_VERTICAL);
                TextView nameTv = new TextView(SdjtuExamActivity.this);
                nameTv.setTextSize(16f);
                nameTv.setTextColor(G.INK);
                nameTv.setTypeface(Typeface.DEFAULT_BOLD);
                top.addView(nameTv, new LinearLayout.LayoutParams(0, -2, 1f));
                TextView cdTv = new TextView(SdjtuExamActivity.this);
                cdTv.setTextSize(12f);
                cdTv.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
                cdTv.setPadding(dp(8), 0, 0, 0);
                top.addView(cdTv);
                row.addView(top);
                TextView time = Ui.text(SdjtuExamActivity.this, row, "", 14, G.INK);
                time.setTypeface(Typeface.MONOSPACE);
                time.setPadding(0, dp(4), 0, 0);
                Ui.text(SdjtuExamActivity.this, row, "", 13.5f, G.SUB);
                View line = new View(SdjtuExamActivity.this);
                line.setBackgroundColor(G.LINE);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 1);
                lp.topMargin = dp(12);
                row.addView(line, lp);
            }
            JSONObject exam = getItem(i);
            LinearLayout top = (LinearLayout) row.getChildAt(0);
            ((TextView) top.getChildAt(0)).setText(exam.optString("name"));
            com.ecut.campus.data.ExamCountdown.Entry entry = com.ecut.campus.data.ExamCountdown.parseOne(exam);
            long now = System.currentTimeMillis();
            String cd = com.ecut.campus.data.ExamCountdown.countdownLabel(entry, now);
            TextView cdTv = (TextView) top.getChildAt(1);
            cdTv.setText(cd);
            boolean urgent = entry.hasDate() && now < entry.endMillis
                    && (entry.startMillis - now <= 7 * com.ecut.campus.data.ExamCountdown.ONE_DAY_MS);
            cdTv.setTextColor("已结束".equals(cd) || "待公布".equals(cd) ? G.MUTE : (urgent ? G.alarm() : G.INK));
            String time = exam.optString("time");
            ((TextView) row.getChildAt(1)).setText(time.isEmpty() ? "考试时间待公布" : time);
            String room = exam.optString("room"), seat = exam.optString("seat");
            String detail = room.isEmpty() ? "" : "考场 " + room;
            if (!seat.isEmpty()) detail += (detail.isEmpty() ? "" : "  ·  ") + "座位 " + seat;
            TextView location = (TextView) row.getChildAt(2);
            location.setText(detail);
            location.setVisibility(detail.isEmpty() ? View.GONE : View.VISIBLE);
            return row;
        }
    }

    private int dp(int value) { return Ui.dp(this, value); }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("syncing", query != null);
        state.putBoolean("awaitingLogin", awaitingLogin);
        state.putBoolean("loginRequired", loginRequired);
        state.putBoolean("allowLoginPrompt", allowLoginPrompt);
    }


    @Override protected void onDestroy() {
        if (query != null) query.cancel();
        super.onDestroy();
    }
}
