package com.ecut.campus;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.ecut.campus.data.SemesterArchive;
import com.ecut.campus.data.Timetable;
import com.ecut.campus.data.TimetableStore;
import com.google.android.material.color.DynamicColors;


public class MainActivity extends AppCompatActivity {

    static final String EXTRA_ACTION = "tangli_action";
    static final String EXTRA_VALUE = "tangli_value";

    static void launchAction(Activity from, String action, String value) {
        Intent i = new Intent(from, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        i.putExtra(EXTRA_ACTION, action);
        i.putExtra(EXTRA_VALUE, value);
        from.startActivity(i);
    }

    static final int TAB_TIMETABLE = 0;
    static final int TAB_SETTINGS = 1;

    private View campusFragment;
    private View settingsFragment;
    private TimetableView timetableView;
    private Timetable currentTimetable = new Timetable();
    private ScheduleSettings scheduleSettings = ScheduleSettings.createDefault();

    private CapsuleDock textDock;
    private DockBlurView dockBlur;
    private SettingsTab settings;
    private ViewGroup syncHost;
    CampusSyncClient activeSync;
    boolean autoLoginBusy;
    int currentTab = TAB_TIMETABLE;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        DynamicColors.applyToActivityIfAvailable(this);
        super.onCreate(savedInstanceState);
        G.init(this);
        setContentView(R.layout.activity_main);

        ViewGroup container = findViewById(R.id.content_container);
        syncHost = findViewById(R.id.sync_host);
        LayoutInflater inflater = getLayoutInflater();
        contentContainer = container;
        // 冷启动只建课表页，第一次打开设置时再创建设置页。
        campusFragment = inflater.inflate(R.layout.fragment_campus, container, false);
        container.addView(campusFragment);

        textDock = findViewById(R.id.text_dock);
        dockBlur = findViewById(R.id.dock_blur);
        if (dockBlur != null) {
            // 纸墨的胶囊是实心墨色，用不着模糊层
            if (G.GLASS) dockBlur.setSource(container);
            else dockBlur.setVisibility(View.GONE);
        }
        textDock.setOnTabSelectedListener(this::showTab);
        // 在课表页再点一次「课表」：回到本周（看别的周看迷路时最顺手的出口）
        textDock.setOnTabReselectedListener(tab -> {
            if (tab != TAB_TIMETABLE) return;
            int actual = actualCurrentWeek();
            if (Prefs.currentWeek(this) == actual) return;
            Prefs.setCurrentWeek(this, actual);
            refreshTimetableTab();
            Toast.makeText(this, "已回到本周（第 " + actual + " 周）", Toast.LENGTH_SHORT).show();
        });

        bindTimetableTab();
        runStartupMigrationsOnce();

        textDock.post(() -> {
            if (timetableView != null) timetableView.setBottomInset(dockInset());
        });

        showTab(TAB_TIMETABLE);
        // 冷启动先加载当前课表，再按所属学期定位到现实中的本周，onResume 就不必再刷一遍
        String today = WeekMath.todayDateLabel();
        if (!today.equals(lastShownDay)) {
            lastShownDay = today;
            int actual = actualCurrentWeek();
            if (Prefs.currentWeek(this) != actual) {
                Prefs.setCurrentWeek(this, actual);
                refreshTimetableTab();
            }
        }
        skipNextResumeRefresh = true;
        if (ThemeReveal.restoreTab >= 0) {
            int tab = ThemeReveal.restoreTab, page = ThemeReveal.restorePage;
            ThemeReveal.restoreTab = -1;
            ThemeReveal.restorePage = -1;
            showTab(tab);
            if (tab == TAB_SETTINGS && page > 0 && page != R.id.settings_page_root) settings.showPage(page, false);
        }
        ThemeReveal.play(this);
        refreshUpdateBadges();
        // 首帧画完再静默查更新（Wi-Fi 与流量均可，不弹框，仅刷新红点角标）
        container.postDelayed(() -> {
            if (!isFinishing()) Updater.autoCheck(this, this::refreshUpdateBadges);
        }, 1500);
        Intent launch = getIntent();
        if (launch != null && launch.hasExtra(EXTRA_ACTION)) {
            container.post(() -> handleExternalIntent(launch));
        }
    }

