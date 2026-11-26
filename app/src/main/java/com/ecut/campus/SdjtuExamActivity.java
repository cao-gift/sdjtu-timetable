package com.ecut.campus;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.ecut.campus.data.ExamCountdown;

import org.json.JSONObject;

import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/** 山东交通学院考试安排：按时间轴排序，最近一场标朱砂（整屏朱砂 ≤ 2 处）。 */
public final class SdjtuExamActivity extends AppCompatActivity {
    private static final int LOGIN = 1;
    private static final SdjtuAcademicPages.Feature EXAMS = SdjtuAcademicPages.Feature.EXAMS;
    private final Exams adapter = new Exams();
    private Button syncButton;
    private TextView status, scope, exportBtn, remindBtn;
    private LinearLayout emptyBox;
    private TextView emptyTitle, emptySub, emptyAction;
    private SessionBanner banner;
    private com.ecut.campus.view.GaugeSkeletonView skeleton;
    private SdjtuExamQuery query;
    private JSONObject cached, shown;
    private long syncedAt;
    private String message = "";
    private boolean partial, loginRequired, awaitingLogin, allowLoginPrompt = true, failed;

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
        banner = new SessionBanner(this, () -> openLogin(null));
        LinearLayout.LayoutParams bannerLp = new LinearLayout.LayoutParams(-1, -2);
        bannerLp.bottomMargin = dp(10);
        header.addView(banner.view(), bannerLp);
        scope = Ui.text(this, header, "", 12, G.SUB);
        scope.setTypeface(Typeface.MONOSPACE);
        status = Ui.text(this, header, "", 13.5f, G.SUB);
        status.setPadding(0, dp(4), 0, 0);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

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

        // 统一空状态块（首次进入 / 同步失败一致排版）
        emptyBox = new LinearLayout(this);
        emptyBox.setOrientation(LinearLayout.VERTICAL);
        emptyBox.setGravity(Gravity.CENTER_HORIZONTAL);
        emptyBox.setPadding(dp(12), dp(28), dp(12), dp(24));
        com.ecut.campus.view.InkEmptyView emptyArt = new com.ecut.campus.view.InkEmptyView(this);
        emptyArt.setKind(com.ecut.campus.view.InkEmptyView.KIND_EXAMS);
        emptyBox.addView(emptyArt, new LinearLayout.LayoutParams(dp(72), dp(72)));
        emptyTitle = new TextView(this);
        emptyTitle.setTextSize(15f);
        emptyTitle.setTypeface(Typeface.DEFAULT_BOLD);
        emptyTitle.setTextColor(G.INK);
        emptyTitle.setPadding(0, dp(10), 0, 0);
        emptyBox.addView(emptyTitle);
        emptySub = new TextView(this);
        emptySub.setTextSize(13f);
        emptySub.setTextColor(G.SUB);
        emptySub.setGravity(Gravity.CENTER);
        emptySub.setPadding(0, dp(6), 0, 0);
        emptyBox.addView(emptySub);
        emptyAction = Ui.button(this, "同步考试安排", true, v -> sync());
        LinearLayout.LayoutParams eaLp = new LinearLayout.LayoutParams(-2, -2);
        eaLp.topMargin = dp(16);
        emptyBox.addView(emptyAction, eaLp);
        header.addView(emptyBox, new LinearLayout.LayoutParams(-1, -2));

