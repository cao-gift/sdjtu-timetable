package com.ecut.campus;

import android.content.Intent;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 交院教务同步页。教务会话由同步器持有，此页只展示已完成的查询结果。 */
public final class SdjtuStudyActivity extends AppCompatActivity {
    private static final int SIGN_IN = 1, LOCATE_PAGE = 2;
    private static final String[] ROOM_STATES = {"free", "busy", "unknown", ""};
    private static final String[] ROOM_LABELS = {"空闲", "有占用", "待核实", "全部状态"};

    private SdjtuAcademicPages.Feature feature;
    private SdjtuStudySync sync;
    private JSONObject data, choiceData;
    private SdjtuRoomConditions roomDialog;
    private JSONObject request = new JSONObject();
    private long syncedAt;
    private String notice = "", term = "", roomState = "free";
    private boolean needsLogin, awaitingLogin, allowLoginPrompt, failed;
    private boolean canLocate;
    private Button locateButton;
    private Button syncButton, conditionButton, filterButton;
    private SessionBanner banner;
    private EditText search;
    private TextView timestamp, message, scope, tally;
    private LinearLayout filters, emptyBox;
    private TextView emptyTitle, emptySub, emptyAction;
    private com.ecut.campus.view.GaugeSkeletonView skeleton;
    private final Results adapter = new Results();

    @Override protected void onCreate(@Nullable Bundle saved) {
        super.onCreate(saved);
        G.init(this);
        feature = SdjtuAcademicPages.Feature.fromTarget(getIntent().getStringExtra(WebShellActivity.EXTRA_TARGET_KEY));
        if (feature == SdjtuAcademicPages.Feature.PORTAL) {
            finish();
            return;
        }
        setContentView(R.layout.activity_sdjtu_study);
        ((TextView) findViewById(R.id.study_title)).setText(feature.title);
        findViewById(R.id.study_back).setOnClickListener(v -> finish());
        syncButton = findViewById(R.id.study_sync);
        syncButton.setOnClickListener(v -> {
            if (rooms()) put(request, "roomAction", "options");
            startSync();
        });
        ListView list = findViewById(R.id.study_list);
        list.setItemsCanFocus(true);
        list.addHeaderView(makeHeader(), null, false);
        list.setAdapter(adapter);
        readCache();
        if (saved != null) {
            roomState = saved.getString("roomState", "free");
            term = saved.getString("term", "");
            needsLogin = saved.getBoolean("needsLogin");
            failed = saved.getBoolean("failed");
            canLocate = saved.getBoolean("canLocate");
            awaitingLogin = saved.getBoolean("awaitingLogin");
            notice = saved.getString("notice", "");
            search.setText(saved.getString("search", ""));
            try { request = new JSONObject(saved.getString("request", "{}")); }
            catch (JSONException ignored) { /* A fresh sync can use the school's defaults. */ }
        }
        render();
        if (saved != null && (saved.getBoolean("syncing") || saved.getBoolean("roomPicker")))
            startSync(saved.getBoolean("allowLoginPrompt"));
    }

    @Override protected void onResume() {
        super.onResume();
        if (Prefs.hasPortalSession(this) && !Prefs.sessionExpired(this)) needsLogin = false;
        if (banner != null) render();
    }

    private boolean rooms() { return feature == SdjtuAcademicPages.Feature.ROOMS; }

    private void readCache() {
        JSONObject cached = SdjtuStudyStore.load(this, feature);
        data = cached == null ? null : cached.optJSONObject("data");
        syncedAt = cached == null ? 0 : cached.optLong("syncedAt");
        if (data != null) request = requestFor(data);
    }