    void refreshUpdateBadges() {
        if (textDock != null) {
            textDock.setBadge(TAB_SETTINGS, Updater.hasAvailableUpdate(this));
        }
        if (settings != null) {
            settings.refreshAboutBadge();
        }
    }

    void refreshDockBlur() {
        if (dockBlur != null) dockBlur.markDirty();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleExternalIntent(intent);
    }

    /** 其它页面或桌面组件转过来的动作（同步、切页签）。 */
    private void handleExternalIntent(Intent intent) {
        if (intent == null || isFinishing()) return;
        String action = intent.getStringExtra(EXTRA_ACTION);
        if (action != null) {
            String value = intent.getStringExtra(EXTRA_VALUE);
            intent.removeExtra(EXTRA_ACTION);
            runHostAction(action, value == null ? "" : value);
        }
    }

    public void runHostAction(String action, String value) {
        if ("sync".equals(action)) {
            showTab(TAB_TIMETABLE);
            if ("grades".equals(value)) syncGrades();
            else if ("history".equals(value)) historySync.start();
            else syncTimetable();
        } else if ("export".equals(action) && "ics".equals(value)) {
            exportIcs();
        } else if ("open".equals(action)) {
            if ("timetable".equals(value) || "today".equals(value)) {
                showTab(TAB_TIMETABLE);
                if ("today".equals(value)) jumpToWeek(actualCurrentWeek());
            } else if ("settings".equals(value)) {
                showTab(TAB_SETTINGS);
            } else if ("grades".equals(value)) {
                startActivity(new Intent(this, GradesActivity.class));
            } else if ("exams".equals(value)) {
                startActivity(new Intent(this, SdjtuExamActivity.class));
            } else if ("rooms".equals(value)) {
                startActivity(new Intent(this, SdjtuStudyActivity.class)
                        .putExtra(WebShellActivity.EXTRA_TARGET_KEY, SdjtuAcademicPages.Feature.ROOMS.target));
            }
        }
    }

    private ViewGroup contentContainer;
    private boolean skipNextResumeRefresh;
    private static final String STARTUP_PREFS = "startup_meta";

    /**
     * 学期数据的清理 / 迁移 / 镜像对齐原来每次冷启动都读写 JSON；
     * 它们都是一次性修复，按版本号只跑一次（装新版本后会再跑一遍）。
     */
    private void runStartupMigrationsOnce() {
        android.content.SharedPreferences sp = getSharedPreferences(STARTUP_PREFS, MODE_PRIVATE);
        // 旧版默认作息（08:00 起）未被用户自定义过时，覆盖升级自动切换为交院默认作息
        if (!sp.getBoolean("sdjtu_default_schedule_migrated", false)) {
            ScheduleSettings saved = ScheduleSettingsStore.load(this);
            if (saved.isLegacyDefault()) {
                ScheduleSettingsStore.clear(this);
            }
            sp.edit().putBoolean("sdjtu_default_schedule_migrated", true).apply();
        }
        if (sp.getInt("migrated_vc", 0) == BuildConfig.VERSION_CODE) return;
        // 一次性清理历史学期被全局日期污染的 startDate（有 startDateTouched 标记的不动）
        SemesterArchive.clearUnsetStartDates(this);
        if (!SemesterArchive.migrateSdjtuStartDate(this)) return;
        // 迁移后以归档为准刷新镜像，防止 Prefs/镜像里的旧日期复活
        SemesterArchive.reconcileActiveMeta(this);
        sp.edit().putInt("migrated_vc", BuildConfig.VERSION_CODE).apply();
    }

    private void ensureSettingsTab() {
        if (settingsFragment != null) return;
        settingsFragment = getLayoutInflater().inflate(R.layout.fragment_settings, contentContainer, false);
        contentContainer.addView(settingsFragment);
        settings = new SettingsTab(this, settingsFragment);
        settings.bind();
    }

