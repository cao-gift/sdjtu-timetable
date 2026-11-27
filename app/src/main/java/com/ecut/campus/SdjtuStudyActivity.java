package com.ecut.campus;

import android.app.Activity;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 交院教务同步页（查空教室 / 培养方案）：
 * 页面编排与同步状态机保留在此；具体渲染拆至 {@link SdjtuStudyRoomsRenderer} 与 {@link SdjtuStudyPlanRenderer}。
 */
public final class SdjtuStudyActivity extends AppCompatActivity {
    private static final int SIGN_IN = 1, LOCATE_PAGE = 2;

    private SdjtuAcademicPages.Feature feature;
    private SdjtuStudySync sync;
    private SdjtuStudyRoomsRenderer roomsRenderer;
    private SdjtuStudyPlanRenderer planRenderer;
    private JSONObject data, choiceData;
    private SdjtuRoomConditions roomDialog;
    private JSONObject request = new JSONObject();
    private long syncedAt;
    private String notice = "", term = "";
    private String selectedPeriodFilter = "";
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
        roomsRenderer = new SdjtuStudyRoomsRenderer(this);
        planRenderer = new SdjtuStudyPlanRenderer(this);
        setContentView(R.layout.activity_sdjtu_study);
        EdgeToEdge.applyContent(this, true);
        ((TextView) findViewById(R.id.study_title)).setText(feature.title);
        findViewById(R.id.study_back).setOnClickListener(v -> finish());
        syncButton = findViewById(R.id.study_sync);
        syncButton.setOnClickListener(v -> {
            if (rooms()) openRoomConditionPicker();
            else startSync();
        });

        ListView list = findViewById(R.id.study_list);
        list.setItemsCanFocus(true);
        list.addHeaderView(makeHeader(), null, false);
        list.setAdapter(adapter);

