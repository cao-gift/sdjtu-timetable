package com.ecut.campus;

import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.ecut.campus.data.SdjtuStudyFormat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 交院教务同步页（查空教室 / 培养方案）。
 * - 查空教室：不落盘缓存，默认无影山校区 + 无影山1号教学楼 + 当前周次 + 当前星期，按节次矩阵展示每间教室空闲情况。
 * - 培养方案：保留本地缓存，按「刻度 Gauge / 纸墨」展示总学分统计、学期刻度条与分类课程卡片。
 */
public final class SdjtuStudyActivity extends AppCompatActivity {
    private static final int SIGN_IN = 1, LOCATE_PAGE = 2;

    private SdjtuAcademicPages.Feature feature;
    private SdjtuStudySync sync;
    private JSONObject data, choiceData;
    private SdjtuRoomConditions roomDialog;
    private JSONObject request = new JSONObject();
    private long syncedAt;
    private String notice = "", term = "";
    /** 空教室页：按某个具体节次筛选（空串 = 当天全部节次）。 */
    private String selectedPeriodFilter = "";
    /** 空教室页：true = 仅显示有空闲的教室；false = 显示全部教室。 */
    private boolean freeOnly = true;
    private boolean needsLogin, awaitingLogin, allowLoginPrompt, failed;
    private boolean canLocate;