        skeleton = new com.ecut.campus.view.GaugeSkeletonView(this);
        skeleton.setRows(4);
        skeleton.setVisibility(View.GONE);
        LinearLayout.LayoutParams skLp = new LinearLayout.LayoutParams(-1, -2);
        skLp.topMargin = dp(8);
        header.addView(skeleton, skLp);

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
            failed = saved.getBoolean("failed");
        }
        render();
        if (saved != null && saved.getBoolean("syncing")) sync(allowLoginPrompt);
    }

    @Override protected void onResume() {
        super.onResume();
        if (Prefs.hasPortalSession(this) && !Prefs.sessionExpired(this)) loginRequired = false;
        render();
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
        failed = false;
        boolean autoLogin = !Prefs.hasPortalSession(this) || Prefs.sessionExpired(this);
        message = autoLogin ? "正在自动登录并同步考试安排，预计 5-10 秒…" : "正在同步考试安排…";
        android.widget.Toast.makeText(this, message,
                autoLogin ? android.widget.Toast.LENGTH_LONG : android.widget.Toast.LENGTH_SHORT).show();
        query = SdjtuExamQuery.start(this, findViewById(R.id.study_sync_host), new SdjtuExamQuery.Callback() {
            @Override public void onSuccess(JSONObject data) {
                query = null;
                partial = false;
                failed = false;
                shown = data;
                long now = System.currentTimeMillis();
                int count = data.optJSONArray("exams") == null ? 0 : data.optJSONArray("exams").length();
                if (SdjtuStudyStore.save(SdjtuExamActivity.this, EXAMS, data, now)) {
                    cached = data;
                    syncedAt = now;
                    message = "";
                    ExamReminder.reschedule(SdjtuExamActivity.this);
                    ClassWidget.updateAll(getApplicationContext());
                    android.widget.Toast.makeText(SdjtuExamActivity.this,
                            count > 0 ? "考试安排已更新：" + count + " 场" : "考试安排同步完毕：本学期暂无考试",
                            android.widget.Toast.LENGTH_SHORT).show();
                } else message = "已取得本次考试安排，保存到手机失败。";
                render();
            }

            @Override public void onPartial(JSONObject data, String reason, boolean needsLogin) {
                query = null;
                loginRequired = needsLogin;
                partial = true;
                failed = true;
                shown = data;
                message = reason;
                int count = data.optJSONArray("exams") == null ? 0 : data.optJSONArray("exams").length();
                if (count == 0 && cached != null) {
                    shown = cached;
                    message += " 当前显示上次完整结果。";
                } else if (count > 0) {
                    android.widget.Toast.makeText(SdjtuExamActivity.this,
                            "考试安排部分同步：" + count + " 场", android.widget.Toast.LENGTH_SHORT).show();
                }
                render();
                if (needsLogin && allowLoginPrompt) openLogin(reason);
            }

            @Override public void onFailure(String reason, boolean needsLogin) {
                query = null;
                loginRequired = needsLogin;
                partial = false;
                failed = true;
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
            failed = true;
            message = "登录已取消。可重新登录后同步。";
            if (shown != null && shown == cached) message += " 当前显示上次完整结果。";
            render();
        }
    }

    private void render() {
        boolean busy = query != null;
        syncButton.setEnabled(!busy && !awaitingLogin);
        syncButton.setText(busy ? "同步中…" : "同步");
        banner.update(loginRequired, busy, !awaitingLogin);

        long now = System.currentTimeMillis();
        List<ExamCountdown.Entry> entries = shown == null
                ? Collections.emptyList()
                : ExamCountdown.sortTimeline(ExamCountdown.parseAll(shown), now);
        boolean hasExams = !entries.isEmpty();

        String summary = shown == null ? "" : shown.optString("term") + " 学期";
        if (shown != null && shown == cached) {
            summary += "  ·  上次同步 " + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(syncedAt));
        }
        scope.setText(summary);
        scope.setVisibility(summary.isEmpty() ? View.GONE : View.VISIBLE);

        // 有列表或加载中时，在头部展示状态行；空态时统一收进 emptyBox，不重复显示两份文案。
        String inlineNotice = (busy || hasExams) ? message : "";
        status.setText(inlineNotice);
        status.setVisibility(inlineNotice.isEmpty() ? View.GONE : View.VISIBLE);

        if (exportBtn != null) exportBtn.setVisibility(hasExams ? View.VISIBLE : View.GONE);
        if (remindBtn != null) {
            remindBtn.setVisibility(hasExams ? View.VISIBLE : View.GONE);
            remindBtn.setText(Prefs.examRemind(this) ? "考前提醒：开" : "考前提醒：关");
        }
        if (skeleton != null) skeleton.setVisibility(busy && !hasExams ? View.VISIBLE : View.GONE);
        if (emptyBox != null) {
            boolean showEmpty = !busy && !hasExams;
            emptyBox.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
            if (showEmpty) {
                if (failed) {
                    emptyTitle.setText("考试安排同步失败");
                    emptySub.setText(message.isEmpty() ? "未能取得考试安排，请稍后重试。" : message);
                    emptyAction.setText("重试同步");
                } else if (shown != null && !partial) {
                    emptyTitle.setText("本学期暂无考试安排");
                    emptySub.setText("教务系统尚未公布考试时间，可稍后再同步。");
                    emptyAction.setText("重新同步");
                } else {
                    emptyTitle.setText("暂无本地考试安排");
                    emptySub.setText("点「同步」查看本学期考试时间、考场与座位。");
                    emptyAction.setText("同步考试安排");
                }
                emptyAction.setEnabled(!awaitingLogin);
            }
        }
        adapter.now = now;
        adapter.entries = entries;
        adapter.nearestIdx = ExamCountdown.nearestUpcomingIndex(entries, now);
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
        List<ExamCountdown.Entry> list = ExamCountdown.parseAll(shown);
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

    /** 左侧发丝时间轴轨：首项无上轨、末项无下轨；仅最近一场实心朱砂点。 */
    private static final class TimelineRail extends View {
        boolean first, last, nearest, muted;
        private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint dotStroke = new Paint(Paint.ANTI_ALIAS_FLAG);

        TimelineRail(Context c) {
            super(c);
            line.setStyle(Paint.Style.STROKE);
            dotFill.setStyle(Paint.Style.FILL);
            dotStroke.setStyle(Paint.Style.STROKE);
        }

        @Override protected void onDraw(Canvas canvas) {
            float d = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f;
            float cy = 22f * d;
            float r = (nearest ? 4.5f : 3.5f) * d;
            line.setColor(G.LINE);
            line.setStrokeWidth(Math.max(1f, d));
            if (!first) canvas.drawLine(cx, 0, cx, Math.max(0, cy - r - 2 * d), line);
            if (!last) canvas.drawLine(cx, cy + r + 2 * d, cx, getHeight(), line);

            if (nearest) {
                dotFill.setColor(G.alarm());
                canvas.drawCircle(cx, cy, r, dotFill);
            } else if (muted) {
                dotFill.setColor(G.MUTE);
                canvas.drawCircle(cx, cy, r * 0.75f, dotFill);
            } else {
                dotFill.setColor(G.PAPER);
                dotStroke.setColor(G.INK);
                dotStroke.setStrokeWidth(1.5f * d);
                canvas.drawCircle(cx, cy, r, dotFill);
                canvas.drawCircle(cx, cy, r, dotStroke);
            }
        }
    }

    private final class Exams extends BaseAdapter {
        List<ExamCountdown.Entry> entries = Collections.emptyList();
        long now;
        int nearestIdx = -1;

        @Override public int getCount() { return entries.size(); }
        @Override public ExamCountdown.Entry getItem(int i) { return entries.get(i); }
        @Override public long getItemId(int i) { return i; }
        @Override public boolean isEnabled(int i) { return false; }

        @Override public View getView(int i, View recycled, ViewGroup parent) {
            LinearLayout row = (LinearLayout) recycled;
            if (row == null) {
                row = new LinearLayout(SdjtuExamActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);

                TimelineRail rail = new TimelineRail(SdjtuExamActivity.this);
                row.addView(rail, new LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.MATCH_PARENT));

                LinearLayout body = new LinearLayout(SdjtuExamActivity.this);
                body.setOrientation(LinearLayout.VERTICAL);
                body.setPadding(dp(8), dp(12), 0, 0);

                LinearLayout top = new LinearLayout(SdjtuExamActivity.this);
                top.setOrientation(LinearLayout.HORIZONTAL);
                top.setGravity(Gravity.CENTER_VERTICAL);
                TextView nameTv = new TextView(SdjtuExamActivity.this);
                nameTv.setTextSize(15.5f);
                nameTv.setTextColor(G.INK);
                nameTv.setTypeface(Typeface.DEFAULT_BOLD);
                top.addView(nameTv, new LinearLayout.LayoutParams(0, -2, 1f));
                TextView cdTv = new TextView(SdjtuExamActivity.this);
                cdTv.setTextSize(12f);
                cdTv.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
                cdTv.setPadding(dp(8), 0, 0, 0);
                top.addView(cdTv);
                body.addView(top);

                TextView time = Ui.text(SdjtuExamActivity.this, body, "", 13.5f, G.INK);
                time.setTypeface(Typeface.MONOSPACE);
                time.setPadding(0, dp(4), 0, 0);
                Ui.text(SdjtuExamActivity.this, body, "", 13f, G.SUB);

                View line = new View(SdjtuExamActivity.this);
                line.setBackgroundColor(G.LINE);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 1);
                lp.topMargin = dp(12);
                body.addView(line, lp);

                row.addView(body, new LinearLayout.LayoutParams(0, -2, 1f));
            }
            ExamCountdown.Entry entry = getItem(i);
            String cd = ExamCountdown.countdownLabel(entry, now);
            boolean muted = "已结束".equals(cd) || "待公布".equals(cd);
            boolean nearest = (i == nearestIdx);

            TimelineRail rail = (TimelineRail) row.getChildAt(0);
            rail.first = (i == 0);
            rail.last = (i == entries.size() - 1);
            rail.nearest = nearest;
            rail.muted = muted;
            rail.invalidate();

            LinearLayout body = (LinearLayout) row.getChildAt(1);
            LinearLayout top = (LinearLayout) body.getChildAt(0);
            TextView nameTv = (TextView) top.getChildAt(0);
            nameTv.setText(entry.name);
            nameTv.setTextColor(muted ? G.MUTE : G.INK);

            TextView cdTv = (TextView) top.getChildAt(1);
            cdTv.setText(cd);
            // 仅最近一场未考使用朱砂色（确保整屏朱砂 ≤ 2 处）
            cdTv.setTextColor(muted ? G.MUTE : (nearest ? G.alarm() : G.INK));

            TextView timeTv = (TextView) body.getChildAt(1);
            timeTv.setText(entry.timeRaw.isEmpty() ? "考试时间待公布" : entry.timeRaw);
            timeTv.setTextColor(muted ? G.MUTE : G.INK);

            String detail = entry.room.isEmpty() ? "" : "考场 " + entry.room;
            if (!entry.seat.isEmpty()) detail += (detail.isEmpty() ? "" : "  ·  ") + "座位 " + entry.seat;
            TextView location = (TextView) body.getChildAt(2);
            location.setText(detail);
            location.setTextColor(muted ? G.MUTE : G.SUB);
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
        state.putBoolean("failed", failed);
    }

    @Override protected void onDestroy() {
        if (query != null) query.cancel();
        super.onDestroy();
    }
}
