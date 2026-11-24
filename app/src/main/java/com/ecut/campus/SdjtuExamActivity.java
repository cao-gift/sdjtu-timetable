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
    private TextView status, scope, login;
    private SdjtuExamQuery query;
    private JSONObject cached, shown;
    private long syncedAt;
    private String message = "";
    private boolean partial, loginRequired, awaitingLogin;

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
        login = Ui.button(this, "登录统一门户", false, v -> openLogin());
        login.setMinHeight(dp(48));
        login.setPadding(dp(16), 0, dp(16), 0);
        header.addView(login, new LinearLayout.LayoutParams(-2, -2));
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
        }
        render();
        if (saved != null && saved.getBoolean("syncing")) sync();
    }

    private void sync() {
        if (query != null || awaitingLogin) return;
        loginRequired = false;
        message = "正在查询考试安排…";
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
            }

            @Override public void onFailure(String reason, boolean needsLogin) {
                query = null;
                loginRequired = needsLogin;
                partial = false;
                shown = cached;
                message = reason + (cached != null ? " 当前显示上次完整结果。" : "");
                render();
            }
        });
        render();
    }

    private void openLogin() {
        if (awaitingLogin || query != null) return;
        awaitingLogin = true;
        render();
        Intent intent = new Intent(this, WebShellActivity.class);
        intent.putExtra(WebShellActivity.EXTRA_TARGET_KEY, "portal");
        intent.putExtra(WebShellActivity.EXTRA_RETURN_AFTER_SDJTU_LOGIN, true);
        startActivityForResult(intent, LOGIN);
    }

    @Override protected void onActivityResult(int code, int result, @Nullable Intent data) {
        super.onActivityResult(code, result, data);
        if (code != LOGIN || !awaitingLogin) return;
        awaitingLogin = false;
        if (result == RESULT_OK) sync();
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
        adapter.rows = rows;
        adapter.notifyDataSetChanged();
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
                row.setPadding(0, dp(10), 0, dp(10));
                Ui.text(SdjtuExamActivity.this, row, "", 16, G.INK).setTypeface(Typeface.DEFAULT_BOLD);
                TextView time = Ui.text(SdjtuExamActivity.this, row, "", 14, G.INK);
                time.setTypeface(Typeface.MONOSPACE);
                time.setPadding(0, dp(4), 0, 0);
                Ui.text(SdjtuExamActivity.this, row, "", 14, G.SUB);
            }
            JSONObject exam = getItem(i);
            ((TextView) row.getChildAt(0)).setText(exam.optString("name"));
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
    }


    @Override protected void onDestroy() {
        if (query != null) query.cancel();
        super.onDestroy();
    }
}