    private void showTab(int tab) {
        currentTab = tab;
        if (tab == TAB_SETTINGS) ensureSettingsTab();
        campusFragment.setVisibility(tab == TAB_TIMETABLE ? View.VISIBLE : View.GONE);
        if (settingsFragment != null) settingsFragment.setVisibility(tab == TAB_SETTINGS ? View.VISIBLE : View.GONE);

        if (textDock != null && textDock.selectedTab() != tab) textDock.setSelected(tab, true);
        if (dockBlur != null) dockBlur.markDirty();

        if (tab == TAB_TIMETABLE) refreshTimetableTab();
        else refreshSettingsTab();
    }

    /** 悬浮 Dock 占掉的底部高度（胶囊高 + 离底边距 + 一点呼吸空间）。 */
    private int dockInset() {
        if (textDock == null || textDock.getHeight() == 0) return 0;
        android.view.ViewGroup.MarginLayoutParams lp =
                (android.view.ViewGroup.MarginLayoutParams) textDock.getLayoutParams();
        return textDock.getHeight() + lp.bottomMargin + Math.round(6 * getResources().getDisplayMetrics().density);
    }

    // ==================== 课表 ====================

    private void bindTimetableTab() {
        timetableView = campusFragment.findViewById(R.id.timetable_view);
        if (timetableView != null) {
            timetableView.setOnSessionClickListener(new TimetableView.OnSessionClickListener() {
                @Override
                public void onSessionClick(Timetable.Course course, Timetable.Session session,
                                           boolean activeThisWeek) {
                    openCourseEditor(course, session);
                }

                @Override
                public void onEmptySlotClick(int dayOfWeek, int startSection) {
                    openCourseEditor(null, dayOfWeek, startSection);
                }

                @Override
                public void onEmptyRangeDrawn(int dayOfWeek, int startSection, int endSection) {
                    Intent i = new Intent(MainActivity.this, CourseEditActivity.class);
                    i.putExtra(CourseEditActivity.EXTRA_DEFAULT_DAY, dayOfWeek);
                    i.putExtra(CourseEditActivity.EXTRA_DEFAULT_SECTION, startSection);
                    i.putExtra(CourseEditActivity.EXTRA_DEFAULT_END_SECTION, endSection);
                    i.putExtra(CourseEditActivity.EXTRA_CURRENT_WEEK, currentViewedWeek());
                    startActivity(i);
                }

                @Override
                public void onHint(String message) {
                    Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onWeekSwipe(int delta) {
                    changeWeek(delta);
                }

                @Override
                public void onMarkedClick(Timetable.Course course, Timetable.Session session,
                                          String label, String adjustId, boolean extra) {
                    CourseBlockActions.onMarked(MainActivity.this, course, session, label, adjustId, extra,
                            MainActivity.this::refreshTimetableTab, MainActivity.this::openCourseEditor);
                }

                @Override
                public void onSessionLongPress(Timetable.Course course, Timetable.Session session,
                                               boolean active, String label, String adjustId,
                                               boolean extra, int week) {
                    CourseBlockActions.onLongPress(MainActivity.this, course, session, active, label, adjustId,
                            extra, week, MainActivity.this::refreshTimetableTab,
                            MainActivity.this::openCourseEditor);
                }

                @Override
                public void onExamClick(com.ecut.campus.data.ExamCountdown.Entry exam) {
                    String cd = com.ecut.campus.data.ExamCountdown.countdownLabel(exam, System.currentTimeMillis());
                    StringBuilder msg = new StringBuilder();
                    msg.append("倒计时：").append(cd);
                    if (!exam.timeRaw.isEmpty()) msg.append("\n时间：").append(exam.timeRaw);
                    if (!exam.room.isEmpty()) msg.append("\n考场：").append(exam.room);
                    if (!exam.seat.isEmpty()) msg.append("\n座位：").append(exam.seat);
                    new AlertDialog.Builder(MainActivity.this)
                            .setTitle("[考试] " + exam.name)
                            .setMessage(msg.toString())
                            .setPositiveButton("查看全部考试", (d, w) ->
                                    startActivity(new Intent(MainActivity.this, SdjtuExamActivity.class)))
                            .setNegativeButton("关闭", null)
                            .show();
                }

                @Override
                public void onWeekSettling(int delta) {
                    // 手指一松就先翻标题，不等吸附动画结束
                    int total = Prefs.totalWeeks(MainActivity.this);
                    int next = Math.max(1, Math.min(total, currentViewedWeek() + delta));
                    TextView title = campusFragment.findViewById(R.id.tv_week_title);
                    if (title != null) title.setText(gaugeWeekTitle(next, total));
                }
            });
            timetableView.setOnVerticalScrollListener(() -> {
                if (dockBlur != null) dockBlur.markDirty();
            });
        }

        TextView scrubTitle = campusFragment.findViewById(R.id.tv_week_title);
        if (scrubTitle != null) {
            scrubTitle.setOnTouchListener(new WeekScrubber(scrubTitle, new WeekScrubber.Host() {
                @Override public int viewedWeek() { return currentViewedWeek(); }
                @Override public int actualWeek() { return actualCurrentWeek(); }
                @Override public int totalWeeks() { return Prefs.totalWeeks(MainActivity.this); }
                @Override public void previewWeek(int week) {
                    scrubTitle.setText(gaugeWeekTitle(week, Prefs.totalWeeks(MainActivity.this)));
                    if (timetableView != null) timetableView.setCurrentWeek(week);
                }
                @Override public void commitWeek(int week) {
                    Prefs.setCurrentWeek(MainActivity.this, week);
                    refreshTimetableTab();
                }
                @Override public void onTitleClick(View v) { showWeekMenu(v); }
            }));
        }

        View backToCurrent = campusFragment.findViewById(R.id.btn_back_to_current_week);
        if (backToCurrent != null) {
            backToCurrent.setOnClickListener(v -> jumpToWeek(actualCurrentWeek()));
        }

        View syncBtn = campusFragment.findViewById(R.id.btn_sync_timetable);
        if (syncBtn != null) syncBtn.setOnClickListener(v -> syncTimetable());

        View addBtn = campusFragment.findViewById(R.id.btn_add_course);
        if (addBtn != null) addBtn.setOnClickListener(v -> {
            int day = WeekMath.todayDayOfWeek();
            if (Prefs.displayDays(this) == 5) day = Math.min(5, day);
            openCourseEditor(null, day, 1);
        });

        View moreBtn = campusFragment.findViewById(R.id.btn_more);
        if (moreBtn != null) moreBtn.setOnClickListener(this::showTimetableMenu);
    }

    /** 「⋯」：只留常用的四项，其余收进分享和课表设置。 */
    private void showTimetableMenu(View anchor) {
        new GaugeMenu(this)
                .add("同步课表")
                .add("切换学期")
                .divider()
                .add("分享课表")
                .add("课表设置")
                .show(anchor, title -> {
                    if ("同步课表".equals(title)) syncTimetable();
                    else if ("切换学期".equals(title)) semesterPicker.show();
                    else if ("分享课表".equals(title)) showShareMenu(anchor);
                    else startActivity(new Intent(this, TimetableSettingsActivity.class));
                });
    }

    private void showShareMenu(View anchor) {
        anchor.post(() -> new GaugeMenu(this)
                .add("二维码 / 分享码")
                .divider()
                .add("导出到日历 (ICS)")
                .show(anchor, title -> {
                    if (title.startsWith("二维码")) startActivity(new Intent(this, ShareActivity.class));
                    else exportIcs();
                }));
    }

    final SemesterPicker semesterPicker = new SemesterPicker(this);
    private final HistorySync historySync = new HistorySync(this);

    /** 点击左上角周数：打开旋钮选周。 */
    private void showWeekMenu(View anchor) {
        WeekPickerDialog.show(this, currentViewedWeek(), Prefs.totalWeeks(this), actualCurrentWeek());
    }

    /** 跳到某周并重画（选周旋钮、回本周按钮共用）。 */
    void jumpToWeek(int week) {
        Prefs.setCurrentWeek(this, week);
        refreshTimetableTab();
    }


    private void changeWeek(int delta) {
        int total = Prefs.totalWeeks(this);
        int current = currentViewedWeek();
        int next = Math.max(1, Math.min(total, current + delta));
        if (next == current) return;
        Prefs.setCurrentWeek(this, next);
        refreshTimetableTab();
    }

    private int currentViewedWeek() {
        if (!Prefs.hasCurrentWeek(this)) {
            int actual = actualCurrentWeek();
            Prefs.setCurrentWeek(this, actual);
            return actual;
        }
        return Prefs.currentWeek(this);
    }

    /** 本进程最后一次「对齐到本周」的日期；冷启动为空，所以一打开就对齐。 */
    private static String lastShownDay = "";

    private int actualCurrentWeek() {
        String start = effectiveStart();
        if (start.isEmpty()) return 1;
        return WeekMath.currentWeek(start, Prefs.totalWeeks(this));
    }

    private String effectiveStart() {
        return WeekMath.normalizeToMonday(Prefs.semesterStartDate(this));
    }

    private CharSequence gaugeWeekTitle(int week, int total) {
        return MainSyncHelper.gaugeWeekTitle(this, week);
    }

    private String staleHint() {
        return MainSyncHelper.staleHint(this);
    }

    void refreshSettingsTab() {
        if (settings != null) settings.refresh();
    }

    void refreshTimetableTab() {
        currentTimetable = TimetableStore.load(this);
        scheduleSettings = ScheduleSettingsStore.load(this);
        // 导入的课用到了后面的节次就自动扩展，只增不减。
        int usedMax = ScheduleSettings.maxSectionUsed(currentTimetable);
        if (scheduleSettings.ensureCovers(usedMax) && ScheduleSettingsStore.save(this, scheduleSettings)) {
            Toast.makeText(this, "课表用到了第 " + usedMax + " 节，已改成每天 " + usedMax + " 节", Toast.LENGTH_LONG).show();
        }
        int week = currentViewedWeek();
        int total = Prefs.totalWeeks(this);
        String start = effectiveStart();
        if (week > total) {
            week = total;
            Prefs.setCurrentWeek(this, week);
        }

        TextView weekTitle = campusFragment.findViewById(R.id.tv_week_title);
        if (weekTitle != null) {
            weekTitle.setText(gaugeWeekTitle(week, total));
            weekTitle.setOnClickListener(this::showWeekMenu);
        }

        TextView subtitle = campusFragment.findViewById(R.id.tv_semester_subtitle);
        if (subtitle != null) {
            int until = WeekMath.daysUntilStart(start);
            boolean needStart = start.isEmpty();
            boolean needLogin = !needStart && com.ecut.campus.data.SessionNotice.needsAction(
                    com.ecut.campus.data.SessionNotice.of(Prefs.sessionExpired(this),
                            SdjtuCredentials.isSaved(this), SdjtuAutoLogin.isPaused(this), false));
            String makeupSummary = needStart ? ""
                    : com.ecut.campus.data.Holidays.weekNoticeSummary(currentTimetable, start, week);
            if (needStart) {
                subtitle.setOnClickListener(v -> startActivity(new Intent(this, TimetableSettingsActivity.class)));
                subtitle.setClickable(true);
            } else if (needLogin) {
                subtitle.setOnClickListener(v -> WebShellActivity.startManualLoginForSync(
                        this, WebShellActivity.REQ_SDJTU_TIMETABLE, "课表", null));
                subtitle.setClickable(true);
            } else if (!makeupSummary.isEmpty()) {
                final int w = week;
                subtitle.setOnClickListener(v -> showMakeupDialog(start, w));
                subtitle.setClickable(true);
            } else {
                subtitle.setOnClickListener(null);
                subtitle.setClickable(false);
            }
            String extraHint = staleHint();
            if (!makeupSummary.isEmpty() && extraHint.isEmpty()) {
                extraHint = "  ·  " + makeupSummary;
            }
            subtitle.setText(needStart
                    ? "还没设第 1 周周一 · 点这里设置"
                    : (until > 0 ? "距开学 " + until + " 天 · "
                            : (week != actualCurrentWeek() ? "本周是第 " + actualCurrentWeek() + " 周 · " : ""))
                            + WeekMath.weekRangeLabel(start, week)
                            + extraHint);
        }

        if (timetableView != null) {
            timetableView.setSchedule(scheduleSettings);
            timetableView.setSemesterStartDate(start);
            timetableView.setDisplayDays(Prefs.displayDays(this));
            timetableView.setTotalWeeks(total);
            timetableView.setShowInactiveCourses(Prefs.showInactiveCourses(this));
            timetableView.setShowCourseCode(Prefs.showCourseCode(this));
            timetableView.setHiddenLocationPhrases(Prefs.hiddenLocationPhrases(this));
            timetableView.setFitAllSections(Prefs.fitAllSections(this));
            timetableView.setWarnCourses(com.ecut.campus.fun.FunPrefs.absenceWarnings(this, currentTimetable, total));
            timetableView.setTodoCounts(CourseTodoStore.load(this).pendingCounts());
            timetableView.setExamEntries(Prefs.examInTimetable(this)
                    ? ExamReminder.loadEntries(this) : java.util.Collections.emptyList());
            timetableView.setTimetable(currentTimetable, week);
            if (textDock != null && textDock.getHeight() > 0) {
                timetableView.setBottomInset(dockInset());
            }
        }
        if (dockBlur != null) dockBlur.markDirty();
        RainWeather.refreshIfNeeded(this, false, () -> {
            if (!isFinishing() && timetableView != null) timetableView.invalidate();
        });
    }

    private void openCourseEditor(@Nullable Timetable.Course course, Timetable.Session session) {
        Intent i = new Intent(this, CourseEditActivity.class);
        if (course != null) {
            i.putExtra(CourseEditActivity.EXTRA_COURSE_KEY, course.key);
            i.putExtra(CourseEditActivity.EXTRA_SESSION_INDEX, course.sessions.indexOf(session));
        }
        i.putExtra(CourseEditActivity.EXTRA_CURRENT_WEEK, currentViewedWeek());
        startActivity(i);
    }

    private void openCourseEditor(@Nullable Timetable.Course course, int day, int startSection) {
        Intent i = new Intent(this, CourseEditActivity.class);
        if (course != null) {
            i.putExtra(CourseEditActivity.EXTRA_COURSE_KEY, course.key);
        } else {
            i.putExtra(CourseEditActivity.EXTRA_DEFAULT_DAY, day);
            i.putExtra(CourseEditActivity.EXTRA_DEFAULT_SECTION, startSection);
        }
        i.putExtra(CourseEditActivity.EXTRA_CURRENT_WEEK, currentViewedWeek());
        startActivity(i);
    }


    void openPortal() {
        WebShellActivity.openManualPortal(this);
    }

    void exportIcs() {
        TimetableFiles.exportIcs(this, currentTimetable, scheduleSettings);
    }


    void syncTimetable() {
        startSilentSync(CampusSyncClient.Kind.TIMETABLE);
    }

    void autoLogin() {
        MainSyncHelper.startAutoLogin(this);
    }

    private android.animation.ObjectAnimator syncSpinAnim;

    void refreshTimetableSyncButton() {
        if (campusFragment == null) return;
        View btn = campusFragment.findViewById(R.id.btn_sync_timetable);
        if (btn == null) return;
        boolean busy = activeSync != null && activeSync.isRunning();
        btn.setEnabled(!busy);
        btn.setAlpha(busy ? 0.55f : 1f);
        if (busy) {
            if (android.animation.ValueAnimator.areAnimatorsEnabled() && syncSpinAnim == null) {
                syncSpinAnim = android.animation.ObjectAnimator.ofFloat(btn, View.ROTATION, 0f, 360f);
                syncSpinAnim.setDuration(900L);
                syncSpinAnim.setRepeatCount(android.animation.ValueAnimator.INFINITE);
                syncSpinAnim.setInterpolator(new android.view.animation.LinearInterpolator());
                syncSpinAnim.start();
            }
        } else {
            if (syncSpinAnim != null) {
                syncSpinAnim.cancel();
                syncSpinAnim = null;
            }
            btn.setRotation(0f);
        }
    }

    private void syncGrades() {
        startSilentSync(CampusSyncClient.Kind.GRADES);
    }

    private void startSilentSync(CampusSyncClient.Kind kind) {
        MainSyncHelper.startSilentSync(this, kind, true);
    }


    WebView newWebViewForSync() {
        WebView view = new WebView(this);
        view.setLayoutParams(new ViewGroup.LayoutParams(1, 1));
        // 保持 VISIBLE 但完全透明：INVISIBLE 可能让 WebView 暂停渲染与定时器。
        view.setAlpha(0f);
        syncHost.addView(view);
        return view;
    }

    @Override
    protected void onDestroy() {
        if (syncSpinAnim != null) {
            syncSpinAnim.cancel();
            syncSpinAnim = null;
        }
        if (activeSync != null) {
            activeSync.cancel();
            activeSync = null;
        }
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == WebShellActivity.REQ_SDJTU_TIMETABLE || requestCode == WebShellActivity.REQ_SDJTU_GRADES) {
            if (resultCode == Activity.RESULT_OK) {
                MainSyncHelper.startSilentSync(this, requestCode == WebShellActivity.REQ_SDJTU_TIMETABLE
                        ? CampusSyncClient.Kind.TIMETABLE : CampusSyncClient.Kind.GRADES, false);
            }
        } else if (requestCode == TimetableFiles.REQ_IMPORT_JSON && resultCode == Activity.RESULT_OK && data != null) {
            if (TimetableFiles.importFrom(this, data.getData())) {
                refreshTimetableTab();
                refreshSettingsTab();
            }
        } else if (requestCode == AppBackupManager.REQ_RESTORE && resultCode == Activity.RESULT_OK && data != null) {
            AppBackupManager.handleRestoreUri(this, data.getData(), () -> {
                refreshTimetableTab();
                refreshSettingsTab();
            });
        } else if (requestCode == WidgetBg.REQ_PICK && resultCode == Activity.RESULT_OK && data != null) {
            android.net.Uri picked = data.getData();
            if (settings != null) settings.onWidgetImagePicked(picked);
            else new Thread(() -> {
                if (WidgetBg.importFrom(getApplicationContext(), picked)) ClassWidget.updateAll(getApplicationContext());
            }, "widget-bg").start();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == RainWeather.REQ_LOCATION && RainWeather.hasPermission(this)) {
            RainWeather.refreshIfNeeded(this, true, () -> {
                if (!isFinishing() && timetableView != null) timetableView.invalidate();
            });
        } else if (requestCode == CalendarSyncManager.REQ_CALENDAR_PERM) {
            CalendarSyncManager.onPermissionResult(this, grantResults, this::refreshSettingsTab);
        }
    }