    private Button locateButton, syncButton;
    private SessionBanner banner;
    private EditText search;
    private TextView message, tally, filterToggleBtn;
    private LinearLayout heroCard, periodBarBox, termBarBox, filters, emptyBox;
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
            if (rooms()) {
                openRoomConditionPicker();
            } else {
                startSync();
            }
        });

        ListView list = findViewById(R.id.study_list);
        list.setItemsCanFocus(true);
        list.addHeaderView(makeHeader(), null, false);
        list.setAdapter(adapter);

        if (rooms()) {
            // 查空教室不需要缓存，清理可能遗留的旧缓存文件并预置默认条件
            SdjtuStudyStore.clearRooms(this);
            request = defaultRoomQueryRequest();
        } else {
            readCache();
        }

        if (saved != null) {
            selectedPeriodFilter = saved.getString("selectedPeriodFilter", "");
            freeOnly = saved.getBoolean("freeOnly", true);
            term = saved.getString("term", "");
            needsLogin = saved.getBoolean("needsLogin");
            failed = saved.getBoolean("failed");
            canLocate = saved.getBoolean("canLocate");
            awaitingLogin = saved.getBoolean("awaitingLogin");
            notice = saved.getString("notice", "");
            search.setText(saved.getString("search", ""));
            try { request = new JSONObject(saved.getString("request", "{}")); }
            catch (JSONException ignored) {}
        }
        render();

        if (saved != null && (saved.getBoolean("syncing") || saved.getBoolean("roomPicker"))) {
            startSync(saved.getBoolean("allowLoginPrompt"));
        } else if (saved == null && rooms() && !WebShellActivity.needsManualLoginFirst(this)) {
            // 已有会话或已配置自动登录时，进入查空教室即按默认条件自动查询
            startSync(true);
        }
    }

    @Override protected void onResume() {
        super.onResume();
        if (Prefs.hasPortalSession(this) && !Prefs.sessionExpired(this)) needsLogin = false;
        if (banner != null) render();
    }

    private boolean rooms() { return feature == SdjtuAcademicPages.Feature.ROOMS; }

    private int actualCurrentWeek() {
        String start = WeekMath.normalizeToMonday(Prefs.semesterStartDate(this));
        if (start.isEmpty()) return 1;
        return WeekMath.currentWeek(start, Prefs.totalWeeks(this));
    }

    private JSONObject defaultRoomQueryRequest() {
        int curWeek = actualCurrentWeek();
        int curDay = WeekMath.todayDayOfWeek();
        JSONObject cond = new JSONObject();
        put(cond, "campusHint", SdjtuStudyFormat.DEFAULT_CAMPUS_HINT);
        put(cond, "buildingHint", SdjtuStudyFormat.DEFAULT_BUILDING_HINT);
        put(cond, "week", String.valueOf(curWeek));
        put(cond, "day", String.valueOf(curDay));
        put(cond, "period", "");
        put(cond, "room", "");
        put(cond, "requireBuilding", true);
        put(cond, "campusLabel", SdjtuStudyFormat.DEFAULT_CAMPUS_HINT);
        put(cond, "buildingLabel", SdjtuStudyFormat.DEFAULT_BUILDING_HINT);
        put(cond, "weekLabel", "第" + curWeek + "周");
        put(cond, "dayLabel", SdjtuStudyFormat.weekdayLabel(curDay));

        JSONObject query = new JSONObject();
        put(query, "day", curDay);
        put(query, "period", "all");

        JSONObject req = new JSONObject();
        put(req, "roomAction", "query");
        put(req, "roomConditions", cond);
        put(req, "query", query);
        return req;
    }

    private void openRoomConditionPicker() {
        if (sync != null || awaitingLogin) return;
        JSONObject cachedOptions = choiceData != null ? choiceData : data;
        JSONObject cond = cachedOptions == null ? null : cachedOptions.optJSONObject("roomConditions");
        if (cond != null && cond.optJSONArray("campuses") != null && cond.optJSONArray("campuses").length() > 0) {
            chooseRooms(cachedOptions);
        } else {
            put(request, "roomAction", "options");
            startSync();
        }
    }

    private void readCache() {
        if (rooms()) return;
        JSONObject cached = SdjtuStudyStore.load(this, feature);
        data = cached == null ? null : cached.optJSONObject("data");
        syncedAt = cached == null ? 0 : cached.optLong("syncedAt");
        if (data != null && !data.optString("planChoice").isEmpty()) {
            put(request, "planChoice", data.optString("planChoice"));
        }
    }

    private LinearLayout makeHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setFocusableInTouchMode(true);
        header.setPadding(0, dp(4), 0, dp(4));

        banner = new SessionBanner(this, () -> openLogin(null));
        LinearLayout.LayoutParams bannerLp = new LinearLayout.LayoutParams(-1, -2);
        bannerLp.bottomMargin = dp(8);
        header.addView(banner.view(), bannerLp);

        message = text(13, G.SUB);
        message.setPadding(0, dp(2), 0, dp(8));
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        header.addView(message);

        locateButton = outlineButton("在教务中定位");
        locateButton.setOnClickListener(v -> feature.locate(this, LOCATE_PAGE));
        LinearLayout.LayoutParams locLp = new LinearLayout.LayoutParams(-2, -2);
        locLp.bottomMargin = dp(8);
        header.addView(locateButton, locLp);

        // 顶部摘要卡片（空教室：当前查询条件卡；培养方案：总学分与课程统计卡）
        heroCard = makeCard();
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(-1, -2);
        heroLp.bottomMargin = dp(12);
        header.addView(heroCard, heroLp);

        // 空教室：快速节次筛选横向胶囊条
        HorizontalScrollView periodScroll = new HorizontalScrollView(this);
        periodScroll.setHorizontalScrollBarEnabled(false);
        periodScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        periodBarBox = new LinearLayout(this);
        periodBarBox.setOrientation(LinearLayout.HORIZONTAL);
        periodBarBox.setPadding(0, dp(2), 0, dp(8));
        periodScroll.addView(periodBarBox);
        header.addView(periodScroll, new LinearLayout.LayoutParams(-1, -2));

        // 培养方案：学期横向刻度标签（与学业成绩页同风格）
        HorizontalScrollView termScroll = new HorizontalScrollView(this);
        termScroll.setHorizontalScrollBarEnabled(false);
        termScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        termBarBox = new LinearLayout(this);
        termBarBox.setOrientation(LinearLayout.HORIZONTAL);
        termBarBox.setPadding(0, dp(2), 0, dp(8));
        termScroll.addView(termBarBox);
        header.addView(termScroll, new LinearLayout.LayoutParams(-1, -2));

        // 搜索栏 + 筛选开关
        filters = new LinearLayout(this);
        filters.setGravity(Gravity.CENTER_VERTICAL);
        filters.setPadding(0, dp(2), 0, dp(6));
        search = new EditText(this);
        search.setSingleLine(true);
        search.setTextSize(14f);
        search.setTextColor(G.INK);
        search.setHintTextColor(G.MUTE);
        search.setBackgroundResource(R.drawable.bg_gauge_input);
        search.setPadding(dp(12), dp(8), dp(12), dp(8));
        search.setMinHeight(dp(44));
        search.setHint(rooms() ? "搜索教室号，如 1102" : "搜索课程名称或课程号");
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        filters.addView(search, new LinearLayout.LayoutParams(0, -2, 1f));

        if (rooms()) {
            filterToggleBtn = new TextView(this);
            filterToggleBtn.setTextSize(12.5f);
            filterToggleBtn.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            filterToggleBtn.setGravity(Gravity.CENTER);
            filterToggleBtn.setPadding(dp(12), dp(10), dp(12), dp(10));
            filterToggleBtn.setMinHeight(dp(44));
            filterToggleBtn.setOnClickListener(v -> {
                freeOnly = !freeOnly;
                renderRows();
            });
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-2, -2);
            flp.setMarginStart(dp(8));
            filters.addView(filterToggleBtn, flp);
        }
        header.addView(filters);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) { renderRows(); }
            @Override public void afterTextChanged(Editable s) {}
        });

        tally = text(12, G.MUTE);
        tally.setTypeface(Typeface.MONOSPACE);
        tally.setPadding(dp(2), dp(4), dp(2), dp(8));
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
        emptyAction = Ui.button(this, rooms() ? "查询空教室" : "同步培养方案", true, v -> {
            if (rooms()) {
                if (!"query".equals(request.optString("roomAction"))) {
                    request = defaultRoomQueryRequest();
                }
            }
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
        boolean roomOptions = rooms() && !"query".equals(request.optString("roomAction"));
        if (rooms()) {
            notice = roomOptions
                    ? (autoLogin ? "正在自动登录并读取查询条件，预计 5-10 秒…" : "正在读取查询条件…")
                    : (autoLogin ? "正在自动登录并查询空教室，预计 5-10 秒…" : "正在查询空教室…");
        } else {
            notice = autoLogin ? "正在自动登录并同步培养方案，预计 5-10 秒…" : "正在后台同步培养方案…";
        }
        Toast.makeText(this, notice, autoLogin ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT).show();
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
                if (rooms()) {
                    // 查空教室不需要缓存，仅在当前页面内存展示
                    data = result;
                    choiceData = result;
                    syncedAt = completedAt;
                    selectedPeriodFilter = "";
                    notice = "";
                    JSONArray list = result.optJSONArray("rooms");
                    int total = list == null ? 0 : list.length();
                    int freeCount = 0;
                    for (int i = 0; i < total; i++) {
                        if ("free".equals(SdjtuStudyFormat.effectiveState(list.optJSONObject(i), ""))) freeCount++;
                    }
                    Toast.makeText(SdjtuStudyActivity.this,
                            total > 0 ? "空教室查询完毕：" + freeCount + "/" + total + " 间有空闲"
                                    : "空教室查询完毕：当前条件暂无教室记录",
                            Toast.LENGTH_SHORT).show();
                } else {
                    if (SdjtuStudyStore.save(SdjtuStudyActivity.this, feature, result, completedAt)) {
                        data = result;
                        syncedAt = completedAt;
                        if (!result.optString("planChoice").isEmpty()) {
                            put(request, "planChoice", result.optString("planChoice"));
                        }
                        choiceData = null;
                        notice = "";
                        JSONArray courses = result.optJSONArray("courses");
                        int count = courses == null ? 0 : courses.length();
                        Toast.makeText(SdjtuStudyActivity.this,
                                "培养方案已更新：" + count + " 门课程", Toast.LENGTH_SHORT).show();
                    } else {
                        notice = "本次培养方案保存失败，请重试。";
                    }
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
                notice = "";
                render();
                if (!isFinishing() && !isDestroyed()) {
                    if (rooms()) chooseRooms(options);
                    else choosePlan();
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
            if (!rooms() && details != null && details.getBooleanExtra(WebShellActivity.EXTRA_STUDY_SAVED, false)) {
                readCache();
                canLocate = needsLogin = false;
                choiceData = null;
                notice = "";
                render();
            } else {
                if (rooms()) request = defaultRoomQueryRequest();
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
        roomDialog = new SdjtuRoomConditions(this, actualCurrentWeek(), WeekMath.todayDayOfWeek(),
                selected -> {
                    request = selected;
                    startSync();
                },
                selected -> {
                    roomDialog = null;
                    request = selected;
                    startSync();
                },
                () -> {
                    if (roomDialog != null) request = roomDialog.request("options");
                    roomDialog = null;
                    if (sync != null) sync.cancel();
                    sync = null;
                    notice = "";
                    render();
                });
        String validation = "query".equals(request.optString("roomAction")) ? notice : "";
        roomDialog.show(conditions, validation);
    }

    private void choosePlan() {
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
        if (rooms()) {
            syncButton.setText(busy ? "查询中…" : "修改条件");
        } else {
            syncButton.setText(busy ? "同步中…" : "↻ 同步");
        }

        String status = (busy || data != null) ? notice : "";
        message.setText(status);
        message.setVisibility(status.isEmpty() ? View.GONE : View.VISIBLE);
        locateButton.setVisibility(canLocate && !busy ? View.VISIBLE : View.GONE);
        banner.update(needsLogin, busy, !awaitingLogin);

        renderHeroCard(busy);

        if (rooms()) {
            termBarBox.setVisibility(View.GONE);
            renderPeriodBar();
        } else {
            periodBarBox.setVisibility(View.GONE);
            renderTermBar();
        }

        filters.setVisibility(data != null ? View.VISIBLE : View.GONE);
        if (skeleton != null) skeleton.setVisibility(busy && data == null ? View.VISIBLE : View.GONE);
        if (emptyBox != null) {
            boolean showEmpty = !busy && data == null;
            emptyBox.setVisibility(showEmpty ? View.VISIBLE : View.GONE);
            if (showEmpty) {
                if (failed) {
                    emptyTitle.setText(rooms() ? "空教室查询失败" : "培养方案同步失败");
                    emptySub.setText(notice.isEmpty() ? "未能读取教务数据，请稍后重试。" : notice);
                    emptyAction.setText("重试");
                } else if (rooms()) {
                    int w = actualCurrentWeek();
                    String d = SdjtuStudyFormat.weekdayLabel(WeekMath.todayDayOfWeek());
                    emptyTitle.setText("实时查询空教室");
                    emptySub.setText("默认：" + SdjtuStudyFormat.DEFAULT_CAMPUS_HINT + " · "
                            + SdjtuStudyFormat.DEFAULT_BUILDING_HINT + "\n第 " + w + " 周 · " + d + "（不缓存）");
                    emptyAction.setText("立即查询");
                } else {
                    emptyTitle.setText("暂无本地培养方案");
                    emptySub.setText("点「同步」读取课程体系、学分与开设学期。");
                    emptyAction.setText("同步培养方案");
                }
                emptyAction.setEnabled(!awaitingLogin);
            }
        }
        renderRows();
    }

    private void renderHeroCard(boolean busy) {
        heroCard.removeAllViews();
        if (rooms()) {
            heroCard.setVisibility(View.VISIBLE);
            JSONObject cond = (data != null && data.optJSONObject("roomConditions") != null)
                    ? data.optJSONObject("roomConditions")
                    : request.optJSONObject("roomConditions");
            String campusLabel = labelOfOption(cond, "campuses", cond == null ? "" : cond.optString("campus"),
                    cond == null ? SdjtuStudyFormat.DEFAULT_CAMPUS_HINT : cond.optString("campusLabel", SdjtuStudyFormat.DEFAULT_CAMPUS_HINT));
            String buildingLabel = labelOfOption(cond, "buildings", cond == null ? "" : cond.optString("building"),
                    cond == null ? SdjtuStudyFormat.DEFAULT_BUILDING_HINT : cond.optString("buildingLabel", SdjtuStudyFormat.DEFAULT_BUILDING_HINT));
            String weekNum = cond == null ? "" : cond.optString("week");
            if (weekNum.isEmpty()) weekNum = String.valueOf(actualCurrentWeek());
            int dayNum = data != null && data.optJSONObject("query") != null && data.optJSONObject("query").optInt("day") > 0
                    ? data.optJSONObject("query").optInt("day")
                    : (cond != null && !cond.optString("day").isEmpty()
                            ? parseIntSafe(cond.optString("day"), WeekMath.todayDayOfWeek())
                            : WeekMath.todayDayOfWeek());
            String periodLbl = cond == null ? "" : labelOfOption(cond, "periods", cond.optString("period"), cond.optString("periodLabel"));
            String roomFilter = cond == null ? "" : cond.optString("room").trim();

            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView cap = text(11, G.MUTE);
            cap.setTypeface(Typeface.MONOSPACE);
            cap.setText(syncedAt > 0
                    ? "QUERY · 实时查询 " + new SimpleDateFormat("HH:mm", Locale.US).format(new Date(syncedAt))
                    : "QUERY · 实时查询（不缓存）");
            top.addView(cap, new LinearLayout.LayoutParams(0, -2, 1f));

            TextView change = text(12, G.INK);
            change.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            change.setText(busy ? "查询中…" : "切换条件 ›");
            change.setEnabled(!busy && !awaitingLogin);
            top.addView(change);
            heroCard.addView(top);

            TextView place = text(16, G.INK);
            place.setTypeface(Typeface.DEFAULT_BOLD);
            place.setPadding(0, dp(6), 0, dp(2));
            place.setText(campusLabel + (buildingLabel.isEmpty() ? "" : "  ·  " + buildingLabel));
            heroCard.addView(place);

            StringBuilder meta = new StringBuilder();
            meta.append("第 ").append(weekNum).append(" 周  ·  ").append(SdjtuStudyFormat.weekdayLabel(dayNum));
            meta.append("  ·  ").append(periodLbl.isEmpty() ? "全部节次" : periodLbl);
            if (!roomFilter.isEmpty()) meta.append("  ·  教室 ").append(roomFilter);
            TextView sub = text(12.5f, G.SUB);
            sub.setTypeface(Typeface.MONOSPACE);
            sub.setPadding(0, dp(2), 0, 0);
            sub.setText(meta.toString());
            heroCard.addView(sub);

            heroCard.setClickable(!busy && !awaitingLogin);
            heroCard.setOnClickListener(v -> openRoomConditionPicker());
        } else {
            if (data == null) {
                heroCard.setVisibility(View.GONE);
                return;
            }
            heroCard.setVisibility(View.VISIBLE);
            heroCard.setClickable(false);
            heroCard.setOnClickListener(null);

            JSONArray allCourses = data.optJSONArray("courses");
            SdjtuStudyFormat.PlanStats stats = SdjtuStudyFormat.computePlanStats(allCourses);

            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            TextView cap = text(11, G.MUTE);
            cap.setTypeface(Typeface.MONOSPACE);
            String syncLabel = syncedAt == 0 ? "尚未同步" : "同步于 "
                    + DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(new Date(syncedAt));
            cap.setText("CURRICULUM · " + syncLabel);
            top.addView(cap, new LinearLayout.LayoutParams(0, -2, 1f));

            JSONObject options = choiceData != null ? choiceData : data;
            JSONArray plans = options == null ? null : options.optJSONArray("planChoices");
            if (plans != null && plans.length() > 1) {
                TextView switchPlan = text(12, G.INK);
                switchPlan.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
                switchPlan.setText("切换方案 ›");
                switchPlan.setOnClickListener(v -> choosePlan());
                top.addView(switchPlan);
            }
            heroCard.addView(top);

            String planName = data.optString("planChoice").trim();
            if (!planName.isEmpty()) {
                TextView pn = text(13.5f, G.SUB);
                pn.setPadding(0, dp(4), 0, dp(2));
                pn.setText(planName);
                heroCard.addView(pn);
            }

            LinearLayout metrics = new LinearLayout(this);
            metrics.setOrientation(LinearLayout.HORIZONTAL);
            metrics.setPadding(0, dp(10), 0, dp(2));
            metrics.addView(metricCol(String.valueOf(stats.courseCount), "总课程（门）"),
                    new LinearLayout.LayoutParams(0, -2, 1f));
            metrics.addView(metricCol(SdjtuStudyFormat.formatCredit(stats.totalCredit), "总学分"),
                    new LinearLayout.LayoutParams(0, -2, 1f));
            metrics.addView(metricCol(String.valueOf(stats.requiredCount), "必修课（门）"),
                    new LinearLayout.LayoutParams(0, -2, 1f));
            heroCard.addView(metrics);
        }
    }

    private LinearLayout metricCol(String value, String caption) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView num = text(26, G.INK);
        num.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        num.setText(value);
        col.addView(num);
        TextView cap = text(11, G.MUTE);
        cap.setPadding(0, dp(2), 0, 0);
        cap.setText(caption);
        col.addView(cap);
        return col;
    }

    private void renderPeriodBar() {
        periodBarBox.removeAllViews();
        if (data == null) {
            periodBarBox.setVisibility(View.GONE);
            return;
        }
        JSONObject choices = data.optJSONObject("choices");
        JSONArray periods = choices == null ? null : choices.optJSONArray("periods");
        if (periods == null || periods.length() <= 1) {
            periodBarBox.setVisibility(View.GONE);
            selectedPeriodFilter = "";
            return;
        }
        periodBarBox.setVisibility(View.VISIBLE);
        addPeriodChip("", "全部时段");
        for (int i = 0; i < periods.length(); i++) {
            String p = periods.optString(i).trim();
            if (p.isEmpty()) continue;
            addPeriodChip(p, SdjtuStudyFormat.shortPeriodLabel(p));
        }
    }

    private void addPeriodChip(String value, String label) {
        boolean selected = value.equals(selectedPeriodFilter);
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(12.5f);
        tv.setTypeface(selected ? Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) : Typeface.MONOSPACE);
        tv.setTextColor(selected ? G.ON_INK : G.SUB);
        tv.setPadding(dp(12), dp(7), dp(12), dp(7));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(G.GLASS ? 18 : 6));
        if (selected) {
            bg.setColor(G.INK);
        } else {
            bg.setColor(G.CARD);
            bg.setStroke(Math.max(1, dp(1)), G.CARD_STROKE);
        }
        tv.setBackground(bg);
        tv.setOnClickListener(v -> {
            selectedPeriodFilter = value;
            renderPeriodBar();
            renderRows();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.setMarginEnd(dp(8));
        periodBarBox.addView(tv, lp);
    }

    private void renderTermBar() {
        termBarBox.removeAllViews();
        if (data == null) {
            termBarBox.setVisibility(View.GONE);
            return;
        }
        JSONArray courses = data.optJSONArray("courses");
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (int i = 0; courses != null && i < courses.length(); i++) {
            JSONObject c = courses.optJSONObject(i);
            if (c != null && !c.optString("term").trim().isEmpty()) {
                terms.add(c.optString("term").trim());
            }
        }
        if (terms.isEmpty()) {
            termBarBox.setVisibility(View.GONE);
            term = "";
            return;
        }
        if (!term.isEmpty() && !terms.contains(term)) term = "";
        termBarBox.setVisibility(View.VISIBLE);
        addTermTab("", "全部学期");
        for (String t : terms) {
            addTermTab(t, termLabel(t));
        }
    }

    private void addTermTab(String termId, String label) {
        boolean selected = termId.equals(term);
        LinearLayout tab = new LinearLayout(this);
        tab.setOrientation(LinearLayout.VERTICAL);
        tab.setPadding(0, 0, dp(18), 0);

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(13f);
        tv.setTypeface(selected ? Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) : Typeface.MONOSPACE);
        tv.setTextColor(selected ? G.INK : G.MUTE);
        tv.setPadding(0, dp(4), 0, dp(8));
        tv.setSingleLine(true);
        tab.addView(tv);

        View line = new View(this);
        line.setBackgroundColor(selected ? G.INK : 0x00000000);
        tab.addView(line, new LinearLayout.LayoutParams(-1, dp(2)));

        tab.setOnClickListener(v -> {
            term = termId;
            renderTermBar();
            renderRows();
        });
        termBarBox.addView(tab);
    }

    private static String termLabel(String value) {
        return value.matches("[0-9]{1,2}") ? "第" + value + "学期" : value;
    }

    private void renderRows() {
        if (tally == null) return;
        List<Item> items = new ArrayList<>();
        String needle = search.getText().toString().trim().toLowerCase(Locale.ROOT);

        if (rooms()) {
            if (filterToggleBtn != null) {
                filterToggleBtn.setText(freeOnly ? "仅看空闲" : "全部教室");
                GradientDrawable bg = new GradientDrawable();
                bg.setCornerRadius(dp(G.GLASS ? 12 : 6));
                if (freeOnly) {
                    bg.setColor(freeChipBg());
                    filterToggleBtn.setTextColor(freeChipFg());
                } else {
                    bg.setColor(G.CARD);
                    bg.setStroke(Math.max(1, dp(1)), G.CARD_STROKE);
                    filterToggleBtn.setTextColor(G.SUB);
                }
                filterToggleBtn.setBackground(bg);
            }

            JSONArray records = data == null ? null : data.optJSONArray("rooms");
            JSONObject cond = data == null ? null : data.optJSONObject("roomConditions");
            String buildingHint = labelOfOption(cond, "buildings",
                    cond == null ? "" : cond.optString("building"),
                    cond == null ? "" : cond.optString("buildingLabel"));

            List<RoomEntry> matched = new ArrayList<>();
            int totalRecords = records == null ? 0 : records.length();
            int totalFree = 0;
            LinkedHashSet<String> distinctBuildings = new LinkedHashSet<>();

            for (int i = 0; i < totalRecords; i++) {
                JSONObject r = records.optJSONObject(i);
                if (r == null) continue;
                String rawName = r.optString("name");
                SdjtuStudyFormat.RoomLabel split = SdjtuStudyFormat.splitRoomLabel(rawName, buildingHint);
                String state = SdjtuStudyFormat.effectiveState(r, selectedPeriodFilter);
                if ("free".equals(state)) totalFree++;
                if (!needle.isEmpty() && !(rawName + " " + split.room + " " + split.building)
                        .toLowerCase(Locale.ROOT).contains(needle)) continue;
                if (freeOnly && !"free".equals(state)) continue;
                if (!split.building.isEmpty()) distinctBuildings.add(split.building);
                matched.add(new RoomEntry(split.building, split.room, state,
                        SdjtuStudyFormat.roomBadge(r, selectedPeriodFilter),
                        SdjtuStudyFormat.roomPeriods(r),
                        SdjtuStudyFormat.freePeriodCount(r)));
            }

            // 空闲多的教室排在前面，同空闲数按教室号自然排序
            matched.sort((a, b) -> {
                boolean af = "free".equals(a.state), bf = "free".equals(b.state);
                if (af != bf) return af ? -1 : 1;
                if (selectedPeriodFilter.isEmpty() && a.freeCount != b.freeCount) {
                    return Integer.compare(b.freeCount, a.freeCount);
                }
                return a.room.compareToIgnoreCase(b.room);
            });

            boolean showBuildingOnCard = distinctBuildings.size() > 1;
            for (RoomEntry e : matched) {
                items.add(Item.room(e, showBuildingOnCard));
            }

            String countText;
            if (data == null) {
                countText = "";
            } else if (totalRecords == 0) {
                countText = "本次教务查询返回 0 间教室";
            } else if (matched.isEmpty()) {
                countText = freeOnly
                        ? "当前筛选时段暂无空闲教室（共 " + totalRecords + " 间，可切「全部教室」查看）"
                        : "没有符合搜索条件的教室";
            } else {
                String scopeLbl = selectedPeriodFilter.isEmpty()
                        ? "当天有空闲" : SdjtuStudyFormat.shortPeriodLabel(selectedPeriodFilter) + "空闲";
                countText = "显示 " + matched.size() + " 间 · " + scopeLbl + " " + totalFree + "/" + totalRecords + " 间";
            }
            tally.setText(countText);
            tally.setVisibility(data == null ? View.GONE : View.VISIBLE);
        } else {
            JSONArray records = data == null ? null : data.optJSONArray("courses");
            Map<String, List<CourseEntry>> groups = new LinkedHashMap<>();
            Map<String, Double> groupCredits = new LinkedHashMap<>();
            int count = 0;
            double filteredCredit = 0.0;

            for (int i = 0; records != null && i < records.length(); i++) {
                JSONObject c = records.optJSONObject(i);
                if (c == null) continue;
                String name = c.optString("name").trim();
                String code = c.optString("code").trim();
                if (!needle.isEmpty() && !(name + " " + code).toLowerCase(Locale.ROOT).contains(needle)) continue;
                String sem = c.optString("term").trim();
                if (!term.isEmpty() && !term.equals(sem)) continue;

                String section = c.optString("system").trim();
                if (section.isEmpty()) section = c.optString("nature").trim();
                if (section.isEmpty()) section = "培养方案课程";

                double cr = SdjtuStudyFormat.parseCredit(c.optString("credit"));
                filteredCredit += cr;
                count++;

                CourseEntry ce = new CourseEntry(
                        name, code, sem.isEmpty() ? "" : termLabel(sem),
                        c.optString("credit").trim(),
                        c.optString("hours").trim(),
                        c.optString("nature").trim(),
                        c.optString("kind").trim(),
                        c.optString("status").trim());
                groups.computeIfAbsent(section, k -> new ArrayList<>()).add(ce);
                groupCredits.put(section, groupCredits.getOrDefault(section, 0.0) + cr);
            }

            for (Map.Entry<String, List<CourseEntry>> g : groups.entrySet()) {
                double gCredit = groupCredits.getOrDefault(g.getKey(), 0.0);
                items.add(Item.group(g.getKey(),
                        g.getValue().size() + " 门 · " + SdjtuStudyFormat.formatCredit(gCredit) + " 学分"));
                List<CourseEntry> list = g.getValue();
                for (int idx = 0; idx < list.size(); idx++) {
                    items.add(Item.course(list.get(idx), idx == 0, idx == list.size() - 1));
                }
            }

            String countText;
            if (data == null) {
                countText = "";
            } else if (count == 0) {
                boolean any = records != null && records.length() > 0;
                countText = any ? "没有符合筛选条件的课程" : "此方案未列出计划课程";
            } else {
                countText = count + " 门课程 · 共 " + SdjtuStudyFormat.formatCredit(filteredCredit) + " 学分";
            }
            tally.setText(countText);
            tally.setVisibility(data == null ? View.GONE : View.VISIBLE);
        }

        adapter.items = items;
        adapter.notifyDataSetChanged();
    }

    private int freeChipBg() { return G.NIGHT ? 0xFF1E3324 : 0xFFE6F0E8; }
    private int freeChipFg() { return G.NIGHT ? 0xFF8CD19B : 0xFF2F5D3A; }
    private int warnChipBg() { return G.NIGHT ? 0xFF332619 : 0xFFF3EAE0; }
    private int warnChipFg() { return G.NIGHT ? 0xFFE8A464 : 0xFFB4540A; }

    private static final class RoomEntry {
        final String building, room, state, badge;
        final List<SdjtuStudyFormat.PeriodSlot> periods;
        final int freeCount;

        RoomEntry(String building, String room, String state, String badge,
                  List<SdjtuStudyFormat.PeriodSlot> periods, int freeCount) {
            this.building = building;
            this.room = room;
            this.state = state;
            this.badge = badge;
            this.periods = periods;
            this.freeCount = freeCount;
        }
    }

    private static final class CourseEntry {
        final String name, code, term, credit, hours, nature, kind, status;

        CourseEntry(String name, String code, String term, String credit,
                    String hours, String nature, String kind, String status) {
            this.name = name;
            this.code = code;
            this.term = term;
            this.credit = credit;
            this.hours = hours;
            this.nature = nature;
            this.kind = kind;
            this.status = status;
        }
    }

    private static final class Item {
        static final int TYPE_GROUP = 0;
        static final int TYPE_ROOM = 1;
        static final int TYPE_COURSE = 2;

        final int type;
        String title, subtitle;
        RoomEntry room;
        boolean showBuilding;
        CourseEntry course;
        boolean firstInCard, lastInCard;

        private Item(int type) { this.type = type; }

        static Item group(String title, String subtitle) {
            Item i = new Item(TYPE_GROUP);
            i.title = title;
            i.subtitle = subtitle;
            return i;
        }

        static Item room(RoomEntry r, boolean showBuilding) {
            Item i = new Item(TYPE_ROOM);
            i.room = r;
            i.showBuilding = showBuilding;
            return i;
        }

        static Item course(CourseEntry c, boolean first, boolean last) {
            Item i = new Item(TYPE_COURSE);
            i.course = c;
            i.firstInCard = first;
            i.lastInCard = last;
            return i;
        }
    }

    private final class Results extends BaseAdapter {
        List<Item> items = new ArrayList<>();

        @Override public int getCount() { return items.size(); }
        @Override public Item getItem(int position) { return items.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 3; }
        @Override public int getItemViewType(int position) { return items.get(position).type; }
        @Override public boolean isEnabled(int position) { return false; }
        @Override public boolean areAllItemsEnabled() { return false; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Item item = getItem(position);
            if (item.type == Item.TYPE_GROUP) {
                return bindGroup(item, recycled);
            }
            if (item.type == Item.TYPE_ROOM) {
                return bindRoom(item);
            }
            return bindCourse(item);
        }

        private View bindGroup(Item item, View recycled) {
            LinearLayout row = (LinearLayout) recycled;
            if (row == null) {
                row = new LinearLayout(SdjtuStudyActivity.this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setPadding(dp(2), dp(16), dp(2), dp(8));
                TextView left = text(13.5f, G.INK);
                left.setTypeface(Typeface.DEFAULT_BOLD);
                row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));
                TextView right = text(11.5f, G.MUTE);
                right.setTypeface(Typeface.MONOSPACE);
                row.addView(right);
            }
            ((TextView) row.getChildAt(0)).setText(item.title);
            ((TextView) row.getChildAt(1)).setText(item.subtitle);
            return row;
        }

        private View bindRoom(Item item) {
            RoomEntry r = item.room;
            LinearLayout outer = new LinearLayout(SdjtuStudyActivity.this);
            outer.setOrientation(LinearLayout.VERTICAL);
            outer.setPadding(0, 0, 0, dp(10));

            LinearLayout card = makeCard();
            LinearLayout top = new LinearLayout(SdjtuStudyActivity.this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);

            LinearLayout nameCol = new LinearLayout(SdjtuStudyActivity.this);
            nameCol.setOrientation(LinearLayout.VERTICAL);
            TextView roomTv = text(17.5f, G.INK);
            roomTv.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            roomTv.setText(r.room);
            nameCol.addView(roomTv);
            if (item.showBuilding && !r.building.isEmpty()) {
                TextView bldTv = text(11.5f, G.SUB);
                bldTv.setText(r.building);
                nameCol.addView(bldTv);
            }
            top.addView(nameCol, new LinearLayout.LayoutParams(0, -2, 1f));

            TextView badge = text(11.5f,
                    "free".equals(r.state) ? freeChipFg() : "unknown".equals(r.state) ? warnChipFg() : G.MUTE);
            badge.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
            badge.setPadding(dp(9), dp(4), dp(9), dp(4));
            GradientDrawable bbg = new GradientDrawable();
            bbg.setCornerRadius(dp(G.GLASS ? 12 : 4));
            bbg.setColor("free".equals(r.state) ? freeChipBg() : "unknown".equals(r.state) ? warnChipBg() : G.CHIP);
            badge.setBackground(bbg);
            badge.setText(r.badge);
            top.addView(badge);
            card.addView(top);

            if (!r.periods.isEmpty()) {
                LinearLayout strip = new LinearLayout(SdjtuStudyActivity.this);
                strip.setOrientation(LinearLayout.HORIZONTAL);
                strip.setPadding(0, dp(10), 0, 0);
                for (int i = 0; i < r.periods.size(); i++) {
                    SdjtuStudyFormat.PeriodSlot slot = r.periods.get(i);
                    boolean isFree = "free".equals(slot.state);
                    boolean isUnknown = "unknown".equals(slot.state);
                    boolean focused = !selectedPeriodFilter.isEmpty() && selectedPeriodFilter.equals(slot.period);

                    LinearLayout cell = new LinearLayout(SdjtuStudyActivity.this);
                    cell.setOrientation(LinearLayout.VERTICAL);
                    cell.setGravity(Gravity.CENTER);
                    cell.setPadding(dp(4), dp(6), dp(4), dp(6));

                    GradientDrawable cbg = new GradientDrawable();
                    cbg.setCornerRadius(dp(G.GLASS ? 10 : 4));
                    cbg.setColor(isFree ? freeChipBg() : isUnknown ? warnChipBg() : G.CHIP);
                    if (focused) {
                        cbg.setStroke(dp(1.5f), G.INK);
                    }
                    cell.setBackground(cbg);

                    TextView pLabel = text(10.5f, isFree ? freeChipFg() : isUnknown ? warnChipFg() : G.MUTE);
                    pLabel.setTypeface(Typeface.MONOSPACE);
                    pLabel.setSingleLine(true);
                    pLabel.setText(slot.shortLabel.replace("节", ""));
                    cell.addView(pLabel);

                    TextView pState = text(11.5f, isFree ? freeChipFg() : isUnknown ? warnChipFg() : G.MUTE);
                    pState.setTypeface(Typeface.DEFAULT_BOLD);
                    pState.setText(isFree ? "空" : isUnknown ? "?" : "占");
                    cell.addView(pState);

                    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, -2, 1f);
                    if (i > 0) clp.setMarginStart(dp(6));
                    strip.addView(cell, clp);
                }
                card.addView(strip);
            }

            outer.addView(card);
            return outer;
        }

        private View bindCourse(Item item) {
            CourseEntry c = item.course;
            LinearLayout wrap = new LinearLayout(SdjtuStudyActivity.this);
            wrap.setOrientation(LinearLayout.VERTICAL);
            wrap.setPadding(0, 0, 0, item.lastInCard ? dp(6) : 0);

            LinearLayout box = new LinearLayout(SdjtuStudyActivity.this);
            box.setOrientation(LinearLayout.VERTICAL);
            float rad = dp(G.CARD_RADIUS);
            float topR = item.firstInCard ? rad : 0f;
            float botR = item.lastInCard ? rad : 0f;
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(G.CARD);
            bg.setCornerRadii(new float[]{topR, topR, topR, topR, botR, botR, botR, botR});
            bg.setStroke(Math.max(1, dp(1)), G.CARD_STROKE);
            box.setBackground(bg);

            if (!item.firstInCard) {
                View hair = new View(SdjtuStudyActivity.this);
                hair.setBackgroundColor(G.LINE);
                LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(-1, 1);
                hlp.setMargins(dp(16), 0, dp(16), 0);
                box.addView(hair, hlp);
            }

            LinearLayout row = new LinearLayout(SdjtuStudyActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), dp(13), dp(16), dp(13));

            LinearLayout left = new LinearLayout(SdjtuStudyActivity.this);
            left.setOrientation(LinearLayout.VERTICAL);

            TextView titleTv = text(15, G.INK);
            titleTv.setTypeface(Typeface.DEFAULT_BOLD);
            titleTv.setText(c.name);
            left.addView(titleTv);

            List<String> metaParts = new ArrayList<>();
            if (!c.code.isEmpty()) metaParts.add(c.code);
            if (!c.term.isEmpty()) metaParts.add(c.term);
            if (!c.hours.isEmpty()) metaParts.add(c.hours + " 学时");
            if (!metaParts.isEmpty()) {
                TextView metaTv = text(12, G.SUB);
                metaTv.setTypeface(Typeface.MONOSPACE);
                metaTv.setPadding(0, dp(3), 0, 0);
                metaTv.setText(android.text.TextUtils.join(" · ", metaParts));
                left.addView(metaTv);
            }

            List<String> badges = new ArrayList<>();
            if (!c.nature.isEmpty()) badges.add(c.nature);
            if (!c.kind.isEmpty() && !c.kind.equals(c.nature)) badges.add(c.kind);
            if (!c.status.isEmpty()) badges.add(c.status);
            if (!badges.isEmpty()) {
                LinearLayout tagRow = new LinearLayout(SdjtuStudyActivity.this);
                tagRow.setOrientation(LinearLayout.HORIZONTAL);
                tagRow.setPadding(0, dp(6), 0, 0);
                for (String b : badges) {
                    TextView pill = text(10.5f, b.contains("已修") || b.contains("通过") ? freeChipFg() : G.SUB);
                    pill.setTypeface(Typeface.DEFAULT_BOLD);
                    pill.setPadding(dp(7), dp(2), dp(7), dp(2));
                    GradientDrawable pbg = new GradientDrawable();
                    pbg.setCornerRadius(dp(4));
                    pbg.setColor(b.contains("已修") || b.contains("通过") ? freeChipBg() : G.CHIP);
                    pill.setBackground(pbg);
                    pill.setText(b);
                    LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(-2, -2);
                    plp.setMarginEnd(dp(6));
                    tagRow.addView(pill, plp);
                }
                left.addView(tagRow);
            }

            row.addView(left, new LinearLayout.LayoutParams(0, -2, 1f));

            if (!c.credit.isEmpty()) {
                LinearLayout right = new LinearLayout(SdjtuStudyActivity.this);
                right.setOrientation(LinearLayout.VERTICAL);
                right.setGravity(Gravity.END);
                right.setPadding(dp(12), 0, 0, 0);
                TextView crNum = text(18, G.INK);
                crNum.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
                crNum.setText(c.credit);
                right.addView(crNum);
                TextView crCap = text(10.5f, G.MUTE);
                crCap.setText("学分");
                right.addView(crCap);
                row.addView(right);
            }

            box.addView(row);
            wrap.addView(box);
            return wrap;
        }
    }

    private LinearLayout makeCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(dp(G.CARD_RADIUS));
        bg.setColor(G.CARD);
        bg.setStroke(Math.max(1, dp(1)), G.CARD_STROKE);
        card.setBackground(bg);
        return card;
    }

    private static String labelOfOption(@Nullable JSONObject cond, String arrayKey, String value, String fallback) {
        if (cond != null && value != null && !value.isEmpty()) {
            JSONArray arr = cond.optJSONArray(arrayKey);
            for (int i = 0; arr != null && i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null && value.equals(o.optString("value"))) {
                    String lbl = o.optString("label").trim();
                    if (!lbl.isEmpty()) return lbl;
                }
            }
        }
        return fallback == null ? "" : fallback;
    }

    private static int parseIntSafe(String s, int fallback) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return fallback; }
    }

    private TextView text(float sp, int color) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        return t;
    }

    private Button outlineButton(String label) {
        Button b = new androidx.appcompat.widget.AppCompatButton(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13.5f);
        b.setTextColor(G.INK);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setBackgroundResource(R.drawable.bg_gauge_outline);
        b.setStateListAnimator(null);
        b.setPadding(dp(16), 0, dp(16), 0);
        b.setMinHeight(dp(44));
        return b;
    }

    static void put(JSONObject into, String key, Object value) {
        try { into.put(key, value); } catch (JSONException e) { throw new IllegalArgumentException(key, e); }
    }

    private int dp(float n) { return Math.round(n * getResources().getDisplayMetrics().density); }

    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("selectedPeriodFilter", selectedPeriodFilter);
        out.putBoolean("freeOnly", freeOnly);
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