    private JSONObject requestFor(JSONObject snapshot) {
        JSONObject next = new JSONObject();
        if (rooms()) {
            put(next, "roomAction", "options");
            JSONObject last = snapshot.optJSONObject("roomConditions");
            if (last != null) {
                JSONObject selected = new JSONObject();
                for (String key : new String[]{"campus","week","building"}) put(selected, key, last.optString(key));
                put(next, "roomConditions", selected);
            }
            if (snapshot.has("query")) put(next, "query", snapshot.optJSONObject("query"));
        } else if (!snapshot.optString("planChoice").isEmpty()) {
            put(next, "planChoice", snapshot.optString("planChoice"));
        }
        return next;
    }

    private LinearLayout makeHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setFocusableInTouchMode(true);
        timestamp = text(12, G.SUB);
        timestamp.setTypeface(Typeface.MONOSPACE);
        header.addView(timestamp);
        message = text(14, G.INK);
        message.setPadding(0, dp(8), 0, dp(4));
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        header.addView(message);
        banner = new SessionBanner(this, () -> openLogin(null));
        LinearLayout.LayoutParams bannerLp = new LinearLayout.LayoutParams(-1, -2);
        bannerLp.topMargin = dp(4);
        bannerLp.bottomMargin = dp(4);
        header.addView(banner.view(), bannerLp);
        locateButton = button("在教务中定位");
        locateButton.setOnClickListener(v -> feature.locate(this, LOCATE_PAGE));
        header.addView(locateButton, wrap());
        scope = text(13, G.SUB);
        scope.setPadding(0, dp(8), 0, dp(4));
        header.addView(scope);
        conditionButton = button("查询条件");
        conditionButton.setOnClickListener(v -> choose());
        header.addView(conditionButton, wrap());

        filters = new LinearLayout(this);
        filters.setGravity(Gravity.CENTER_VERTICAL);
        filters.setPadding(0, dp(8), 0, dp(6));
        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setTextColor(G.INK);
        search.setHintTextColor(G.MUTE);
        search.setMinHeight(dp(48));
        search.setHint(rooms() ? "搜索教室" : "搜索课程名称或课程号");
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        filters.addView(search, new LinearLayout.LayoutParams(0, -2, 1));
        filterButton = button("");
        filterButton.setOnClickListener(v -> chooseFilter());
        filters.addView(filterButton, wrap());
        header.addView(filters);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderRows(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        tally = text(13, G.SUB);
        tally.setTypeface(Typeface.MONOSPACE);
        tally.setPadding(0, dp(12), 0, dp(8));
        header.addView(tally);

        emptyBox = new LinearLayout(this);
        emptyBox.setOrientation(LinearLayout.VERTICAL);
        emptyBox.setGravity(Gravity.CENTER_HORIZONTAL);
        emptyBox.setPadding(dp(12), dp(28), dp(12), dp(24));
        com.ecut.campus.view.InkEmptyView emptyArt = new com.ecut.campus.view.InkEmptyView(this);
        emptyArt.setKind(rooms() ? com.ecut.campus.view.InkEmptyView.KIND_ROOMS
                : com.ecut.campus.view.InkEmptyView.KIND_PLAN);
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
        emptyAction = Ui.button(this, rooms() ? "选择查询条件" : "同步培养方案", true, v -> {
            if (rooms()) put(request, "roomAction", "options");
            startSync();
        });
        LinearLayout.LayoutParams eaLp = new LinearLayout.LayoutParams(-2, -2);
        eaLp.topMargin = dp(16);
        emptyBox.addView(emptyAction, eaLp);
        header.addView(emptyBox, new LinearLayout.LayoutParams(-1, -2));

        skeleton = new com.ecut.campus.view.GaugeSkeletonView(this);
        skeleton.setRows(5);
        skeleton.setVisibility(View.GONE);
        LinearLayout.LayoutParams skLp = new LinearLayout.LayoutParams(-1, -2);
        skLp.topMargin = dp(8);
        header.addView(skeleton, skLp);
        return header;
    }

    private void startSync() {
        startSync(true);
    }