    private void showMakeupDialog(String start, int week) {
        java.util.List<String> lines = com.ecut.campus.data.Holidays.weekMakeupNotices(currentTimetable, start, week);
        if (lines.isEmpty()) return;
        new AlertDialog.Builder(this)
                .setTitle("第 " + week + " 周调休安排")
                .setMessage(android.text.TextUtils.join("\n", lines)
                        + "\n\n课表已按法定调休自动调整，学校另有通知时可长按课块单独修改。")
                .setPositiveButton("知道了", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        if (currentTab == TAB_SETTINGS && settings != null && settings.backToRoot()) return;
        super.onBackPressed();
    }

    @Override
    protected void onStop() {
        super.onStop();
        // 离开 App 时（课表/作息可能刚改过）顺手重排课前提醒、刷新桌面小组件
        ClassReminder.reschedule(this);
        ExamReminder.reschedule(this);
        TodoReminder.reschedule(this);
        ClassWidget.updateAll(this);
    }


    @Override
    protected void onResume() {
        super.onResume();
        // 每次进 App（或跨天回来）都先回到现实中的本周，不停在上次翻到的周
        String today = WeekMath.todayDateLabel();
        boolean dayChanged = !today.equals(lastShownDay);
        if (dayChanged) {
            lastShownDay = today;
            Prefs.setCurrentWeek(this, actualCurrentWeek());
        }
        refreshUpdateBadges();
        boolean justCreated = skipNextResumeRefresh;
        skipNextResumeRefresh = false;
        if (!justCreated) {
            Updater.autoCheck(this, this::refreshUpdateBadges);
        }
        // onCreate 刚刷新过一遍，冷启动不重复读盘重绘
        if (justCreated && !dayChanged) return;
        if (currentTab == TAB_TIMETABLE) refreshTimetableTab();
        else refreshSettingsTab();
    }
}