        if (rooms()) {
            SdjtuStudyStore.clearRooms(this);
            request = roomsRenderer.defaultQueryRequest(actualCurrentWeek(), WeekMath.todayDayOfWeek());
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

        heroCard = makeCard(this);
        LinearLayout.LayoutParams heroLp = new LinearLayout.LayoutParams(-1, -2);
        heroLp.bottomMargin = dp(12);
        header.addView(heroCard, heroLp);

        HorizontalScrollView periodScroll = new HorizontalScrollView(this);
        periodScroll.setHorizontalScrollBarEnabled(false);
        periodScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        periodBarBox = new LinearLayout(this);
        periodBarBox.setOrientation(LinearLayout.HORIZONTAL);
        periodBarBox.setPadding(0, dp(2), 0, dp(8));
        periodScroll.addView(periodBarBox);
        header.addView(periodScroll, new LinearLayout.LayoutParams(-1, -2));

        HorizontalScrollView termScroll = new HorizontalScrollView(this);
        termScroll.setHorizontalScrollBarEnabled(false);
        termScroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        termBarBox = new LinearLayout(this);
        termBarBox.setOrientation(LinearLayout.HORIZONTAL);
        termBarBox.setPadding(0, dp(2), 0, dp(8));
        termScroll.addView(termBarBox);
        header.addView(termScroll, new LinearLayout.LayoutParams(-1, -2));

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
            if (rooms() && !"query".equals(request.optString("roomAction"))) {
                request = roomsRenderer.defaultQueryRequest(actualCurrentWeek(), WeekMath.todayDayOfWeek());
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

    private void startSync() { startSync(true); }

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
                    data = result;
                    choiceData = result;
                    syncedAt = completedAt;
                    selectedPeriodFilter = "";
                    notice = "";
                    rememberRoomPlaceFrom(result.optJSONObject("roomConditions"));
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
                if (rooms()) request = roomsRenderer.defaultQueryRequest(actualCurrentWeek(), WeekMath.todayDayOfWeek());
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
                    rememberRoomPlaceFrom(selected.optJSONObject("roomConditions"));
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
        if (rooms()) {
            syncButton.setVisibility(View.GONE);
        } else {
            syncButton.setVisibility(View.VISIBLE);
            syncButton.setEnabled(!busy && !awaitingLogin);
            syncButton.setText(busy ? "同步中…" : "↻ 同步");
        }

        String status = (busy || data != null) ? notice : "";
        message.setText(status);
        message.setVisibility(status.isEmpty() ? View.GONE : View.VISIBLE);
        locateButton.setVisibility(canLocate && !busy ? View.VISIBLE : View.GONE);
        banner.update(needsLogin, busy, !awaitingLogin);

        if (rooms()) {
            roomsRenderer.renderHeroCard(heroCard, data, request, syncedAt, actualCurrentWeek(), busy, awaitingLogin,
                    () -> {
                        if (!"query".equals(request.optString("roomAction"))) put(request, "roomAction", "query");
                        startSync();
                    },
                    this::openRoomConditionPicker);
            termBarBox.setVisibility(View.GONE);
            selectedPeriodFilter = roomsRenderer.renderPeriodBar(periodBarBox, data, selectedPeriodFilter, val -> {
                selectedPeriodFilter = val;
                render();
            });
        } else {
            planRenderer.renderHeroCard(heroCard, data, choiceData, syncedAt, this::choosePlan);
            periodBarBox.setVisibility(View.GONE);
            term = planRenderer.renderTermBar(termBarBox, data, term, val -> {
                term = val;
                render();
            });
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
                    emptyAction.setVisibility(View.VISIBLE);
                } else if (rooms()) {
                    emptyTitle.setText("点击上方「查询」获取空教室");
                    emptySub.setText("可先修改校区、教学楼、周次或星期，查询结果不缓存。");
                    emptyAction.setVisibility(View.GONE);
                } else {
                    emptyTitle.setText("暂无本地培养方案");
                    emptySub.setText("点「同步」读取课程体系、学分与开设学期。");
                    emptyAction.setText("同步培养方案");
                    emptyAction.setVisibility(View.VISIBLE);
                }
                emptyAction.setEnabled(!awaitingLogin);
            }
        }
        renderRows();
    }

    private void rememberRoomPlaceFrom(@Nullable JSONObject cond) {
        if (cond == null) return;
        String cVal = cond.optString("campus").trim();
        String cLbl = SdjtuStudyRoomsRenderer.labelOfOption(cond, "campuses", cVal, cond.optString("campusLabel"));
        String bVal = cond.optString("building").trim();
        String bLbl = SdjtuStudyRoomsRenderer.labelOfOption(cond, "buildings", bVal, cond.optString("buildingLabel"));
        if (Prefs.saveStudyRoomPlace(this, cVal, cLbl, bVal, bLbl)) {
            RainWeather.clearCache(this);
            RainWeather.refreshIfNeeded(this, true, null);
        }
    }

    private void renderRows() {
        if (tally == null) return;
        String needle = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        if (rooms()) {
            roomsRenderer.styleFreeOnlyButton(filterToggleBtn, freeOnly);
            int nowMin = ClassPlans.nowMinutes(System.currentTimeMillis());
            SdjtuStudyRoomsRenderer.RowsResult res = roomsRenderer.buildRows(
                    data, needle, selectedPeriodFilter, freeOnly,
                    actualCurrentWeek(), WeekMath.todayDayOfWeek(), nowMin,
                    ScheduleSettingsStore.load(this));
            tally.setText(res.tallyText);
            tally.setVisibility(data == null ? View.GONE : View.VISIBLE);
            adapter.items = res.items;
        } else {
            SdjtuStudyPlanRenderer.RowsResult res = planRenderer.buildRows(data, needle, term);
            tally.setText(res.tallyText);
            tally.setVisibility(data == null ? View.GONE : View.VISIBLE);
            adapter.items = res.items;
        }
        adapter.notifyDataSetChanged();
    }

    static final class Item {
        static final int TYPE_GROUP = 0;
        static final int TYPE_ROOM = 1;
        static final int TYPE_COURSE = 2;

        final int type;
        String title, subtitle;
        SdjtuStudyRoomsRenderer.RoomEntry room;
        boolean showBuilding;
        SdjtuStudyPlanRenderer.CourseEntry course;
        boolean firstInCard, lastInCard;

        private Item(int type) { this.type = type; }

        static Item group(String title, String subtitle) {
            Item i = new Item(TYPE_GROUP);
            i.title = title;
            i.subtitle = subtitle;
            return i;
        }

        static Item room(SdjtuStudyRoomsRenderer.RoomEntry r, boolean showBuilding) {
            Item i = new Item(TYPE_ROOM);
            i.room = r;
            i.showBuilding = showBuilding;
            return i;
        }

        static Item course(SdjtuStudyPlanRenderer.CourseEntry c, boolean first, boolean last) {
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
            if (item.type == Item.TYPE_GROUP) return planRenderer.bindGroupRow(item, recycled);
            if (item.type == Item.TYPE_ROOM) return roomsRenderer.bindRoomCard(item, selectedPeriodFilter);
            return planRenderer.bindCourseCard(item);
        }
    }

    static LinearLayout makeCard(Activity a) {
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout card = new LinearLayout(a);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(Math.round(16 * d), Math.round(14 * d), Math.round(16 * d), Math.round(14 * d));
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(G.CARD_RADIUS * d);
        bg.setColor(G.CARD);
        bg.setStroke(Math.max(1, Math.round(d)), G.CARD_STROKE);
        card.setBackground(bg);
        return card;
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