    private void startSync(boolean promptForLogin) {
        if (sync != null || awaitingLogin) return;
        allowLoginPrompt = promptForLogin;
        if (promptForLogin && WebShellActivity.needsManualLoginFirst(this)) {
            openLogin(null);
            return;
        }
        canLocate = false;
        failed = false;
        boolean autoLogin = !Prefs.hasPortalSession(this) || Prefs.sessionExpired(this);
        notice = rooms()
                ? ("query".equals(request.optString("roomAction"))
                        ? (autoLogin ? "正在自动登录并查询教室…" : "正在查询教室…")
                        : (autoLogin ? "正在自动登录并读取查询条件…" : "正在读取查询条件…"))
                : (autoLogin ? "正在自动登录并同步" : "正在同步") + feature.title + "…";
        needsLogin = false;
        sync = SdjtuStudySync.start(this, findViewById(R.id.study_sync_host), feature, request,
                new SdjtuStudySync.Callback() {
            @Override public void onSuccess(JSONObject result) {
                sync = null;
                if (rooms() && !"query".equals(request.optString("roomAction"))) {
                    onFailure("未能读取校区和周次，请重试。", false);
                    return;
                }
                long completedAt = System.currentTimeMillis();
                if (SdjtuStudyStore.save(SdjtuStudyActivity.this, feature, result, completedAt)) {
                    data = result;
                    syncedAt = completedAt;
                    request = requestFor(result);
                    choiceData = null;
                    notice = "";
                } else {
                    notice = "本次查询结果保存失败，请重试。";
                }
                render();
            }
            @Override public void onFailure(String reason, boolean loginRequired) {
                sync = null;
                if (roomDialog != null) {
                    request = roomDialog.request("options");
                    roomDialog.dismiss();
                    roomDialog = null;
                }
                failed = true;
                needsLogin = loginRequired;
                canLocate = !loginRequired;
                notice = reason;
                render();
                if (loginRequired && allowLoginPrompt) openLogin(reason);
            }
            @Override public void onChoose(String reason, JSONObject options) {
                sync = null;
                if (rooms() && options.optJSONObject("roomConditions") == null) {
                    onFailure("未能读取校区和周次，请重试。", false);
                    return;
                }
                choiceData = options;
                notice = reason;
                render();
                if (!isFinishing() && !isDestroyed()) {
                    if (rooms()) chooseRooms(options);
                    else choose();
                }
            }
        });
        render();
    }

    private void openLogin(@Nullable String reason) {
        if (awaitingLogin || isFinishing() || isDestroyed()) return;
        awaitingLogin = true;
        render();
        WebShellActivity.startManualLoginForSync(this, SIGN_IN, feature.title, reason);
    }

    @Override protected void onActivityResult(int code, int result, @Nullable Intent details) {
        super.onActivityResult(code, result, details);
        if (code == LOCATE_PAGE && result == RESULT_OK && feature != null) {
            if (details != null && details.getBooleanExtra(WebShellActivity.EXTRA_STUDY_SAVED, false)) {
                readCache();
                canLocate = needsLogin = false;
                choiceData = null;
                notice = "";
                render();
            } else {
                if (rooms()) put(request, "roomAction", "options");
                startSync(false);
            }
            return;
        }
        if (code != SIGN_IN || !awaitingLogin || feature == null) return;
        awaitingLogin = false;
        if (result == RESULT_OK) startSync(false);
        else {
            notice = "登录已取消，可重新同步。";
            needsLogin = true;
            render();
        }
    }

    private void chooseRooms(JSONObject options) {
        JSONObject conditions = options.optJSONObject("roomConditions");
        if (roomDialog != null) {
            roomDialog.setChoices(conditions);
            return;
        }
        roomDialog = new SdjtuRoomConditions(this, selected -> {
            request = selected;
            startSync();
        }, selected -> {
            roomDialog = null;
            request = selected;
            startSync();
        }, () -> {
            request = roomDialog.request("options");
            roomDialog = null;
            if (sync != null) sync.cancel();
            sync = null;
            notice = "";
            render();
        });
        JSONObject choices = options.optJSONObject("choices");
        JSONArray days = choices == null ? null : choices.optJSONArray("days");
        boolean newTimetable = days != null && days.length() > 0;
        // A query can return a different set of weekdays or periods than the last saved result.
        JSONObject timetable = newTimetable ? options : data;
        JSONObject selectedTime = newTimetable ? options.optJSONObject("query") : request.optJSONObject("query");
        String validation = "query".equals(request.optString("roomAction")) ? notice : "";
        roomDialog.show(conditions, timetable, selectedTime, validation);
    }

    private void choose() {
        if (sync != null) return;
        JSONObject options = choiceData != null ? choiceData : data;
        if (options == null) { startSync(); return; }
        JSONArray plans = options.optJSONArray("planChoices");
        if (plans == null || plans.length() == 0) return;
        List<String> labels = new ArrayList<>(), values = new ArrayList<>();
        for (int i = 0; i < plans.length(); i++) {
            JSONObject item = plans.optJSONObject(i);
            if (item == null) continue;
            labels.add(item.optString("label"));
            values.add(item.optString("value"));
        }
        new AlertDialog.Builder(this).setTitle("选择培养方案")
                .setSingleChoiceItems(labels.toArray(new String[0]), values.indexOf(request.optString("planChoice")), (d, i) -> {
                    d.dismiss();
                    put(request, "planChoice", values.get(i));
                    startSync();
                }).setNegativeButton("取消", null).show();
    }

    private void render() {
        boolean busy = sync != null;
        syncButton.setEnabled(!busy && !awaitingLogin);
        if (rooms()) syncButton.setText(busy ? "请稍候…" : "选择条件");
        else syncButton.setText(busy ? "同步中…" : "同步");
        timestamp.setText(syncedAt == 0 ? "尚未同步" : "上次同步  " +
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(syncedAt)));
        String status = (busy || data != null) ? notice : "";
        message.setText(status);
        message.setVisibility(status.isEmpty() ? View.GONE : View.VISIBLE);
        locateButton.setVisibility(canLocate && !busy ? View.VISIBLE : View.GONE);
        banner.update(needsLogin, busy, !awaitingLogin);
        String summary = data == null ? "" : data.optString(rooms() ? "summary" : "planChoice");
        scope.setText(summary.isEmpty() ? "" : (rooms() ? "上次查询 · " : "当前方案 · ") + summary);
        scope.setVisibility(summary.isEmpty() ? View.GONE : View.VISIBLE);
        JSONObject options = choiceData != null ? choiceData : data;
        JSONArray plans = options == null ? null : options.optJSONArray("planChoices");
        boolean canChoose = !rooms() && plans != null && plans.length() > 1;
        conditionButton.setVisibility(canChoose ? View.VISIBLE : View.GONE);
        conditionButton.setEnabled(!busy);
        conditionButton.setText("选择培养方案");
        filters.setVisibility(data != null ? View.VISIBLE : View.GONE);
        if (skeleton != null) skeleton.setVisibility(busy && data == null ? View.VISIBLE : View.GONE);
        if (emptyBox != null) {
            boolean showEmpty = !busy && data == null;
            emptyBox.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
            if (showEmpty) {
                if (failed) {
                    emptyTitle.setText(rooms() ? "空教室查询失败" : "培养方案同步失败");
                    emptySub.setText(notice.isEmpty() ? "未能读取教务数据，请稍后重试。" : notice);
                    emptyAction.setText("重试同步");
                } else {
                    emptyTitle.setText(rooms() ? "尚未查询空教室" : "暂无本地培养方案");
                    emptySub.setText(rooms()
                            ? "选择校区和周次后查询空闲教室，教学楼可留空。"
                            : "点「同步」读取课程体系、学分与开设学期。");
                    emptyAction.setText(rooms() ? "选择查询条件" : "同步培养方案");
                }
                emptyAction.setEnabled(!awaitingLogin);
            }
        }
        renderRows();
    }

    private void chooseFilter() {
        List<String> values, labels;
        String selected;
        if (rooms()) {
            values = Arrays.asList(ROOM_STATES); labels = Arrays.asList(ROOM_LABELS); selected = roomState;
        } else {
            LinkedHashSet<String> terms = new LinkedHashSet<>();
            JSONArray courses = data == null ? null : data.optJSONArray("courses");
            for (int i = 0; courses != null && i < courses.length(); i++) {
                JSONObject c = courses.optJSONObject(i);
                if (c != null && !c.optString("term").isEmpty()) terms.add(c.optString("term"));
            }
            values = new ArrayList<>(); values.add(""); values.addAll(terms); selected = term;
            labels = new ArrayList<>(); labels.add("全部学期");
            for (String t : terms) labels.add(termLabel(t));
        }
        new AlertDialog.Builder(this).setTitle(rooms() ? "教室状态" : "开设学期")
                .setSingleChoiceItems(labels.toArray(new String[0]), values.indexOf(selected), (d, i) -> {
                    if (rooms()) roomState = values.get(i); else term = values.get(i);
                    d.dismiss(); renderRows();
                }).setNegativeButton("取消", null).show();
    }

    private static String termLabel(String value) {
        return value.matches("[0-9]{1,2}") ? "第" + value + "学期" : value;
    }

    private static String stateLabel(String s) {
        int i = Arrays.asList(ROOM_STATES).indexOf(s);
        return i < 0 ? "待核实" : ROOM_LABELS[i];
    }

    private void renderRows() {
        if (tally == null) return;
        List<Row> rows = new ArrayList<>();
        JSONArray records = data == null ? null : data.optJSONArray(rooms() ? "rooms" : "courses");
        String needle = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        int count = 0;
        Map<String,List<Row>> groups = new LinkedHashMap<>();
        if (!rooms() && !term.isEmpty() && records != null) {
            boolean exists = false;
            for (int i = 0; i < records.length(); i++) {
                JSONObject c = records.optJSONObject(i);
                if (c != null && term.equals(c.optString("term"))) { exists = true; break; }
            }
            if (!exists) term = "";
        }
        for (int i = 0; records != null && i < records.length(); i++) {
            JSONObject item = records.optJSONObject(i);
            if (item == null) continue;
            String name = item.optString("name");
            if (!(name + " " + item.optString("code")).toLowerCase(Locale.ROOT).contains(needle)) continue;
            if (rooms()) {
                String state = item.optString("state");
                if (!Arrays.asList(ROOM_STATES).contains(state) || state.isEmpty()) state = "unknown";
                if (!roomState.isEmpty() && !roomState.equals(state)) continue;
                rows.add(new Row(name, stateLabel(state), false));
            } else {
                String semester = item.optString("term");
                if (!term.isEmpty() && !term.equals(semester)) continue;
                String section = item.optString("system");
                if (section.isEmpty()) section = item.optString("nature");
                List<String> parts = new ArrayList<>();
                add(parts, item.optString("code"), "");
                if (!semester.isEmpty()) parts.add(termLabel(semester));
                add(parts, item.optString("credit"), " 学分");
                add(parts, item.optString("hours"), " 学时");
                for (String key : new String[]{"group","nature","kind","status"}) {
                    String value = item.optString(key);
                    if (!value.equals(section) && !parts.contains(value)) add(parts, value, "");
                }
                groups.computeIfAbsent(section, k -> new ArrayList<>()).add(
                        new Row(name, android.text.TextUtils.join(" · ", parts), false));
            }
            count++;
        }
        for (Map.Entry<String,List<Row>> g : groups.entrySet()) {
            if (!g.getKey().isEmpty()) rows.add(new Row(g.getKey(), "", true));
            rows.addAll(g.getValue());
        }
        filterButton.setText(rooms() ? stateLabel(roomState) : term.isEmpty() ? "全部学期" : termLabel(term));
        String countText = rooms() ? count + " 间教室" : count + " 门课程";
        if (count == 0 && data != null) {
            boolean any = records != null && records.length() > 0;
            countText = any ? "没有符合筛选条件的" + (rooms() ? "教室" : "课程")
                    : rooms() ? "本次教务查询返回 0 间教室" : "此方案未列出计划课程";
        }
        tally.setText(countText);
        tally.setVisibility(data == null ? View.GONE : View.VISIBLE);
        adapter.rows = rows;
        adapter.notifyDataSetChanged();
    }

    private static void add(List<String> parts, String value, String suffix) {
        if (!value.isEmpty()) parts.add(value + suffix);
    }

    private static final class Row {
        final String title, detail;
        final boolean group;
        Row(String title, String detail, boolean group) { this.title = title; this.detail = detail; this.group = group; }
    }

    private final class Results extends BaseAdapter {
        List<Row> rows = new ArrayList<>();
        @Override public int getCount() { return rows.size(); }
        @Override public Row getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public boolean isEnabled(int position) { return false; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row = (LinearLayout) recycled;
            if (row == null) {
                row = new LinearLayout(SdjtuStudyActivity.this);
                row.setOrientation(LinearLayout.VERTICAL);
                View line = new View(SdjtuStudyActivity.this); line.setBackgroundColor(G.LINE);
                row.addView(line, new LinearLayout.LayoutParams(-1, 1));
                TextView title = text(16, G.INK);
                title.setPadding(0, dp(12), 0, dp(3)); row.addView(title);
                TextView detail = text(13, G.SUB);
                detail.setPadding(0, 0, 0, dp(10)); row.addView(detail);
            }
            Row item = getItem(position);
            TextView title = (TextView) row.getChildAt(1), detail = (TextView) row.getChildAt(2);
            title.setText(item.title);
            title.setTypeface(item.group ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            detail.setText(item.detail);
            detail.setVisibility(item.detail.isEmpty() ? View.GONE : View.VISIBLE);
            row.setPadding(0, 0, 0, item.detail.isEmpty() ? dp(8) : 0);
            return row;
        }
    }

    private TextView text(int sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp); t.setTextColor(color);
        return t;
    }

    private Button button(String label) {
        Button b = new androidx.appcompat.widget.AppCompatButton(this);
        b.setText(label); b.setAllCaps(false); b.setTextSize(14); b.setTextColor(G.INK);
        b.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        b.setBackgroundResource(R.drawable.bg_gauge_outline);
        b.setStateListAnimator(null);
        b.setPadding(dp(16), 0, dp(16), 0); b.setMinHeight(dp(48));
        return b;
    }

    static void put(JSONObject into, String key, Object value) {
        try { into.put(key, value); } catch (JSONException e) { throw new IllegalArgumentException(key, e); }
    }

    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-2, -2); }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("roomState", roomState);
        out.putString("term", term);
        out.putBoolean("roomPicker", roomDialog != null);
        out.putString("request", (roomDialog != null ? roomDialog.request("options") : request).toString());
        if (search != null) out.putString("search", search.getText().toString());
        out.putBoolean("syncing", sync != null);
        out.putBoolean("allowLoginPrompt", allowLoginPrompt);
        out.putBoolean("needsLogin", needsLogin);
        out.putBoolean("failed", failed);
        out.putBoolean("canLocate", canLocate);
        out.putBoolean("awaitingLogin", awaitingLogin);
        out.putString("notice", notice);
    }


    @Override protected void onDestroy() {
        if (sync != null) sync.cancel();
        if (roomDialog != null) roomDialog.dismiss();
        super.onDestroy();
    }
}
